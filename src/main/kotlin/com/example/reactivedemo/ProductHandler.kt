package com.example.reactivedemo

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyToMono
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.mono

@Component
class ProductHandler(private val service: ProductService) {

    fun findAll(request: ServerRequest): Mono<ServerResponse> =
        ServerResponse.ok().body(service.findAll(), Product::class.java)

    fun findById(request: ServerRequest): Mono<ServerResponse> {
        val id = request.pathVariable("id").toLong()
        return service.findById(id)
            .flatMap { ServerResponse.ok().bodyValue(it) }
            .switchIfEmpty(ServerResponse.notFound().build())
    }

    // Reactor: the entire flow is a declarative chain of Mono operators.
    // Nothing executes until WebFlux subscribes to the returned Mono.
    // Fire-and-forget in the service detaches via .subscribe() on a boundedElastic thread.
    fun createReactive(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToMono<Product>()
            .flatMap { service.createReactive(it) }
            .flatMap { ServerResponse.ok().bodyValue(it) }

    // Coroutines: suspend calls inside mono { } look sequential but stay non-blocking.
    // mono { } bridges coroutines back to a Mono so WebFlux can subscribe to it normally.
    // Fire-and-forget in the service detaches via launch { } on a service-owned CoroutineScope.
    fun createCoroutine(request: ServerRequest): Mono<ServerResponse> =
        mono {
            val product = request.bodyToMono<Product>().awaitSingle()
            val saved = service.createCoroutine(product)
            ServerResponse.ok().bodyValue(saved).awaitSingle()
        }

    // @Async: save is reactive, but the fire-and-forget is handed to a Spring thread-pool
    // via @Async on a separate bean. That thread may call .block() without reactor warnings
    // because it is not a reactor event-loop thread.
    fun createAsync(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToMono<Product>()
            .flatMap { service.createAsync(it) }
            .flatMap { ServerResponse.ok().bodyValue(it) }

    fun delete(request: ServerRequest): Mono<ServerResponse> {
        val id = request.pathVariable("id").toLong()
        return service.delete(id)
            .then(ServerResponse.noContent().build())
    }
}
