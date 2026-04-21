package com.example.reactivedemo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Service
class ProductService(
    private val repository: ProductRepository,
    private val asyncCategoryCounter: AsyncCategoryCounter,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun findAll(): Flux<Product> = repository.findAll()

    fun findById(id: Long): Mono<Product> = repository.findById(id)

    // Reactor: pure operator chain, no threads involved until subscription.
    // Fire-and-forget detaches via .subscribe() on a boundedElastic thread.
    fun createReactive(product: Product): Mono<Product> =
        repository.save(product)
            .doOnSuccess { saved -> triggerCategoryCountReactive(saved.category) }

    // Coroutines: sequential suspend calls bridged to Reactor via awaitSingle().
    // Fire-and-forget detaches via launch { } on the service-owned CoroutineScope.
    suspend fun createCoroutine(product: Product): Product {
        val saved = repository.save(product).awaitSingle()
        serviceScope.launch {
            runCatching { repository.countByCategory(saved.category).awaitSingle() }
                .onSuccess { count -> log.info("Category '{}' now has {} product(s)", saved.category, count) }
                .onFailure { error -> log.error("Failed to count products for category '{}'", saved.category, error) }
        }
        return saved
    }

    // @Async: save is still reactive (Mono), but the fire-and-forget is handed off to
    // a Spring-managed thread pool via @Async. That thread is allowed to call .block().
    fun createAsync(product: Product): Mono<Product> =
        repository.save(product)
            .doOnSuccess { saved -> asyncCategoryCounter.countAndLog(saved.category) }

    fun delete(id: Long): Mono<Void> = repository.deleteById(id)

    private fun triggerCategoryCountReactive(category: String) {
        repository.countByCategory(category)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                { count -> log.info("Category '{}' now has {} product(s)", category, count) },
                { error -> log.error("Failed to count products for category '{}'", category, error) }
            )
    }
}
