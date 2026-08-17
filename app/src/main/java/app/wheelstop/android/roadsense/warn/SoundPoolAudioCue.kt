package app.wheelstop.android.roadsense.warn

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import app.wheelstop.android.roadsense.detect.Severity

/**
 * SOTA approach chime via [SoundPool] — three designed samples, one per severity,
 * low-latency (pre-loaded, no per-play decode). This is the proper audio path
 * (vs the ToneGenerator placeholder); SoundPool is the right API for short,
 * frequently-triggered UI cues.
 *
 * ## Sample assets (drop-in — see TODO)
 * Expects three raw resources under `res/raw/`:
 *   - `roadsense_chime_minor`    — soft single tick
 *   - `roadsense_chime_moderate` — firmer double note
 *   - `roadsense_chime_severe`   — urgent triple / rising alert
 * Use short (<800 ms), normalized .ogg files (ogg = smaller than wav, SoundPool
 * decodes it fine). **Until those files exist this cue reports notReady and the
 * WarningCoordinator falls back to [ToneAudioCue]** — so the feature is never
 * silent waiting on sound design.
 *
 * Plays on STREAM-equivalent USAGE_ASSISTANCE_SONIFICATION so the chime ducks
 * politely under media rather than fighting it. Lazy-loads on first use; [release]
 * frees the pool when the feature stops.
 */
class SoundPoolAudioCue(private val context: Context) : WarningCoordinator.AudioCue {

    private var pool: SoundPool? = null
    private val soundIds = HashMap<Severity, Int>()
    @Volatile private var loaded = false
    @Volatile private var unavailable = false

    /** True once at least one sample is loaded; WarningCoordinator/fallback checks this. */
    fun isReady(): Boolean = loaded

    /** True if the raw samples are absent/failed — caller should use the fallback. */
    fun isUnavailable(): Boolean = unavailable

    private fun ensureLoaded() {
        if (pool != null || unavailable) return
        val minor = rawId("roadsense_chime_minor")
        val moderate = rawId("roadsense_chime_moderate")
        val severe = rawId("roadsense_chime_severe")
        if (minor == 0 || moderate == 0 || severe == 0) {
            // Samples not bundled yet → mark unavailable; caller falls back to tones.
            unavailable = true
            Log.i(TAG, "chime samples not present in res/raw — using ToneGenerator fallback")
            return
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        sp.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded = true }
        soundIds[Severity.MINOR] = sp.load(context, minor, 1)
        soundIds[Severity.MODERATE] = sp.load(context, moderate, 1)
        soundIds[Severity.SEVERE] = sp.load(context, severe, 1)
        pool = sp
    }

    override fun chime(severity: Severity) {
        ensureLoaded()
        val sp = pool ?: return
        if (!loaded) return // not finished loading; a single missed early chime is fine
        val id = soundIds[severity] ?: return
        // Higher severity slightly louder; left=right=full pan, no loop, normal rate.
        val vol = when (severity) {
            Severity.MINOR -> 0.7f
            Severity.MODERATE -> 0.85f
            Severity.SEVERE -> 1.0f
        }
        try { sp.play(id, vol, vol, 1, 0, 1.0f) } catch (_: Throwable) {}
    }

    override fun release() {
        try { pool?.release() } catch (_: Throwable) {}
        pool = null
        soundIds.clear()
        loaded = false
    }

    private fun rawId(name: String): Int =
        context.resources.getIdentifier(name, "raw", context.packageName)

    companion object { private const val TAG = "RoadSense/Chime" }
}

/**
 * Composite cue: prefer the designed [SoundPoolAudioCue] samples; transparently
 * fall back to [ToneAudioCue] system tones when the raw samples aren't bundled
 * (or fail to load). This is what the controller installs — so audio works today
 * (tones) and upgrades automatically the moment the sample files land, with no
 * code change. Mirrors the project's "graceful degradation" stance.
 */
class RoadSenseAudioCue(context: Context) : WarningCoordinator.AudioCue {
    private val sound = SoundPoolAudioCue(context)
    private val tones = ToneAudioCue()

    override fun chime(severity: Severity) {
        // Touch the SoundPool first (lazy-loads + sets the unavailable flag). If
        // the designed samples aren't present/ready, use tones for THIS chime;
        // once samples finish loading, subsequent chimes use them.
        if (!sound.isUnavailable() && sound.isReady()) {
            sound.chime(severity)
        } else {
            sound.chime(severity)            // kick the lazy-load (no-op if unavailable)
            if (!sound.isReady()) tones.chime(severity)  // cover this chime with a tone
        }
    }

    override fun release() {
        sound.release()
        tones.release()
    }
}
