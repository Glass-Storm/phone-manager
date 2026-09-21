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
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the packaged manifest wires the full-backup rules`() {
        // Given the packaged application
        val app =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .applicationInfo!!

        // When the full-backup resource is read reflectively (it is @hide in the
        // public SDK stub, exactly like networkSecurityConfigRes)
        val fullBackup = app.javaClass.getField("fullBackupContent").get(app)

        // Then it points at the real resource, so the rules are actually applied
        assertThat(fullBackup).isEqualTo(R.xml.backup_rules)
    }

    @Test
    fun `the manifest declares both backup rule attributes`() {
        // Given the manifest source (the dataExtractionRules field only exists from
        // API 31, so the API-29 packaged application cannot report it)
        val source = readSource("src/main/AndroidManifest.xml")

        // Then both attributes are declared and reference the right resources
        assertThat(source).contains("android:fullBackupContent=\"@xml/backup_rules\"")
        assertThat(source).contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
    }

    @Test
    fun `the full-backup rules exclude the api key prefs and the sqlite database`() {
        // Given the raw rules a reviewer would read
        val source = readSource("src/main/res/xml/backup_rules.xml")

        // Then both secrets-bearing artifacts are excluded
        assertThat(source).contains("<exclude domain=\"sharedpref\" path=\"runtime_config.xml\"")
        assertThat(source).contains("<exclude domain=\"database\" path=\"phone_manager.db\"")
        assertThat(source).contains("full-backup-content")
    }

    @Test
    fun `the data-extraction rules exclude both artifacts from cloud backup and transfer`() {
        // Given the raw rules
        val source = readSource("src/main/res/xml/data_extraction_rules.xml")

        // Then cloud backup and device transfer each exclude both artifacts
        assertThat(source).contains("cloud-backup")
        assertThat(source).contains("device-transfer")
        assertThat(source).contains("<exclude domain=\"sharedpref\" path=\"runtime_config.xml\"")
        assertThat(source).contains("<exclude domain=\"database\" path=\"phone_manager.db\"")
    }

    @Test
    fun `the parsed backup rules contain the exclusions`() {
        // Given the parsed resource, which proves the XML is well-formed and reachable
        val parser = context.resources.getXml(R.xml.backup_rules)

        // When every exclude element is collected
        val excludes = mutableListOf<String>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude") {
                excludes.add(
                    "${parser.getAttributeValue(null, "domain")}:" +
                        parser.getAttributeValue(null, "path"),
                )
            }
            event = parser.next()
        }

        // Then both exclusions are present in the parsed tree
        assertThat(excludes).containsAtLeast(
            "sharedpref:runtime_config.xml",
            "database:phone_manager.db",
        )
    }

    private fun readSource(path: String): String =
        // Gradle runs unit tests with the MODULE root as the working directory.
        java.io
            .File(path)
            .takeIf { it.exists() }
            ?.bufferedReader()
            ?.use(BufferedReader::readText)
            ?: ""
}
