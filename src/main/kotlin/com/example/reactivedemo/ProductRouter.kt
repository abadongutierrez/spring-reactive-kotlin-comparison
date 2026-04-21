package com.example.reactivedemo

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router

@Configuration
class ProductRouter {

    @Bean
    fun routes(handler: ProductHandler): RouterFunction<ServerResponse> = router {
        "/products".nest {
            GET("", handler::findAll)
            GET("/{id}", handler::findById)
            // Reactor pipeline: returns Mono<Product> chained with flatMap operators
            POST("/reactive", handler::createReactive)
            // Coroutines: suspend function wrapped in mono { }, reads like sequential code
            POST("/coroutine", handler::createCoroutine)
            // @Async: save is reactive, fire-and-forget runs on a Spring thread-pool thread
            POST("/async", handler::createAsync)
            DELETE("/{id}", handler::delete)
        }
    }
}
