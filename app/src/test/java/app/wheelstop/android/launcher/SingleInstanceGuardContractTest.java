package app.wheelstop.android.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * All three core daemons must refuse to launch a second copy of themselves.
 *
 * CAMERA_DAEMON already did; SENTRY_DAEMON and ACC_SENTRY_DAEMON did not, and a
 * double app launch produced two sentry_daemon processes under two separate
 * watchdogs — two daemons contending for the same camera and sentinel files.
 *
 * Source inspection because launching needs a device, an adb channel and a real
 * process table. Same approach as the sibling *ContractTests.
 */
public class SingleInstanceGuardContractTest {

    private static final String LAUNCHER =
            "app/src/main/java/app/wheelstop/android/launcher/DaemonLauncher.kt";

    @Test
    public void everyCoreDaemonLaunchIsGuardedByIsDaemonRunning() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        for (String process : new String[]{
                "CAMERA_DAEMON_PROCESS", "SENTRY_DAEMON_PROCESS", "ACC_SENTRY_DAEMON_PROCESS"}) {
            assertTrue(process + " must be guarded by isDaemonRunning before launch",
                    source.contains("isDaemonRunning(" + process + ")"));
        }
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
