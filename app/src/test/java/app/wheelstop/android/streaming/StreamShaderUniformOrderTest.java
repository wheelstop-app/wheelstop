package app.wheelstop.android.streaming;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL requires a uniform to be declared before it is referenced. Nothing in the
 * Java build catches a violation: the shader is assembled as a string and only
 * fails at runtime, on the device, when the stream is first opened — and because
 * this is ONE shared fragment program, a single out-of-order uniform takes the
 * whole streaming path down with "Failed to create shader program".
 *
 * <p>That is not hypothetical. {@code uBsContrast}/{@code uBsSharpen}/{@code uBsRadius}
 * were added to the blind-spot uniform block, which sits AFTER {@code odBlend()} —
 * the function that uses them. On an Adreno 610 the compiler reported
 * {@code ERROR: 0:95: 'uBsSharpen' : undeclared identifier} and every attempt to
 * open the video stream failed, while surveillance and recording (which use
 * different programs) kept working, so it looked like a streaming-only fault.
 *
 * <p>This reads the source as text rather than invoking the builder, because
 * {@code buildFragmentShader} is private and its output depends on GL state that
 * does not exist in a JVM test.
 */
public class StreamShaderUniformOrderTest {

    /** {@code "uniform <type> <name>;\n"} inside the shader string. */
    private static final Pattern DECL = Pattern.compile(
            "\"uniform\\s+\\w+\\s+(u[A-Za-z0-9_]*)\\s*;");

    /** A GLSL line inside the shader: a string literal ending in a newline escape. */
    private static final Pattern SHADER_LINE = Pattern.compile("\"[^\"]*\\\\n\"");

    /** Any {@code uXxx} token appearing inside a shader string literal. */
    private static final Pattern USE = Pattern.compile("\\b(u[A-Z][A-Za-z0-9_]*)\\b");

    @Test
    public void everyShaderUniformIsDeclaredBeforeItIsUsed() throws IOException {
        List<String> lines = Files.readAllLines(
                repositoryPath("app/src/main/java/app/wheelstop/android/streaming/GpuStreamScaler.java"),
                StandardCharsets.UTF_8);

        Map<String, Integer> declaredAt = new LinkedHashMap<>();
        Map<String, Integer> firstUseAt = new LinkedHashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // Only GLSL source lines count. They are string literals being concatenated
            // into the shader and so end with a newline escape: `"...\n" +`. This
            // deliberately excludes Java lines such as
            //   glGetUniformLocation(programId, "uCameraTex")
            // which mention a uniform's NAME but are not shader source — treating those
            // as "uses" reports every uniform in the file as out of order.
            if (!SHADER_LINE.matcher(line).find()) continue;

            Matcher d = DECL.matcher(line);
            while (d.find()) declaredAt.putIfAbsent(d.group(1), i + 1);

            Matcher u = USE.matcher(line);
            while (u.find()) firstUseAt.putIfAbsent(u.group(1), i + 1);
        }

        assertTrue("expected the shader to declare some uniforms", declaredAt.size() > 5);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> e : declaredAt.entrySet()) {
            String name = e.getKey();
            int decl = e.getValue();
            Integer use = firstUseAt.get(name);
            if (use != null && use < decl) {
                violations.add(name + ": first used at line " + use + " but declared at line " + decl);
            }
        }

        if (!violations.isEmpty()) {
            fail("GLSL requires declaration before use; these would fail to compile on device "
                    + "and break the whole stream path:\n  " + String.join("\n  ", violations));
        }
    }

    /** Walk up until the given repo-relative path resolves — mirrors the sibling asset tests. */
    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.exists(direct)) return direct;
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.exists(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository path: " + relativePath);
    }
}
