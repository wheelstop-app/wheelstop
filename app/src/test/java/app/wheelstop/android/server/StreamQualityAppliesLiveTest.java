package app.wheelstop.android.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The live-view quality picker must actually change the running stream.
 *
 * <p>It did not. {@code handleSetStreamQuality} persisted the preset and returned
 * without touching the pipeline, every other reader of {@code streamingQuality}
 * is guarded by {@code if (!pipeline.isStreamingEnabled())}, and the WebSocket
 * reconnect path compared only the encoder RESOLUTION — so an fps-only change
 * (ULTRA_HIGH, SMOOTH and MAX are all 1280x960 and differ only in fps) was
 * dropped even on reconnect. Measured on the car: the preset changed only after
 * a forced teardown. Picking a lower rate to fix a choppy live view did nothing.
 *
 * <p>These are source-inspection assertions because both call sites need a live
 * camera daemon, an encoder and a GL context — none of which exist in a JVM
 * test. Same approach as the sibling {@code *ContractTest}s in this package.
 */
public class StreamQualityAppliesLiveTest {

    private static final String HANDLER =
            "app/src/main/java/app/wheelstop/android/server/StreamingApiHandler.java";
    private static final String HTTP_SERVER =
            "app/src/main/java/app/wheelstop/android/server/HttpServer.java";
    private static final String PIPELINE =
            "app/src/main/java/app/wheelstop/android/surveillance/GpuSurveillancePipeline.java";

    @Test
    public void settingQualityRestartsAnAlreadyRunningStream() throws IOException {
        String method = methodBody(readRepositoryFile(HANDLER),
                "private static void handleSetStreamQuality");

        assertTrue("quality change must check whether a stream is currently up",
                method.contains("isStreamingEnabled()"));
        assertTrue("quality change must tear the running lane down",
                method.contains("disableStreaming()"));
        // enableStreaming (not a bare encoder swap) is what re-fires the pipeline's
        // streamStateListener → RecordingModeManager.reconcileCameraProfile, which
        // re-floors the shared camera HAL fps at the new stream rate. Without it the
        // camera stays pinned at the old rate and the new preset is cosmetic.
        assertTrue("quality change must bring the lane back up via enableStreaming",
                method.contains("enableStreaming("));
    }

    @Test
    public void reconnectComparesFrameRateNotJustResolution() throws IOException {
        String method = methodBody(readRepositoryFile(HTTP_SERVER),
                "private void streamH264ToWebSocket");

        assertTrue("reconnect must compare the encoder's fps, not only its resolution",
                method.contains("getFps()"));
        // The comparison must be against the EFFECTIVE fps the pipeline would use.
        // On the dilink4 HAL the encoder is deliberately clamped below the request
        // and re-clamped identically on every enable, so comparing the raw preset
        // would never match and would restart the lane on every single reconnect.
        assertTrue("fps comparison must go through effectiveStreamFps to survive the dilink4 clamp",
                method.contains("effectiveStreamFps("));
    }

    @Test
    public void pipelineQualitySetterDoesNotAlsoRestartTheLane() throws IOException {
        // Exactly one owner of the live restart. Two paths racing to disable and
        // re-enable the same lane is worse than the bug being fixed.
        String method = methodBody(readRepositoryFile(PIPELINE),
                "public void setStreamingQuality(");

        assertFalse("setStreamingQuality must stay config-only",
                method.contains("disableStreaming()"));
        assertFalse("setStreamingQuality must stay config-only",
                method.contains("enableStreaming("));
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
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
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
