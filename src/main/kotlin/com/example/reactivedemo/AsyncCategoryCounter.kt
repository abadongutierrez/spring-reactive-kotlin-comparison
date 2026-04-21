package com.example.reactivedemo

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class AsyncCategoryCounter(private val repository: ProductRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Runs in a Spring-managed thread pool (SimpleAsyncTaskExecutor by default).
    // Because this is a real OS thread, it is safe to call .block() here without
    // risking a reactor thread starvation deadlock.
    @Async
    fun countAndLog(category: String) {
        runCatching { repository.countByCategory(category).block() }
            .onSuccess { count -> log.info("Category '{}' now has {} product(s)", category, count) }
            .onFailure { error -> log.error("Failed to count products for category '{}'", category, error) }
    }
}
