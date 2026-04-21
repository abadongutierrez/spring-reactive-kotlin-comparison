package com.example.reactivedemo

import org.springframework.data.repository.reactive.ReactiveCrudRepository

interface ProductRepository : ReactiveCrudRepository<Product, Long> {

    fun countByCategory(category: String): Mono<Long>
}
