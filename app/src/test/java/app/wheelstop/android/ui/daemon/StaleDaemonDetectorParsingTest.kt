package app.wheelstop.android.ui.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildEnvironCommand` and `parseEnvironOutput` are the pure halves of
 * [StaleDaemonDetector] — everything else in that class is I/O over a device
 * channel that does not exist in a JVM test. They are `internal` rather than
 * `private` specifically so they can be exercised here: a bug in the pid ↔
 * environ bookkeeping is exactly the kind of thing a device would only
 * surface at the worst possible moment. The bleed-through case below is the
 * one that matters most — an unreadable `/proc/<pid>/environ` picking up the
 * PREVIOUS pid's CLASSPATH would misclassify that daemon as CURRENT or STALE
 * off a completely different process's environment.
 */
class StaleDaemonDetectorParsingTest {

    // ---- buildEnvironCommand ----

    @Test
    fun emitsOneEchoAndCatPairPerPid() {
        val command = StaleDaemonDetector.buildEnvironCommand(listOf(1161, 1200))
        assertTrue(command.contains("${StaleDaemonDetector.PID_MARKER}1161"))
        assertTrue(command.contains("${StaleDaemonDetector.PID_MARKER}1200"))
        assertTrue(command.contains("/proc/1161/environ"))
        assertTrue(command.contains("/proc/1200/environ"))
        // One "cat" per pid — two pids means exactly two occurrences.
        assertEquals(2, command.split("cat ").size - 1)
    }

    @Test
    fun emptyPidListProducesAnEmptyCommand() {
        // A bare `cat` with no path would read stdin and hang the shell
        // session, so an empty pid list must produce nothing at all rather
        // than a `cat` invocation missing its argument.
        assertEquals("", StaleDaemonDetector.buildEnvironCommand(emptyList()))
    }

    // ---- parseEnvironOutput ----

    @Test
    fun splitsATwoPidBlockIntoTheRightMapping() {
        val output = """
            ${StaleDaemonDetector.PID_MARKER}1161
            CLASSPATH=/system/framework/bmmcamera.jar:/data/app/a==/base.apk
            ${StaleDaemonDetector.PID_MARKER}1200
            CLASSPATH=/system/framework/bmmcamera.jar:/data/app/b==/base.apk
        """.trimIndent()
        val result = StaleDaemonDetector.parseEnvironOutput(output)
        assertTrue(result.getValue(1161).contains("/data/app/a==/base.apk"))
        assertTrue(result.getValue(1200).contains("/data/app/b==/base.apk"))
    }

    @Test
    fun aPidWithNoOutputDoesNotInheritThePreviousPidsEnviron() {
        // /proc/<pid>/environ was unreadable for 1200, so its `cat` produced
        // nothing between its marker and the next one — the real UNKNOWN
        // case. If the parser failed to reset its buffer on the marker line,
        // 1200 would silently pick up 1161's CLASSPATH.
        val output = """
            ${StaleDaemonDetector.PID_MARKER}1161
            CLASSPATH=/system/framework/bmmcamera.jar:/data/app/a==/base.apk
            ${StaleDaemonDetector.PID_MARKER}1200
            ${StaleDaemonDetector.PID_MARKER}1201
            CLASSPATH=/system/framework/bmmcamera.jar:/data/app/c==/base.apk
        """.trimIndent()
        val result = StaleDaemonDetector.parseEnvironOutput(output)
        assertFalse(result.getValue(1200).contains("/data/app/a==/base.apk"))
        assertTrue(result.getValue(1200).isBlank())
    }

    @Test
    fun leadingNoiseBeforeTheFirstMarkerIsIgnored() {
        val output = "some shell banner\n" +
            "${StaleDaemonDetector.PID_MARKER}1161\n" +
            "CLASSPATH=/system/framework/bmmcamera.jar:/data/app/a==/base.apk\n"
        val result = StaleDaemonDetector.parseEnvironOutput(output)
        assertEquals(setOf(1161), result.keys)
        assertTrue(result.getValue(1161).contains("/data/app/a==/base.apk"))
    }

    @Test
    fun aMalformedMarkerDoesNotThrowOrAttributeLinesToAWrongPid() {
        val output = "${StaleDaemonDetector.PID_MARKER}notanumber\n" +
            "CLASSPATH=/system/framework/bmmcamera.jar:/data/app/a==/base.apk\n" +
            "${StaleDaemonDetector.PID_MARKER}1200\n" +
            "CLASSPATH=/system/framework/bmmcamera.jar:/data/app/b==/base.apk\n"
        val result = StaleDaemonDetector.parseEnvironOutput(output)
        assertEquals(setOf(1200), result.keys)
        assertTrue(result.getValue(1200).contains("/data/app/b==/base.apk"))
    }

    @Test
    fun roundTripsThroughTheClassifierForARealisticCapturedShape() {
        val installed = "/data/app/app.wheelstop.android-abc==/base.apk"
        val output = """
            ${StaleDaemonDetector.PID_MARKER}1161
            PATH=/system/bin
            CLASSPATH=/system/framework/bmmcamera.jar:$installed
            ${StaleDaemonDetector.PID_MARKER}1200
            CLASSPATH=/system/framework/bmmcamera.jar:$installed
        """.trimIndent()
        val result = StaleDaemonDetector.parseEnvironOutput(output)
        assertEquals(installed, StaleDaemonClassifier.apkPathFromEnviron(result.getValue(1161)))
        assertEquals(installed, StaleDaemonClassifier.apkPathFromEnviron(result.getValue(1200)))
    }
}
