package com.glassstorm.phonemanager.domain.context

import com.glassstorm.phonemanager.core.model.Device
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Given/When/Then unit tests for the sing-box-style Context registry. */
class ContextTest {
    private class Counter {
        var value: Int = 0
    }

    @Test
    fun `register then fromContext returns the same instance`() {
        // Given a fresh context and one instance
        val ctx = Context()
        val instance = Counter()

        // When it is registered and later resolved
        register(ctx, instance)
        val resolved = fromContext<Counter>(ctx)

        // Then it is the identical instance, not a copy
        assertThat(resolved).isSameInstanceAs(instance)
    }

    @Test
    fun `fromContext throws MissingFromContextException for an unknown type`() {
        // Given an empty context
        val ctx = Context()

        // When/Then resolving an unregistered type fails with the typed exception
        assertThrows(MissingFromContextException::class.java) {
            fromContext<Counter>(ctx)
        }
    }

    @Test
    fun `fromContextOrNull returns null for an unknown type`() {
        // Given an empty context
        val ctx = Context()

        // When/Then the or-null variant does not throw
        assertThat(fromContextOrNull<Counter>(ctx)).isNull()
    }

    @Test
    fun `re-registering the same type overwrites the previous instance`() {
        // Given a context holding one instance
        val ctx = Context()
        val first = Counter()
        val second = Counter()
        register(ctx, first)

        // When a second instance of the same type is registered
        register(ctx, second)

        // Then resolution yields the newer instance
        assertThat(fromContext<Counter>(ctx)).isSameInstanceAs(second)
    }

    @Test
    fun `unregister then lookup throws`() {
        // Given a registered instance
        val ctx = Context()
        register(ctx, Counter())

        // When it is unregistered
        ctx.unregister(Counter::class)

        // Then resolution fails
        assertThrows(MissingFromContextException::class.java) {
            fromContext<Counter>(ctx)
        }
    }

    @Test
    fun `registry keys on the declared type so distinct types coexist`() {
        // Given two different types registered under the same context
        val ctx = Context()
        val counterInstance = Counter()
        val device =
            Device(
                deviceId = "d-1",
                deviceName = "glass",
                role = "GLASS",
                tokenHash = "hash-d-1",
                pairedAtMs = 1_000L,
                lastSeenMs = null,
            )
        register(ctx, counterInstance)
        register(ctx, device)

        // When both are resolved
        val resolvedCounter = fromContext<Counter>(ctx)
        val resolvedDevice = fromContext<Device>(ctx)

        // Then each keeps its own slot keyed by KClass
        assertThat(resolvedCounter).isSameInstanceAs(counterInstance)
        assertThat(resolvedDevice).isSameInstanceAs(device)
    }
}
