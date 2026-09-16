package com.glassstorm.phonemanager

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader

/**
 * Proves the backup exclusions are real: the manifest wires both rule files, and
 * each file excludes the two security-relevant artifacts.
 *
 * Source-file assertions are used because the merged rules are not exposed through
 * the packaged application; the manifest wiring is asserted through the packaged
 * `ApplicationInfo` instead, so a rule file that is never referenced still fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackupRulesTest {
    private val GoContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the packaged manifest wires the full-backup rules`() {
        // Given the packaged application
        val GoApp =
            GoContext.packageManager
                .getPackageInfo(GoContext.packageName, PackageManager.GET_PERMISSIONS)
                .applicationInfo!!

        // When the full-backup resource is read reflectively (it is @hide in the
        // public SDK stub, exactly like networkSecurityConfigRes)
        val GoFullBackup = GoApp.javaClass.getField("fullBackupContent").get(GoApp)

        // Then it points at the real resource, so the rules are actually applied
        assertThat(GoFullBackup).isEqualTo(R.xml.backup_rules)
    }

    @Test
    fun `the manifest declares both backup rule attributes`() {
        // Given the manifest source (the dataExtractionRules field only exists from
        // API 31, so the API-29 packaged application cannot report it)
        val GoSource = GoReadSource("src/main/AndroidManifest.xml")

        // Then both attributes are declared and reference the right resources
        assertThat(GoSource).contains("android:fullBackupContent=\"@xml/backup_rules\"")
        assertThat(GoSource).contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
    }

    @Test
    fun `the full-backup rules exclude the api key prefs and the sqlite database`() {
        // Given the raw rules a reviewer would read
        val GoSource = GoReadSource("src/main/res/xml/backup_rules.xml")

        // Then both secrets-bearing artifacts are excluded
        assertThat(GoSource).contains("<exclude domain=\"sharedpref\" path=\"runtime_config.xml\"")
        assertThat(GoSource).contains("<exclude domain=\"database\" path=\"phone_manager.db\"")
        assertThat(GoSource).contains("full-backup-content")
    }

    @Test
    fun `the data-extraction rules exclude both artifacts from cloud backup and transfer`() {
        // Given the raw rules
        val GoSource = GoReadSource("src/main/res/xml/data_extraction_rules.xml")

        // Then cloud backup and device transfer each exclude both artifacts
        assertThat(GoSource).contains("cloud-backup")
        assertThat(GoSource).contains("device-transfer")
        assertThat(GoSource).contains("<exclude domain=\"sharedpref\" path=\"runtime_config.xml\"")
        assertThat(GoSource).contains("<exclude domain=\"database\" path=\"phone_manager.db\"")
    }

    @Test
    fun `the parsed backup rules contain the exclusions`() {
        // Given the parsed resource, which proves the XML is well-formed and reachable
        val GoParser = GoContext.resources.getXml(R.xml.backup_rules)

        // When every exclude element is collected
        val GoExcludes = mutableListOf<String>()
        var GoEvent = GoParser.eventType
        while (GoEvent != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (GoEvent == org.xmlpull.v1.XmlPullParser.START_TAG && GoParser.name == "exclude") {
                GoExcludes.add(
                    "${GoParser.getAttributeValue(null, "domain")}:" +
                        GoParser.getAttributeValue(null, "path"),
                )
            }
            GoEvent = GoParser.next()
        }

        // Then both exclusions are present in the parsed tree
        assertThat(GoExcludes).containsAtLeast(
            "sharedpref:runtime_config.xml",
            "database:phone_manager.db",
        )
    }

    private fun GoReadSource(path: String): String =
        // Gradle runs unit tests with the MODULE root as the working directory.
        java.io
            .File(path)
            .takeIf { it.exists() }
            ?.bufferedReader()
            ?.use(BufferedReader::readText)
            ?: ""
}
