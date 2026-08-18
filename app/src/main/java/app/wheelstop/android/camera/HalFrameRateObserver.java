package app.wheelstop.android.camera;

/**
 * Learns the highest frame rate the camera HAL will actually deliver, by
 * watching what it produces against what it was asked for.
 *
 * <p><b>Why this has to be learned rather than queried.</b> The AVM HAL exposes
 * {@code setCameraFps(int)} and nothing else — no {@code getSupportedFpsRange},
 * no parameter map, not even an fps getter. Worse, on the BYD panoramic HAL
 * {@code setCameraFps} returns {@code false} for every value while demonstrably
 * applying it (measured: request 15 → 15.9 delivered). So neither the API nor
 * its return value can tell us what the hardware can do; only measurement can.
 *
 * <p><b>Why it matters.</b> Declaring a frame rate the HAL cannot meet is not
 * cosmetic. The encoder budgets bits and paces rate control against the declared
 * rate, and a fixed-fps client decoder (JMuxer) paces playback against it — so
 * declaring 30 while receiving 26 makes the decoder run ~13% fast and then
 * starve, which reads as judder regardless of how stable the capture is.
 *
 * <p><b>The rule, and why it is shaped this way.</b> A ceiling is recorded ONLY
 * from evidence of saturation: a window where the HAL was asked for materially
 * more than it delivered. Simply taking "the highest rate seen" would be wrong
 * in both directions — this camera idles at 1 fps when no consumer wants frames,
 * which would teach a ceiling of 1; and a car that was only ever asked for 15
 * and duly delivered 15 would be capped at 15 forever, never allowed to reach
 * the 30 or 60 it is capable of. Saturation evidence avoids both: no evidence
 * means no ceiling means no clamp, so a faster HAL is never held back by this
 * class. It only ever describes hardware we have watched fall short.
 *
 * <p>Thread-safe: a single volatile int, written from the camera's stats window
 * and read by the stream-enable path.
 */
public final class HalFrameRateObserver {

    /**
     * How far below the request delivery must fall before it counts as the HAL
     * saturating rather than ordinary jitter. Measured delivery overshoots
     * slightly on this HAL (request 15 → 15.9, request 25 → 26.3), so the
     * comparison must tolerate being a little OVER the request too — only a
     * genuine shortfall is evidence of a ceiling.
     */
    private static final float SATURATION_TOLERANCE_FPS = 2.0f;

    /**
     * Shortest window that can produce a trustworthy rate. Anything briefer is
     * dominated by HAL warm-up and encoder start-up transients, which would
     * teach an artificially low ceiling and then clamp every later stream to it.
     */
    private static final long MIN_WINDOW_MS = 5_000L;

    /** Below this, a "delivered" rate is a stall or a throttled idle tier, not a ceiling. */
    private static final float MIN_CREDIBLE_FPS = 5.0f;

    /** 0 = not yet known. Never decreases (see {@link #observe}). */
    private volatile int ceilingFps = 0;

    /**
     * Feed one measurement window.
     *
     * @param requestedFps  what the camera was asked for over the window
     * @param deliveredFps  what it actually produced
     * @param windowMs      wall-clock length of the window
     */
    public void observe(int requestedFps, float deliveredFps, long windowMs) {
        if (windowMs < MIN_WINDOW_MS) return;
        if (requestedFps <= 0) return;
        if (deliveredFps < MIN_CREDIBLE_FPS) return;
        // Not saturating: the HAL met (or beat) the request, so this window says
        // nothing about an upper bound. Recording it would cap the device at
        // whatever it merely happened to be asked for.
        if (deliveredFps >= requestedFps - SATURATION_TOLERANCE_FPS) return;

        int observed = Math.round(deliveredFps);
        // Monotonic: keep the HIGHEST saturating delivery ever seen. A later
        // window under extra load can deliver less without that being a lower
        // hardware ceiling, and ratcheting down on it would slowly starve the
        // declared rate toward the worst moment the device ever had.
        if (observed > ceilingFps) {
            ceilingFps = observed;
        }
    }

    /** @return the learned ceiling, or 0 when the HAL has never been seen to fall short. */
    public int getCeilingFps() {
        return ceilingFps;
    }

    /**
     * Seed from persisted state so the very first stream after a restart is
     * already paced correctly. Ignores non-positive and implausible values so a
     * corrupt config cannot clamp the stream to a crawl.
     */
    public void seed(int persistedCeilingFps) {
        if (persistedCeilingFps >= MIN_CREDIBLE_FPS && persistedCeilingFps > ceilingFps) {
            ceilingFps = persistedCeilingFps;
        }
    }

    /**
     * Apply the learned ceiling to a requested rate.
     *
     * @return {@code requestedFps} when no ceiling is known or the request is
     *         already within it; otherwise the ceiling.
     */
    public int clamp(int requestedFps) {
        int c = ceilingFps;
        if (c <= 0 || requestedFps <= c) return requestedFps;
        return c;
    }
}
