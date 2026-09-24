package dev.evestaticmapplanner.sovereignty

import java.util.concurrent.atomic.AtomicReference

/** Connects the typed Provider request hook after the refresh coordinator is fully constructed. */
internal class SovereigntyRefreshRequest {
    private val delegate = AtomicReference<(() -> Boolean)?>(null)

    fun request(): Boolean = delegate.get()?.invoke() ?: false

    fun attach(request: () -> Boolean) {
        check(delegate.compareAndSet(null, request)) { "Sovereignty refresh request is already attached" }
    }

    fun clear() {
        delegate.set(null)
    }
}
