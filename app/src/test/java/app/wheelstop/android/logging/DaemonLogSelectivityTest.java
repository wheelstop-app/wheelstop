package app.wheelstop.android.logging;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Turning a tag on must not turn every tag on.
 *
 * <p>Two switches are coupled here. R8 strips every log call unless at least one
 * flag in {@code DaemonLogConfig} is true, because build.gradle.kts greps this
 * file to decide whether to apply {@code proguard-rules-strip-logs.pro}. And
 * {@code BuildConfig.LOG_CAPTURE} is true in BOTH build types. So before this
 * guard, enabling a single tag purely to keep the daemon-lifecycle logs through
 * R8 also enabled file logging for every other tag, including the GL and
 * telemetry ones that produced megabytes per hour on the vehicle.
 *
 * <p>Source inspection because {@code isFileLoggingEnabled} reads
 * {@code BuildConfig}, which differs per build type and is not meaningfully
 * settable from a JVM test.
 */
public class DaemonLogSelectivityTest {

    private static final String CONFIG =
            "app/src/main/java/app/wheelstop/android/logging/DaemonLogConfig.java";
    private static final String DAEMON =
            "app/src/main/java/app/wheelstop/android/daemon/CameraDaemon.java";

    @Test
    public void perTagSelectionWinsOverTheCaptureAllFlag() throws IOException {
        String method = methodBody(readRepositoryFile(CONFIG),
                "public static boolean isFileLoggingEnabled");
        int selective = method.indexOf("ENABLED_TAGS.isEmpty()");
        int captureAll = method.indexOf("BuildConfig.LOG_CAPTURE");
        assertTrue("the selective branch must exist", selective >= 0);
        assertTrue("LOG_CAPTURE must be the fallback, not the first word — otherwise "
                        + "selecting one tag writes every tag",
                captureAll > selective);
    }

    @Test
    public void theCoreDaemonTagsAreOnSoAShippingBuildIsDiagnosable() throws IOException {
        String source = readRepositoryFile(CONFIG);
        for (String flag : new String[]{
                "CAMERA_DAEMON", "SENTRY_DAEMON", "ACC_SENTRY_DAEMON"}) {
            assertTrue(flag + " must be on: these are the daemons that can run a deleted "
                            + "APK, and the reset that fixes them is otherwise silent",
                    source.contains("boolean " + flag + " = true;"));
        }
    }

    @Test
    public void theNoisyTagsStayOff() throws IOException {
        String source = readRepositoryFile(CONFIG);
        // These produced the megabytes-per-hour that made "log everything"
        // unaffordable on a head unit.
        for (String flag : new String[]{
                "GPU_PIPELINE", "PANORAMIC_CAMERA", "BYD_TELEMETRY", "ENABLE_ALL"}) {
            assertFalse(flag + " must stay off in a shipping build",
                    source.contains("boolean " + flag + " = true;"));
        }
    }

    @Test
    public void daemonLogsAreSizeCapped() throws IOException {
        String source = readRepositoryFile(DAEMON);
        assertTrue("daemon logs need an explicit size cap now that they ship enabled",
                source.contains("withMaxFileSizeMB("));
        assertTrue("and a rotation count, or a cap alone still grows without bound",
                source.contains("withRotationCount("));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Could not locate " + signature);
        int openingBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openingBrace, i + 1);
            }
        }
        throw new AssertionError("Unbalanced method body for " + signature);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
