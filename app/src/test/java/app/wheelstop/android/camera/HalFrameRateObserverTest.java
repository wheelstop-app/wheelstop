package app.wheelstop.android.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Behavioural tests for the learned frame-rate ceiling. The values here are the
 * ones actually measured on a BYD Seal (DiLink): the panoramic HAL saturates at
 * ~25.9 fps and overshoots slightly on low requests (15 → 15.92).
 */
public class HalFrameRateObserverTest {

    private static final long WINDOW = 30_000L;

    @Test
    public void unknownUntilTheHalIsSeenToFallShort() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        assertEquals(0, o.getCeilingFps());
        assertEquals("no evidence must never clamp", 30, o.clamp(30));
    }

    @Test
    public void learnsTheCeilingWhenDeliveryFallsShortOfTheRequest() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        o.observe(30, 25.92f, WINDOW);
        assertEquals(26, o.getCeilingFps());
        assertEquals("a request above the ceiling is clamped to it", 26, o.clamp(30));
        assertEquals("a request within the ceiling is untouched", 15, o.clamp(15));
    }

    @Test
    public void meetingTheRequestTeachesNoCeiling() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        // Measured: request 15 delivers 15.92 — the HAL met it, so this window
        // says nothing about an upper bound. Recording it would cap the device
        // at 15 and it could never reach 30.
        o.observe(15, 15.92f, WINDOW);
        assertEquals(0, o.getCeilingFps());
        assertEquals(30, o.clamp(30));
    }

    @Test
    public void aFasterHalIsNeverHeldBack() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        // A future vehicle that delivers everything asked of it, including 60.
        o.observe(30, 30.1f, WINDOW);
        o.observe(60, 59.8f, WINDOW);
        assertEquals("no saturation was ever seen", 0, o.getCeilingFps());
        assertEquals("60fps hardware must not be clamped", 60, o.clamp(60));
    }

    @Test
    public void ceilingNeverRatchetsDownOnALoadedWindow() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        o.observe(30, 25.9f, WINDOW);
        // A later window under heavier load delivers less. That is not a lower
        // hardware ceiling, and tracking it down would starve the declared rate
        // toward the worst moment the device ever had.
        o.observe(30, 18.0f, WINDOW);
        assertEquals(26, o.getCeilingFps());
    }

    @Test
    public void idleThrottleIsNotMistakenForACeiling() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        // The parked idle rung drives the camera to 1 fps request / ~2 delivered.
        // Learning that as a ceiling would clamp every later stream to a crawl.
        o.observe(30, 2.2f, WINDOW);
        assertEquals(0, o.getCeilingFps());
        assertEquals(30, o.clamp(30));
    }

    @Test
    public void shortWindowsAreIgnored() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        // Start-up transients dominate a brief window; trusting one would teach
        // an artificially low ceiling that then clamps everything afterwards.
        o.observe(30, 9.0f, 1_000L);
        assertEquals(0, o.getCeilingFps());
    }

    @Test
    public void seedRestoresAPersistedCeilingButRejectsNonsense() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        o.seed(26);
        assertEquals(26, o.getCeilingFps());
        assertEquals(26, o.clamp(30));

        o.seed(1);      // corrupt/implausible
        o.seed(0);
        o.seed(-5);
        assertEquals("a bad persisted value must not lower the ceiling", 26, o.getCeilingFps());

        o.seed(60);     // a faster device
        assertEquals(60, o.getCeilingFps());
    }

    @Test
    public void toleranceAbsorbsOvershootWithoutLearningACeiling() {
        HalFrameRateObserver o = new HalFrameRateObserver();
        // Measured: request 25 delivers 26.3 — over, not short.
        o.observe(25, 26.3f, WINDOW);
        assertEquals(0, o.getCeilingFps());
        // And a delivery a hair under the request is jitter, not saturation.
        o.observe(30, 29.0f, WINDOW);
        assertEquals(0, o.getCeilingFps());
    }
}
