package com.example.reactivedemo

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal

@Table("products")
data class Product(
    @Id val id: Long? = null,
    val name: String,
    val price: BigDecimal,
    val category: String,
)
