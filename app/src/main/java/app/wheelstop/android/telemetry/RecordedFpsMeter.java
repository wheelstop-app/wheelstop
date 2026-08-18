package app.wheelstop.android.telemetry;

/**
 * Derives the rate that is actually being RECORDED, from counters the camera
 * already keeps: HAL frame delivery divided by the recorder lane's draw stride.
 *
 * <p><b>Why not an existing number:</b> {@code getMeasuredFps()} is computed on a
 * 2-minute window, so it reads 0 for the first two minutes of a recording and
 * then moves in 2-minute steps — useless burned into a frame. {@code
 * HalFrameRateObserver} (a different class, added for the stream encoder) learns
 * a saturation CEILING rather than a live rate. Neither describes the file being
 * written right now.
 *
 * <p><b>Why the stride matters:</b> the recorder lane sub-samples — Proximity
 * Guard's MONITORING state and the blind-spot keep-warm path both move
 * {@code recorderFrameStride} mid-recording. A stamp showing raw HAL delivery
 * would read 26 on a file that actually contains ~8.7 fps.
 *
 * <p>Pure and allocation-free after construction: an 8-slot ring sampled at the
 * overlay worker's 2 Hz gives a ~4s window — long enough to be steady, short
 * enough to follow a stride change within a few seconds. Nothing here runs on
 * the GL thread.
 *
 * <p>Not thread-safe; it is confined to the overlay worker thread.
 */
public final class RecordedFpsMeter {

    /** 8 slots x 500ms worker cadence = a ~4s window. */
    private static final int SLOTS = 8;

    /** Below this the rate is dominated by sampling jitter rather than signal. */
    private static final long MIN_SPAN_MS = 1_000L;

    private final int[] frameCounts = new int[SLOTS];
    private final long[] stamps = new long[SLOTS];
    private int stride = 1;
    private int size = 0;
    private int head = 0;

    /**
     * Record one observation.
     *
     * @param frameCount     the camera's cumulative delivered-frame counter
     * @param recorderStride draw stride; values below 1 mean "every frame"
     * @param elapsedMs      a monotonic clock reading (SystemClock.elapsedRealtime)
     */
    public void sample(int frameCount, int recorderStride, long elapsedMs) {
        // The auto-probe path sets frameCounter back to 0 while it cycles camera
        // ids, and the overlay worker can be running through that. A delta across
        // the reset is negative and would render as a nonsense rate, so drop the
        // window and start again. This also covers int wraparound for free.
        if (size > 0 && frameCount < newestCount()) {
            reset();
        }
        this.stride = Math.max(1, recorderStride);
        frameCounts[head] = frameCount;
        stamps[head] = elapsedMs;
        head = (head + 1) % SLOTS;
        if (size < SLOTS) size++;
    }

    /** @return recorded frames per second, or {@code NaN} while the window is too thin. */
    public float recordedFps() {
        if (size < 2) return Float.NaN;
        int newest = (head - 1 + SLOTS) % SLOTS;
        int oldest = (head - size + SLOTS) % SLOTS;
        long spanMs = stamps[newest] - stamps[oldest];
        if (spanMs < MIN_SPAN_MS) return Float.NaN;
        int delta = frameCounts[newest] - frameCounts[oldest];
        if (delta < 0) return Float.NaN;
        float halFps = (delta * 1000f) / spanMs;
        return halFps / stride;
    }

    /** Drop the window. Used on a counter reset and when the field is switched off. */
    public void reset() {
        size = 0;
        head = 0;
    }

    private int newestCount() {
        return frameCounts[(head - 1 + SLOTS) % SLOTS];
    }
}
