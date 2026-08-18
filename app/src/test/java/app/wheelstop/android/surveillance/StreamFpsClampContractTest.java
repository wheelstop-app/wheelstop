package app.wheelstop.android.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The dilink4 stream-fps clamp must have exactly ONE definition.
 *
 * <p>On the OEM SurfaceTexture HAL the stream encoder is deliberately capped
 * below the requested preset ({@code DILINK4_STREAM_FPS_CAP}), because that HAL
 * emits at its own fixed low rate. The cap is not a detail of encoder
 * construction: anything comparing a RUNNING encoder against a requested preset
 * must apply the same cap, or the encoder reads as permanently mismatched and
 * the comparison misfires on exactly the hardware the cap exists for.
 *
 * <p>An inline cap makes that mistake easy, because a second caller has no way
 * to ask what fps the pipeline would actually use and reaches for the raw preset
 * instead. These assertions keep
 * {@link GpuSurveillancePipeline#effectiveStreamFps(int)} the single definition.
 *
 * <p>Source inspection rather than invocation: the clamp reads live camera state
 * through a HAL that does not exist in a JVM test. Same approach as the sibling
 * {@code *ContractTest}s.
 */
public class StreamFpsClampContractTest {

    private static final String PIPELINE =
            "app/src/main/java/app/wheelstop/android/surveillance/GpuSurveillancePipeline.java";

    @Test
    public void clampLivesOnlyInEffectiveStreamFps() throws IOException {
        String source = readRepositoryFile(PIPELINE);

        String helper = methodBody(source, "public int effectiveStreamFps(");
        assertTrue("effectiveStreamFps must apply the dilink4 cap",
                helper.contains("DILINK4_STREAM_FPS_CAP"));

        String enableStreaming = methodBody(source, "private void enableStreamingInternal(");
        assertFalse("enableStreamingInternal must not re-inline the clamp",
                enableStreaming.contains("DILINK4_STREAM_FPS_CAP"));
        assertTrue("enableStreamingInternal must derive its encoder fps from effectiveStreamFps",
                enableStreaming.contains("effectiveStreamFps("));
    }

    @Test
    public void clampIsExposedForCallersComparingAgainstAPreset() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        // Must be reachable from outside the pipeline — the whole point is that
        // the reconnect path (a different class) can ask what fps the encoder
        // would really run at, instead of guessing from the preset.
        assertTrue("effectiveStreamFps must be public",
                source.contains("public int effectiveStreamFps(int requestedFps)"));
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
