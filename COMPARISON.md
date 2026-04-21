# Fire-and-Forget Strategy Comparison

This document compares three strategies used in this project to trigger a fire-and-forget
side-effect (counting products in the same category) after saving a new product.

---

## 1. Reactor — `POST /products/reactive`

**Entry point:** `ProductHandler.createReactive` → `ProductService.createReactive`

### How it works

```kotlin
fun createReactive(product: Product): Mono<Product> =
    repository.save(product)
        .doOnSuccess { saved -> triggerCategoryCountReactive(saved.category) }

private fun triggerCategoryCountReactive(category: String) {
    repository.countByCategory(category)
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            { count -> log.info("Category '{}' now has {} product(s)", category, count) },
            { error -> log.error(...) }
        )
}
```

### Threading model

Everything is built as a lazy pipeline. No code runs until the HTTP server subscribes to
the returned `Mono`. When `.subscribe()` is called inside `doOnSuccess`, a new independent
subscription is started on `Schedulers.boundedElastic()` — a thread pool designed for
blocking or I/O-bound work that should not run on the event-loop threads. The main
response completes without waiting for that subscription.

```
Reactor event-loop thread
  └── subscribe to save(product)
        └── doOnSuccess fires
              └── subscribe to countByCategory  ← detached, runs on boundedElastic thread
  └── response returned to client               ← happens immediately
```

### Fire-and-forget mechanism

`.subscribe()` is called without chaining the result back into the main `Mono`. This
creates a detached subscription — the Reactor runtime tracks it internally, but the caller
never sees it. Errors must be handled inside the subscribe callbacks, because there is no
outer subscriber to propagate them to.

### Pros
- Fully native to the Reactor model; no extra abstractions.
- `boundedElastic` has a configurable queue and thread cap, providing natural backpressure.

### Cons
- `.subscribe()` inside a pipeline is easy to misuse — forgetting `subscribeOn` would run
  the side-effect on the event-loop thread, starving other requests.
- Error handling is buried inside callbacks, not in the main flow.
- Harder to test: the detached subscription runs asynchronously and is not returned.

---

## 2. Kotlin Coroutines — `POST /products/coroutine`

**Entry point:** `ProductHandler.createCoroutine` → `ProductService.createCoroutine`

### How it works

```kotlin
suspend fun createCoroutine(product: Product): Product {
    val saved = repository.save(product).awaitSingle()
    serviceScope.launch {
        runCatching { repository.countByCategory(saved.category).awaitSingle() }
            .onSuccess { count -> log.info(...) }
            .onFailure { error -> log.error(...) }
    }
    return saved
}
```

The handler bridges back to Reactor with `mono { }`:

```kotlin
fun createCoroutine(request: ServerRequest): Mono<ServerResponse> =
    mono {
        val product = request.bodyToMono<Product>().awaitSingle()
        val saved = service.createCoroutine(product)
        ServerResponse.ok().bodyValue(saved).awaitSingle()
    }
```

### Threading model

`awaitSingle()` suspends the coroutine at each await point and resumes it when the
reactive result is ready — without blocking any thread. `launch { }` starts a new
coroutine on `serviceScope` (backed by `Dispatchers.IO`) and immediately returns.
The current coroutine continues and returns `saved` to the caller.

```
Reactor event-loop thread (via mono { })
  └── awaitSingle on save(product) → suspends, resumes when done
        └── launch { } fires on serviceScope (Dispatchers.IO)  ← detached coroutine
  └── return saved → ServerResponse                            ← happens immediately
```

`serviceScope` is built with `SupervisorJob`, meaning a failure in one `launch` does not
cancel the scope or affect other coroutines.

### Fire-and-forget mechanism

`launch { }` starts a coroutine and returns a `Job` immediately. The launched coroutine
runs independently. Unlike `.subscribe()`, it is structured — it belongs to `serviceScope`,
so if the service is shut down, the scope can be cancelled and all child jobs are cleaned
up. `runCatching` handles errors within the coroutine.

### Pros
- Sequential, readable code — looks like imperative code but is fully non-blocking.
- Structured concurrency: the fire-and-forget is tracked by a named scope, not floating.
- `runCatching` keeps error handling inline and idiomatic.
- Easy to test: `serviceScope` can be replaced with `TestScope` in unit tests.

### Cons
- Requires `kotlinx-coroutines-reactor` bridge (`awaitSingle`, `mono { }`).
- `serviceScope` must be carefully managed in long-lived services (cancellation, lifecycle).
- Mixing coroutines and Reactor in the same codebase adds cognitive overhead.

---

## 3. Spring `@Async` — `POST /products/async`

**Entry point:** `ProductHandler.createAsync` → `ProductService.createAsync` → `AsyncCategoryCounter.countAndLog`

### How it works

```kotlin
// ProductService
fun createAsync(product: Product): Mono<Product> =
    repository.save(product)
        .doOnSuccess { saved -> asyncCategoryCounter.countAndLog(saved.category) }

// AsyncCategoryCounter
@Async
fun countAndLog(category: String) {
    runCatching { repository.countByCategory(category).block() }
        .onSuccess { count -> log.info(...) }
        .onFailure { error -> log.error(...) }
}
```

`@EnableAsync` in `AsyncConfig` activates Spring's AOP proxy that intercepts `@Async`
method calls and submits them to a thread pool executor.

### Threading model

The main flow is still reactive: `save` returns a `Mono`, and `doOnSuccess` fires on the
event-loop thread. But `asyncCategoryCounter.countAndLog(...)` is an `@Async` call — Spring
intercepts it and immediately submits the work to an executor thread pool, returning to
the caller without waiting. The submitted task runs on a real OS thread, where calling
`.block()` is safe.

```
Reactor event-loop thread
  └── subscribe to save(product)
        └── doOnSuccess fires
              └── @Async proxy submits countAndLog to thread pool ← returns immediately
  └── response returned to client                                 ← happens immediately

Thread pool thread (Spring TaskExecutor)
  └── countAndLog runs
        └── .block() waits for countByCategory result
        └── log result
```

**Important:** `@Async` must be called through a Spring proxy. Calling it on `this` inside
the same bean (self-invocation) bypasses the proxy and runs synchronously. That is why
`AsyncCategoryCounter` is a separate Spring bean.

### Fire-and-forget mechanism

Spring's AOP proxy wraps the method call in a `Runnable` and submits it to the configured
`TaskExecutor`. The caller gets back control immediately. If the method returns `void`,
exceptions are sent to the `AsyncUncaughtExceptionHandler`. If it returns
`CompletableFuture`, the caller can optionally wait or attach callbacks.

### Pros
- Familiar Spring model — no Reactor or coroutine knowledge required.
- `.block()` is safe inside `@Async` threads, simplifying reactive-to-imperative bridging.
- Thread pool is managed and configurable via `TaskExecutor` bean.

### Cons
- Each fire-and-forget call consumes a real OS thread for the duration of `.block()`.
  Under high load this can exhaust the thread pool.
- Requires a separate bean to avoid the self-invocation proxy trap — this is a hidden
  gotcha that is easy to miss.
- `@Async` is Spring AOP magic: it only works on Spring-managed beans called through the
  proxy, which makes the behavior non-obvious and harder to test.
- Mixing `@Async` (thread-based) with WebFlux (event-loop) is architecturally inconsistent
  and can cause thread starvation if the executor is not tuned properly.

---

## Side-by-Side Summary

| Dimension              | Reactor `.subscribe()`          | Coroutines `launch { }`            | Spring `@Async`                     |
|------------------------|----------------------------------|-------------------------------------|--------------------------------------|
| **Abstraction**        | Reactive streams operators       | Structured concurrency              | Thread pool + AOP proxy             |
| **Thread usage**       | boundedElastic pool (non-blocking) | Dispatchers.IO (non-blocking)     | Real OS thread (blocking allowed)   |
| **Readability**        | Operator chains (functional)     | Sequential (imperative style)       | Imperative (familiar Spring style)  |
| **Fire-and-forget**    | Detached `.subscribe()`          | `launch {}` on owned scope          | Submitted to TaskExecutor           |
| **Error handling**     | Subscribe error callback         | `runCatching` inside coroutine      | `AsyncUncaughtExceptionHandler`     |
| **Lifecycle control**  | None (floating subscription)     | Tied to `CoroutineScope`            | Tied to TaskExecutor lifecycle      |
| **Testability**        | Hard (async, detached)           | Good (replaceable TestScope)        | Moderate (requires Spring context)  |
| **Hidden gotcha**      | Must use `subscribeOn`           | Must manage scope cancellation      | Self-invocation bypasses proxy      |
| **Best fit**           | Pure reactive codebases          | Kotlin-first, mixed reactive apps   | Legacy/traditional Spring apps      |

---

## Recommendation

- If your entire stack is reactive and the team is comfortable with Reactor operators,
  **Reactor `.subscribe()`** is the most consistent choice.
- If you are writing Kotlin and want readable, maintainable code with proper lifecycle
  management, **Coroutines `launch { }`** is the best option.
- **`@Async`** is best avoided in new WebFlux applications. It introduces thread-pool
  blocking into an event-loop architecture and relies on AOP proxying rules that are easy
  to violate. It is most appropriate when migrating a legacy Spring MVC app incrementally.
