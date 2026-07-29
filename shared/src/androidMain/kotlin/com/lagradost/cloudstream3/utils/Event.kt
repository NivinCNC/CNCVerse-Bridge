package com.lagradost.cloudstream3.utils

/**
 * Dummy Event class to satisfy plugins accessing MainActivity.afterPluginsLoadedEvent, etc.
 */
class Event<T> {
    operator fun plusAssign(listener: (T) -> Unit) {}
    operator fun minusAssign(listener: (T) -> Unit) {}
    fun invoke(value: T) {}
}
