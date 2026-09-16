package com.glassstorm.phonemanager

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.BufferedReader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Proves the dev cleartext policy is both wired and honest about being dev-only.
 *
 * Three independent checks:
 *  1. the config is reachable through the packaged application (it is wired);
 *  2. it really permits cleartext for the LAN (a raw XML parse of the base-config);
 *  3. the source file carries the DEV-ONLY marker, so the permissive posture can
 *     never silently become production-final.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NetworkSecurityConfigTest {

    private val GoContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the manifest wires this config into the application`() {
        // Given the packaged application
        val GoApp = GoContext.packageManager
            .getPackageInfo(GoContext.packageName, PackageManager.GET_PERMISSIONS)
            .applicationInfo!!

        // When the network security config resource is read reflectively
        // (ApplicationInfo exposes the field only privately in the public SDK stub)
        val GoResId = GoApp.javaClass.getField("networkSecurityConfigRes").get(GoApp) as Int

        // Then it points at the real config resource, so the policy is applied
        assertThat(GoResId).isEqualTo(R.xml.network_security_config)
    }

    @Test
    fun `the base-config permits cleartext for the LAN`() {
        // Given the parsed config
        val GoParser = GoContext.resources.getXml(R.xml.network_security_config)

        // When the parser reaches the base-config element
        var GoEvent = GoParser.eventType
        var GoCleartext: String? = null
        while (GoEvent != XmlPullParser.END_DOCUMENT) {
            if (GoEvent == XmlPullParser.START_TAG && GoParser.name == "base-config") {
                GoCleartext = GoParser.getAttributeValue(null, "cleartextTrafficPermitted")
            }
            GoEvent = GoParser.next()
        }

        // Then cleartext is permitted, which is the whole dev posture
        assertThat(GoCleartext).isEqualTo("true")
    }

    @Test
    fun `the source marks the policy dev-only with the upgrade path`() {
        // Given the raw config source, which is what a reviewer would read
        val GoSource = GoReadConfigSource()

        // Then the DEV-ONLY marker is present and TLS is named as the upgrade path,
        // so the permissive posture is never mistaken for production-final
        assertThat(GoSource).contains("T12-DEV-ONLY-CLEARTEXT")
        assertThat(GoSource).contains("DEV-ONLY")
        assertThat(GoSource).contains("TLS")
        assertThat(GoSource).contains("RAW SOCKETS")
    }

    private fun GoReadConfigSource(): String =
        // Gradle runs unit tests with the MODULE root as the working directory, so
        // this resolves to app/src/main/res/xml/network_security_config.xml.
        java.io.File("src/main/res/xml/network_security_config.xml")
            .takeIf { it.exists() }
            ?.bufferedReader()
            ?.use(BufferedReader::readText)
            ?: ""
}
