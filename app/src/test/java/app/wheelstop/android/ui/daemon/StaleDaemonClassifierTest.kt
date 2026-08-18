package app.wheelstop.android.ui.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real `environ` content is NUL-separated, and the CLASSPATH carries the OEM
 * framework jar ahead of our APK. The observed live value on a BYD Seal was:
 *
 *   CLASSPATH=/system/framework/bmmcamera.jar:/data/app/app.wheelstop.android-tz5JXIhWkVeDto4606tCmw==/base.apk
 */
class StaleDaemonClassifierTest {

    private val installed = "/data/app/app.wheelstop.android-tz5JXIhWkVeDto4606tCmw==/base.apk"
    private val previous = "/data/app/app.wheelstop.android-7-WPN6b0Aq9xLmN2pQrS4tU==/base.apk"

    private fun environ(classpath: String) =
        "PATH=/system/bin CLASSPATH=$classpath ANDROID_DATA=/data "

    @Test
    fun extractsOurApkAndIgnoresTheFrameworkJar() {
        val cp = "/system/framework/bmmcamera.jar:$installed"
        assertEquals(installed, StaleDaemonClassifier.apkPathFromEnviron(environ(cp)))
    }

    @Test
    fun matchingPathIsCurrent() {
        val cp = "/system/framework/bmmcamera.jar:$installed"
        assertEquals(DaemonApkState.CURRENT,
            StaleDaemonClassifier.classify(environ(cp), installed))
    }

    @Test
    fun differentPathIsStale() {
        val cp = "/system/framework/bmmcamera.jar:$previous"
        assertEquals(DaemonApkState.STALE,
            StaleDaemonClassifier.classify(environ(cp), installed))
    }

    @Test
    fun unreadableEnvironIsUnknownNotStale() {
        assertEquals(DaemonApkState.UNKNOWN,
            StaleDaemonClassifier.classify(null, installed))
        assertEquals(DaemonApkState.UNKNOWN,
            StaleDaemonClassifier.classify("", installed))
    }

    @Test
    fun environWithoutADataAppEntryIsUnknownNotStale() {
        // Only the framework jar — nothing that identifies our build.
        val cp = "/system/framework/bmmcamera.jar"
        assertEquals(DaemonApkState.UNKNOWN,
            StaleDaemonClassifier.classify(environ(cp), installed))
        assertNull(StaleDaemonClassifier.apkPathFromEnviron(environ(cp)))
    }

    @Test
    fun environWithNoClasspathAtAllIsUnknown() {
        assertEquals(DaemonApkState.UNKNOWN,
            StaleDaemonClassifier.classify("PATH=/system/bin HOME=/ ", installed))
    }

    @Test
    fun aBlankExpectedPathIsUnknownRatherThanEverythingBeingStale() {
        val cp = "/system/framework/bmmcamera.jar:$installed"
        assertEquals(DaemonApkState.UNKNOWN,
            StaleDaemonClassifier.classify(environ(cp), ""))
    }

    @Test
    fun handlesNewlineSeparatedEnvironFromTrTranslation() {
        // The device read pipes environ through `tr '\0' '\n'`, so the classifier
        // must accept either separator.
        val text = "PATH=/system/bin\nCLASSPATH=/system/framework/bmmcamera.jar:$installed\n"
        assertEquals(DaemonApkState.CURRENT,
            StaleDaemonClassifier.classify(text, installed))
    }

    @Test
    fun picksTheDataAppEntryRegardlessOfPositionInClasspath() {
        val cp = "$installed:/system/framework/bmmcamera.jar"
        assertEquals(installed, StaleDaemonClassifier.apkPathFromEnviron(environ(cp)))
    }
}
