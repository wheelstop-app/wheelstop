package app.wheelstop.android.updater;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Staleness follows the APK. Five daemons load it through CLASSPATH and can go
 * stale; four tunnel binaries are native and cannot. The detector and the reset
 * must both act on exactly that set.
 *
 * <p>hardResetDaemons was written for the in-app updater, which runs it just
 * before the app restarts, so taking cloudflared/zrok/sing-box/tailscaled down
 * costs nothing there. The stale-daemon health check can fire at any time,
 * including on a parked car administered over Tailscale, and its cascade killed
 * the connection that triggered it with nothing to re-establish it.
 */
public class HardResetScopeContractTest {

    private static final String LIFECYCLE =
            "app/src/main/java/app/wheelstop/android/updater/UpdateLifecycle.java";
    private static final String STARTUP =
            "app/src/main/java/app/wheelstop/android/ui/daemon/DaemonStartupManager.kt";
    private static final String DETECTOR =
            "app/src/main/java/app/wheelstop/android/ui/daemon/StaleDaemonDetector.kt";

    @Test
    public void detectorCoversEveryApkBackedDaemon() throws IOException {
        String source = readRepositoryFile(DETECTOR);
        for (String name : new String[]{
                "byd_cam_daemon", "sentry_daemon", "acc_sentry_daemon",
                "sentry_proxy", "telegram_bot_daemon"}) {
            assertTrue(name + " loads the APK via CLASSPATH, so it can go stale and "
                            + "must be diagnosed",
                    source.contains("\"" + name + "\""));
        }
    }

    @Test
    public void detectorIgnoresTheNativeBinaries() throws IOException {
        String names = methodOrFieldRegion(readRepositoryFile(DETECTOR),
                "APK_BACKED_PROCESS_NAMES");
        for (String native_ : new String[]{"cloudflared", "zrok", "sing-box", "tailscaled"}) {
            assertTrue(native_ + " is a native binary that never loads the APK, so it "
                            + "cannot be stale and must not be diagnosed",
                    !names.contains(native_));
        }
    }

    @Test
    public void resetTakesAScopeParameter() throws IOException {
        assertTrue("hardResetDaemons needs a scope flag so the stale path can spare the tunnels",
                readRepositoryFile(LIFECYCLE)
                        .contains("hardResetDaemons(Context ctx, boolean includeNativeTunnels"));
    }

    @Test
    public void nativeTunnelKillsAreConditional() throws IOException {
        String source = readRepositoryFile(LIFECYCLE);
        assertTrue("the native tunnel kills must sit behind the scope flag",
                source.contains("includeNativeTunnels"));
        // The APK-backed work stays unconditional — a scoped reset that skipped it
        // would not fix staleness at all.
        for (String required : new String[]{
                "camera_daemon.disabled", "byd_cam_daemon", "sentry_daemon",
                "acc_sentry_daemon", "sentry_proxy", "telegram_bot_daemon",
                "start_telegram.sh"}) {
            assertTrue(required + " is APK-backed work and must run in both scopes",
                    source.contains(required));
        }
    }

    @Test
    public void theStaleDaemonPathSparesTheTunnels() throws IOException {
        assertTrue("the stale-daemon reset must request the APK-backed-only scope",
                readRepositoryFile(STARTUP).contains("hardResetDaemons(context, false"));
    }

    @Test
    public void theUpdaterPathIsUnchanged() throws IOException {
        assertTrue("existing callers keep the full cascade through the two-arg overload",
                readRepositoryFile(LIFECYCLE)
                        .contains("hardResetDaemons(Context ctx, Runnable onComplete)"));
    }

    /** Text from a declaration to the end of its line-continued initializer. */
    private static String methodOrFieldRegion(String source, String anchor) {
        int at = source.indexOf(anchor);
        if (at < 0) throw new AssertionError("Could not locate " + anchor);
        int close = source.indexOf(')', at);
        return source.substring(at, close > at ? close : Math.min(at + 400, source.length()));
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
