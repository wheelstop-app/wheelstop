package app.wheelstop.android.communication;

import app.wheelstop.android.byd.BydDataCollector;
import app.wheelstop.android.byd.BydVehicleData;
import app.wheelstop.android.config.UnifiedConfigManager;
import app.wheelstop.android.daemon.CameraDaemon;
import app.wheelstop.android.monitor.AccMonitor;
import app.wheelstop.android.monitor.GearMonitor;
import app.wheelstop.android.surveillance.GpuSurveillancePipeline;

import org.json.JSONObject;

/** Motion and overlay-surface gates used by remote communication. */
public final class VehicleCommunicationSafety {

    private static final long ROAD_SENSE_STATE_FRESH_MS = 5_000L;

    private VehicleCommunicationSafety() {}

    /** True only after the daemon has received an authoritative ACC reading. */
    public static boolean isCarPowerStateKnown() {
        try {
            return AccMonitor.isAccStateAuthoritative();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Unknown is deliberately not treated as off during daemon startup. */
    public static boolean isCarKnownOff() {
        try {
            return AccMonitor.isAccStateAuthoritative() && !AccMonitor.isAccOn();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Dialogs are permitted only with a positive, current Park signal. */
    public static boolean isParked() {
        Integer gear = readGear();
        return gear != null && gear == GearMonitor.GEAR_P;
    }

    /**
     * The compact voice overlay yields to reverse, blind-spot, and active
     * RoadSense hazard/confirmation surfaces. Audio continues underneath.
     */
    public static boolean isRemoteVoiceOverlaySafe() {
        Integer gear = readGear();
        if (gear != null && gear == GearMonitor.GEAR_R) return false;

        try {
            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.isBlindSpotCardShowing()) return false;
        } catch (Throwable ignored) {}

        try {
            JSONObject state = UnifiedConfigManager.loadConfig()
                    .optJSONObject("roadSense");
            state = state == null ? null : state.optJSONObject("overlayState");
            if (state != null) {
                long age = System.currentTimeMillis() - state.optLong("ts", 0L);
                if (age >= 0L && age <= ROAD_SENSE_STATE_FRESH_MS
                        && (state.optBoolean("ahead", false)
                        || state.optJSONObject("pending") != null)) {
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private static Integer readGear() {
        try {
            GearMonitor monitor = GearMonitor.getInstance();
            if (monitor != null && monitor.isRunning()) {
                return monitor.getCurrentGear();
            }
        } catch (Throwable ignored) {}
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData data = collector == null ? null : collector.getData();
            if (data != null && data.gearMode != BydVehicleData.UNAVAILABLE) {
                return data.gearMode;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
