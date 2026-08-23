package com.example.currency

import com.example.currency.internal.RateSerializer

class CurrencyService {

    private val serializer: Any by lazy {
        // Reflection instantiation to demonstrate consumer rule verification
        Class.forName("com.example.currency.internal.RateSerializer").getDeclaredConstructor().newInstance()
    }

    fun convert(money: Money, targetRate: Double): Money {
        val newAmount = (money.amountMicros * targetRate).toLong()
        return Money(newAmount, money.currencyCode)
    }
}
