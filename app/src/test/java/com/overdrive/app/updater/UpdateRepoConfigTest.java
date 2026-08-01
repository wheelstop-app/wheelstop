package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.BuildConfig;
import org.junit.Test;

/** Guards the fork-update source: the app must check the fork, not upstream. */
public class UpdateRepoConfigTest {
    @Test
    public void updateRepoPointsAtFork() {
        assertEquals("shauneccles/Overdrive-release", BuildConfig.UPDATE_REPO);
    }
}
