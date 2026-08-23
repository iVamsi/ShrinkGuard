package com.example.currency

data class Money(
    val amountMicros: Long,
    val currencyCode: String
) {
    fun formatted(): String {
        val major = amountMicros / 1_000_000
        val minor = (amountMicros % 1_000_000) / 10_000
        return "$currencyCode $major.${minor.toString().padStart(2, '0')}"
    }
}
