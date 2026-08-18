package app.wheelstop.android.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Whether tailscaled routes through the sing-box proxy must be decided by
 * probing the proxy's PORT, never by looking for a process.
 *
 * <p>The original check ran {@code pgrep -f sing-box}. {@code -f} matches the
 * whole command line and the check itself executes as
 * {@code sh -c "pgrep -f sing-box"}, so the probing shell matched itself and the
 * function returned true unconditionally. Verified on the car with sing-box
 * definitively absent: {@code pgrep -f sing-box} returned the pid of
 * {@code /system/bin/sh -c ...sing-box...}.
 *
 * <p>That is not a cosmetic bug. tailscaled was then always launched with
 * {@code ALL_PROXY=socks5://127.0.0.1:8119}; with nothing listening there every
 * dial failed instantly, the tunnel hung on "starting" forever, and the vehicle
 * could not be brought back online remotely after a daemon reset killed
 * sing-box.
 *
 * <p>Source inspection because the real check opens a socket against a proxy
 * that does not exist in a JVM test. Same approach as the sibling
 * {@code *ContractTest}s.
 */
public class SingboxProxyReadinessContractTest {

    private static final String LAUNCHER =
            "app/src/main/java/app/wheelstop/android/launcher/TailscaleLauncher.kt";

    @Test
    public void singboxCheckProbesThePortRatherThanGrepingForAProcess() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        String method = methodBody(source, "private fun isSingboxActive");

        assertFalse("`pgrep -f sing-box` matches the shell running it, so this check "
                        + "would report the proxy up when it is absent",
                method.contains("pgrep"));
        assertTrue("the decision must be a readiness probe of the proxy port",
                method.contains("PROXY_PORT"));
        assertTrue("a readiness probe means actually connecting to it",
                method.contains("connect("));
    }

    @Test
    public void theProbeIsBounded() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        // An unbounded connect would stall the tunnel launch on a wedged proxy —
        // the failure mode this check exists to avoid, one layer along.
        assertTrue("the probe must carry a connect timeout",
                methodBody(source, "private fun isSingboxActive")
                        .contains("SINGBOX_PROBE_TIMEOUT_MS"));
        assertTrue("and that timeout must be defined",
                source.contains("SINGBOX_PROBE_TIMEOUT_MS ="));
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
