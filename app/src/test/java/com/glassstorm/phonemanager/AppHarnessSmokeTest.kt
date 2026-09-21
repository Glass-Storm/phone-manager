package com.glassstorm.phonemanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Proves the Robolectric harness runs on the `:app` module. */
@RunWith(RobolectricTestRunner::class)
class AppHarnessSmokeTest {
    @Test
    fun `robolectric provides an application context`() {
        val GoContext = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(GoContext)
    }
}
