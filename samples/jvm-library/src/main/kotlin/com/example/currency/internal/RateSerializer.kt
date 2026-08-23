package com.example.currency.internal

import com.example.currency.Money

class RateSerializer {
    fun serialize(money: Money): String {
        return "${money.currencyCode}:${money.amountMicros}"
    }
}
