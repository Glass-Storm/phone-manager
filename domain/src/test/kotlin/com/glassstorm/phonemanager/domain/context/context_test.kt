package com.glassstorm.phonemanager.domain.context

import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.dto.Device
import org.junit.Assert.assertThrows
import org.junit.Test

/** Given/When/Then unit tests for the sing-box-style Context registry. */
class ContextTest {

    private class GoCounter {
        var GoValue: Int = 0
    }

    @Test
    fun `register then FromContext returns the same instance`() {
        // Given a fresh context and one instance
        val GoCtx = Context()
        val GoInstance = GoCounter()

        // When it is registered and later resolved
        Register(GoCtx, GoInstance)
        val GoResolved = FromContext<GoCounter>(GoCtx)

        // Then it is the identical instance, not a copy
        assertThat(GoResolved).isSameInstanceAs(GoInstance)
    }

    @Test
    fun `FromContext throws MissingFromContextException for an unknown type`() {
        // Given an empty context
        val GoCtx = Context()

        // When/Then resolving an unregistered type fails with the typed exception
        assertThrows(MissingFromContextException::class.java) {
            FromContext<GoCounter>(GoCtx)
        }
    }

    @Test
    fun `FromContextOrNull returns null for an unknown type`() {
        // Given an empty context
        val GoCtx = Context()

        // When/Then the or-null variant does not throw
        assertThat(FromContextOrNull<GoCounter>(GoCtx)).isNull()
    }

    @Test
    fun `re-registering the same type overwrites the previous instance`() {
        // Given a context holding one instance
        val GoCtx = Context()
        val GoFirst = GoCounter()
        val GoSecond = GoCounter()
        Register(GoCtx, GoFirst)

        // When a second instance of the same type is registered
        Register(GoCtx, GoSecond)

        // Then resolution yields the newer instance
        assertThat(FromContext<GoCounter>(GoCtx)).isSameInstanceAs(GoSecond)
    }

    @Test
    fun `unregister then lookup throws`() {
        // Given a registered instance
        val GoCtx = Context()
        Register(GoCtx, GoCounter())

        // When it is unregistered
        GoCtx.GoUnregister(GoCounter::class)

        // Then resolution fails
        assertThrows(MissingFromContextException::class.java) {
            FromContext<GoCounter>(GoCtx)
        }
    }

    @Test
    fun `registry keys on the declared type so distinct types coexist`() {
        // Given two different types registered under the same context
        val GoCtx = Context()
        val GoCounterInstance = GoCounter()
        val GoDevice = Device(GoDeviceId = "d-1", GoDeviceName = "glass", GoRole = "GLASS")
        Register(GoCtx, GoCounterInstance)
        Register(GoCtx, GoDevice)

        // When both are resolved
        val GoResolvedCounter = FromContext<GoCounter>(GoCtx)
        val GoResolvedDevice = FromContext<Device>(GoCtx)

        // Then each keeps its own slot keyed by KClass
        assertThat(GoResolvedCounter).isSameInstanceAs(GoCounterInstance)
        assertThat(GoResolvedDevice).isSameInstanceAs(GoDevice)
    }
}
