package app.wheelstop.android.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BlindSpotConfigTest {
    @Test fun activeFpsDefaultsTo25() =
        assertEquals(25, UnifiedConfigManager.blindSpotActiveFps(JSONObject()))

    @Test fun activeFpsHonoursConfigAndClamps() {
        assertEquals(25, UnifiedConfigManager.blindSpotActiveFps(JSONObject().put("activeFps", 25)))
        assertEquals(30, UnifiedConfigManager.blindSpotActiveFps(JSONObject().put("activeFps", 99)))
        assertEquals(1,  UnifiedConfigManager.blindSpotActiveFps(JSONObject().put("activeFps", 0)))
    }

    @Test fun contrastDefaultsToNeutralOne() =
        assertEquals(1.0f, UnifiedConfigManager.blindSpotContrast(JSONObject()), 0f)

    @Test fun contrastClampsToSafetyRange() {
        assertEquals(2.0f, UnifiedConfigManager.blindSpotContrast(JSONObject().put("contrast", 9.0)), 0f)
        assertEquals(0.5f, UnifiedConfigManager.blindSpotContrast(JSONObject().put("contrast", 0.1)), 0f)
    }

    @Test fun sharpenDefaultsToOff() =
        assertEquals(0.0f, UnifiedConfigManager.blindSpotSharpen(JSONObject()), 0f)

    @Test fun sharpenClampsToSafetyRange() {
        assertEquals(1.0f, UnifiedConfigManager.blindSpotSharpen(JSONObject().put("sharpen", 5.0)), 0f)
        assertEquals(0.0f, UnifiedConfigManager.blindSpotSharpen(JSONObject().put("sharpen", -1.0)), 0f)
    }
}
