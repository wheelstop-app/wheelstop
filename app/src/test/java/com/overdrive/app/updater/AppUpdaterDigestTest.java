package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AppUpdaterDigestTest {

    @Test public void findsSumsAssetCaseInsensitively() throws Exception {
        JSONArray assets = new JSONArray();
        JSONObject apk = new JSONObject();
        apk.put("name", "overdrive-alpha-v33.1.apk");
        apk.put("browser_download_url", "https://example/apk");
        JSONObject sums = new JSONObject();
        sums.put("name", "sha256sums"); // lower-case on purpose
        sums.put("browser_download_url", "https://example/SUMS");
        assets.put(apk);
        assets.put(sums);
        assertEquals("https://example/SUMS", AppUpdater.sha256SumsAssetUrl(assets));
    }

    @Test public void sumsAssetAbsentYieldsEmpty() throws Exception {
        JSONArray assets = new JSONArray();
        JSONObject apk = new JSONObject();
        apk.put("name", "overdrive-alpha-v33.1.apk");
        apk.put("browser_download_url", "https://example/apk");
        assets.put(apk);
        assertEquals("", AppUpdater.sha256SumsAssetUrl(assets));
    }

    @Test public void nullAssetsYieldEmpty() {
        assertEquals("", AppUpdater.sha256SumsAssetUrl(null));
    }

    @Test public void extractsApkDigestIgnoringOtherLines() {
        String sums =
            "0000000000000000000000000000000000000000000000000000000000000000  NOTES.txt\n"
          + "ABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789  overdrive-alpha-v33.1.apk\n";
        // lower-cased, apk line only
        assertEquals(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            AppUpdater.expectedApkDigest(sums));
    }

    @Test public void missingApkLineYieldsEmpty() {
        assertEquals("", AppUpdater.expectedApkDigest("deadbeef  NOTES.txt\n"));
    }

    @Test public void nullSumsContentYieldsEmpty() {
        assertEquals("", AppUpdater.expectedApkDigest(null));
    }

    @Test public void classifyMatch() {
        assertEquals("VERIFIED", AppUpdater.classifyDigest("aa", "AA")); // case-insensitive
    }

    @Test public void classifyMismatch() {
        assertEquals("MISMATCH", AppUpdater.classifyDigest("aa", "bb"));
    }

    @Test public void classifyMissingEitherIsUnverified() {
        assertEquals("UNVERIFIED", AppUpdater.classifyDigest("", "bb"));
        assertEquals("UNVERIFIED", AppUpdater.classifyDigest("aa", ""));
        assertEquals("UNVERIFIED", AppUpdater.classifyDigest(null, "bb"));
    }
}
