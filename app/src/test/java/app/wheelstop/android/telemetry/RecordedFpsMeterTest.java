package app.wheelstop.android.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The meter reports what is IN THE FILE: HAL delivery divided by the recorder
 * stride. Values here are the ones measured on a BYD Seal — the panoramic HAL
 * saturates near 26 fps, and Proximity Guard's MONITORING state moves the stride
 * mid-recording.
 */
public class RecordedFpsMeterTest {

    /** Feed n ticks at a fixed HAL rate and stride, 500ms apart (the worker cadence). */
    private static RecordedFpsMeter fed(float halFps, int stride, int ticks) {
        RecordedFpsMeter m = new RecordedFpsMeter();
        long t = 10_000L;
        float count = 0f;
        for (int i = 0; i < ticks; i++) {
            m.sample((int) count, stride, t);
            count += halFps / 2f;   // 500ms per tick
            t += 500L;
        }
        return m;
    }

    @Test
    public void reportsHalRateWhenNothingIsBeingSkipped() {
        assertEquals(26.0f, fed(26f, 1, 8).recordedFps(), 0.5f);
    }

    @Test
    public void dividesByTheRecorderStride() {
        // Stride 3 means only every third frame reaches the encoder.
        assertEquals(26f / 3f, fed(26f, 3, 8).recordedFps(), 0.5f);
    }

    @Test
    public void strideOfZeroOrNegativeIsTreatedAsOne() {
        assertEquals(26.0f, fed(26f, 0, 8).recordedFps(), 0.5f);
        assertEquals(26.0f, fed(26f, -4, 8).recordedFps(), 0.5f);
    }

    @Test
    public void oneSampleIsNotEnoughToReportARate() {
        RecordedFpsMeter m = new RecordedFpsMeter();
        m.sample(100, 1, 10_000L);
        assertTrue("a single sample must not yield a number", Float.isNaN(m.recordedFps()));
    }

    @Test
    public void aWindowShorterThanASecondIsNotEnough() {
        RecordedFpsMeter m = new RecordedFpsMeter();
        m.sample(0, 1, 10_000L);
        m.sample(13, 1, 10_400L);   // 400ms apart
        assertTrue(Float.isNaN(m.recordedFps()));
    }

    @Test
    public void counterResetDiscardsTheWindowInsteadOfReportingNonsense() {
        RecordedFpsMeter m = fed(26f, 1, 8);
        assertTrue(m.recordedFps() > 0f);
        // The auto-probe path sets frameCounter back to 0 while cycling camera ids.
        // A negative delta must never render as a rate.
        m.sample(0, 1, 14_000L);
        assertTrue("a counter reset must discard the window", Float.isNaN(m.recordedFps()));
    }

    @Test
    public void recoversAfterAResetOnceTheWindowRefills() {
        RecordedFpsMeter m = fed(26f, 1, 8);
        m.sample(0, 1, 14_000L);
        long t = 14_500L;
        float count = 13f;
        for (int i = 0; i < 6; i++) {
            m.sample((int) count, 1, t);
            count += 13f;
            t += 500L;
        }
        assertEquals(26.0f, m.recordedFps(), 1.0f);
    }

    @Test
    public void usesMeasuredElapsedTimeNotAssumedTickCount() {
        // A starved worker ticks late. The rate must still be right, because the
        // divisor is measured wall clock rather than ticks x 500ms.
        RecordedFpsMeter m = new RecordedFpsMeter();
        m.sample(0, 1, 10_000L);
        m.sample(260, 1, 20_000L);   // 260 frames in 10s = 26fps
        assertEquals(26.0f, m.recordedFps(), 0.5f);
    }

    @Test
    public void ringDropsTheOldestSoTheWindowStaysBounded() {
        // 20 ticks through an 8-slot ring: the reported rate must reflect the
        // RECENT window, not the whole history.
        RecordedFpsMeter m = new RecordedFpsMeter();
        long t = 10_000L;
        int count = 0;
        for (int i = 0; i < 10; i++) { m.sample(count, 1, t); count += 5; t += 500L; }   // 10fps
        for (int i = 0; i < 10; i++) { m.sample(count, 1, t); count += 15; t += 500L; }  // 30fps
        assertEquals("must track the recent rate, not the lifetime average",
                30.0f, m.recordedFps(), 2.0f);
    }

    @Test
    public void resetClearsEverything() {
        RecordedFpsMeter m = fed(26f, 1, 8);
        m.reset();
        assertTrue(Float.isNaN(m.recordedFps()));
    }
}
