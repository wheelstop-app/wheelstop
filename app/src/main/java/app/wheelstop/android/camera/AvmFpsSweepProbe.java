package app.wheelstop.android.camera;

import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import app.wheelstop.android.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * DIAGNOSTIC ONLY — not part of any shipping flow.
 *
 * <p>Sweeps requested capture rates against the AVM HAL and reports what it
 * actually delivers, with no GL work, no encoding and no downstream consumer:
 * images are acquired and closed immediately so the measurement is what the HAL
 * pushes, not what a pipeline can absorb.
 *
 * <p>Answers two questions the live pipeline cannot:
 *
 * <ol>
 *   <li><b>Is ~26 fps the HAL's ceiling, or just our request?</b> The live path
 *       clamps every request to 30 ({@code Math.min(30, fps)}). If delivery
 *       tracks the request but saturates below it, asking for 45 or 60 may land
 *       30. Note {@code setCameraFps} returns {@code false} for every value on
 *       this HAL yet the rate demonstrably follows it, so the return is
 *       ignored here.</li>
 *   <li><b>Does a single quadrant run faster than the mosaic?</b> Surface mode 0
 *       is the stitched 5120x960 strip; modes 1-4 are individual 1280x960
 *       cameras. A quarter of the pixels may clear a bandwidth-bound ceiling.</li>
 * </ol>
 *
 * <p>Driven by {@code /data/local/tmp/run_fps_sweep}. Each non-blank, non-{@code #}
 * line is one run: {@code camId surfaceMode width height fpsRequest seconds}.
 * An empty sentinel runs {@link #defaultPlan()}. Parameterised so the plan can
 * change without rebuilding and re-pushing an 85 MB APK.
 *
 * <p>Never throws — one failed run must not abort the rest of the sweep.
 */
public final class AvmFpsSweepProbe {

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("AvmFpsSweepProbe");

    private static final long FIRST_FRAME_TIMEOUT_MS = 6_000L;

    private final File sentinel;

    public AvmFpsSweepProbe(File sentinel) {
        this.sentinel = sentinel;
    }

    private static final class Run {
        final int camId, surfaceMode, width, height, fps, seconds;
        Run(int camId, int surfaceMode, int width, int height, int fps, int seconds) {
            this.camId = camId; this.surfaceMode = surfaceMode;
            this.width = width; this.height = height;
            this.fps = fps; this.seconds = seconds;
        }
        @Override public String toString() {
            return "cam=" + camId + " mode=" + surfaceMode + " " + width + "x" + height
                    + " req=" + fps + "fps for " + seconds + "s";
        }
    }

    /**
     * Mosaic at rising request rates to find the saturation point, then the same
     * camera as a single quadrant to test whether the ceiling is pixel-bound.
     */
    private static List<Run> defaultPlan() {
        List<Run> plan = new ArrayList<>();
        for (int fps : new int[]{15, 30, 45, 60}) {
            plan.add(new Run(0, 0, 5120, 960, fps, 12));
        }
        plan.add(new Run(0, 1, 1280, 960, 60, 12));
        return plan;
    }

    private List<Run> readPlan() {
        List<Run> plan = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(sentinel))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                if (p.length < 6) continue;
                plan.add(new Run(
                        Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5])));
            }
        } catch (Throwable t) {
            logger.warn("sentinel parse failed (" + t.getMessage() + ") — using default plan");
        }
        return plan.isEmpty() ? defaultPlan() : plan;
    }

    public void run() {
        logger.info("=== AVM FPS SWEEP START ===");
        List<Run> plan = readPlan();
        logger.info("plan has " + plan.size() + " run(s)");
        for (Run run : plan) {
            try {
                measure(run);
            } catch (Throwable t) {
                logger.error("run failed (" + run + "): "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            // Let the HAL settle between opens; back-to-back open/close of the
            // AVM camera is not something the OEM stack is exercised on.
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
        logger.info("=== AVM FPS SWEEP COMPLETE ===");
    }

    private static final class Counter implements ImageReader.OnImageAvailableListener {
        volatile int frames = 0;
        volatile long firstMs = 0;
        @Override public void onImageAvailable(ImageReader reader) {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img != null) {
                    if (frames == 0) firstMs = System.currentTimeMillis();
                    frames++;
                }
            } catch (Throwable ignored) {
            } finally {
                if (img != null) {
                    try { img.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void measure(Run run) throws Exception {
        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
        HandlerThread cbThread = new HandlerThread("AvmFpsSweepCb");
        cbThread.start();
        Handler cbHandler = new Handler(cbThread.getLooper());

        Object cam = null;
        ImageReader reader = null;
        Counter counter = new Counter();
        String setFpsResult = "n/a";
        try {
            Constructor<?> ctor = avmClass.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            cam = ctor.newInstance(run.camId);

            Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            Object opened = mOpen.invoke(cam);
            if (!(opened instanceof Boolean) || !(Boolean) opened) {
                logger.warn("RESULT " + run + " → open returned " + opened + ", skipped");
                return;
            }

            // Rate must be requested BEFORE a consumer is attached: on DiLink 3.x
            // the HAL rejects setCameraFps once a consumer is bound. The live
            // pipeline orders it the same way.
            try {
                Method mFps = avmClass.getDeclaredMethod("setCameraFps", int.class);
                mFps.setAccessible(true);
                setFpsResult = String.valueOf(mFps.invoke(cam, run.fps));
            } catch (Throwable t) {
                setFpsResult = "threw:" + t.getClass().getSimpleName();
            }

            try {
                reader = ImageReader.newInstance(run.width, run.height,
                        ImageFormat.PRIVATE, 6, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            } catch (Throwable t) {
                reader = ImageReader.newInstance(run.width, run.height,
                        ImageFormat.YUV_420_888, 6);
            }
            reader.setOnImageAvailableListener(counter, cbHandler);

            Method mAdd = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            mAdd.setAccessible(true);
            Object added = mAdd.invoke(cam, reader.getSurface(), run.surfaceMode);

            Method mStart = avmClass.getDeclaredMethod("startPreview");
            mStart.setAccessible(true);
            Object started = mStart.invoke(cam);

            long deadline = System.currentTimeMillis() + FIRST_FRAME_TIMEOUT_MS;
            while (counter.frames == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (counter.frames == 0) {
                logger.warn("RESULT " + run + " → NO FRAMES"
                        + " (setCameraFps=" + setFpsResult + " addSurface=" + added
                        + " startPreview=" + started + ")");
                return;
            }

            // Count only frames after the first, measured from the first frame's
            // arrival — excludes HAL warm-up from the rate.
            int startFrames = counter.frames;
            long startMs = System.currentTimeMillis();
            Thread.sleep(run.seconds * 1000L);
            int delta = counter.frames - startFrames;
            long elapsed = Math.max(1L, System.currentTimeMillis() - startMs);
            float fps = delta * 1000f / elapsed;

            logger.info(String.format(
                    "RESULT %s → achieved=%.2f fps (%d frames / %.1fs)"
                            + " setCameraFps=%s addSurface=%s startPreview=%s",
                    run, fps, delta, elapsed / 1000f, setFpsResult, added, started));
        } finally {
            try {
                if (cam != null) {
                    Method mStop = avmClass.getDeclaredMethod("stopPreview");
                    mStop.setAccessible(true);
                    mStop.invoke(cam);
                }
            } catch (Throwable ignored) {}
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {}
            try {
                if (cam != null) {
                    Method mClose = avmClass.getDeclaredMethod("close");
                    mClose.setAccessible(true);
                    mClose.invoke(cam);
                }
            } catch (Throwable ignored) {}
            cbThread.quitSafely();
        }
    }
}
