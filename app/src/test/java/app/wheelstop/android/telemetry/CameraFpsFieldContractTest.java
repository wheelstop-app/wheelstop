package app.wheelstop.android.telemetry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The overlay's chip grid builds itself from the enum, so a new field reaches the
 * UI on its own — but its LABEL does not. The fallback label map is duplicated in
 * recording.js and surveillance.js, and updating only one leaves the surveillance
 * flow rendering the raw wire key `cameraFps`. That asymmetry is the whole reason
 * this test exists.
 */
public class CameraFpsFieldContractTest {

    @Test
    public void fieldExistsAndDefaultsOff() {
        TelemetryFields.Field f = TelemetryFields.Field.fromKey("cameraFps");
        assertTrue("cameraFps must be a known field", f != null);
        assertFalse("a new field must default OFF so an update never changes an "
                + "existing overlay", f.isLegacyDefault());
        assertFalse("legacyDefault() must not include it",
                TelemetryFields.legacyDefault().has(f));
    }

    @Test
    public void bothDuplicatedLabelMapsCarryTheField() throws IOException {
        for (String asset : new String[]{
                "app/src/main/assets/web/shared/recording.js",
                "app/src/main/assets/web/shared/surveillance.js"}) {
            assertTrue(asset + " must label cameraFps, or its chip renders as the raw key",
                    readRepositoryFile(asset).contains("cameraFps:"));
        }
    }

    @Test
    public void englishLocaleCarriesTheLabel() throws IOException {
        assertTrue(readRepositoryFile("app/src/main/assets/web/i18n/en.json")
                .contains("telemetry_field_cameraFps"));
    }

    @Test
    public void liveViewRendersTheMeasuredRateTheApiAlreadyReturns() throws IOException {
        String stream = readRepositoryFile("app/src/main/assets/web/shared/stream.js");
        assertTrue("stream.js must consume cameraFpsActual — the API has returned it "
                + "unrendered since the field was added",
                stream.contains("cameraFpsActual"));
        assertTrue("stream.js must surface cameraFpsClampNote, which is the "
                + "'asked 30, got 26' explanation",
                stream.contains("cameraFpsClampNote"));
        String page = readRepositoryFile("app/src/main/assets/web/local/live-view.html");
        assertTrue("live-view.html needs the element the readout writes into",
                page.contains("cameraFpsReadout"));
        // The clamp note explains WHY a preset under-delivers, so it has to be
        // on screen. A title attribute would be unreachable here — this runs on
        // a head-unit touchscreen with no hover.
        assertTrue("the clamp note needs a visible element, not a tooltip",
                page.contains("cameraFpsNote"));
        assertFalse("the clamp note must not be hidden in a title attribute",
                stream.contains(".title = data.cameraFpsClampNote"));
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
