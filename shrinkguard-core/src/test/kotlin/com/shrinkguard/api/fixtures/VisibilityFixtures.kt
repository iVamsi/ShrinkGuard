package com.shrinkguard.api.fixtures

class PublicType {
    fun visible() {}
    internal fun hidden() {}
}

internal class InternalType {
    fun leaked() {}
}
