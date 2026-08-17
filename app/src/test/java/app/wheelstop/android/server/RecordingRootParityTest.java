package app.wheelstop.android.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RecordingRootParityTest {

    @Test
    public void consumersUseStorageManagerInsteadOfDuplicatingLegacyPaths() throws IOException {
        String index = readRepositoryFile(
                "app/src/main/java/app/wheelstop/android/server/RecordingsIndex.java");
        String api = readRepositoryFile(
                "app/src/main/java/app/wheelstop/android/server/RecordingsApiHandler.java");
        String scanner = readRepositoryFile(
                "app/src/main/java/app/wheelstop/android/ui/util/RecordingScanner.kt");
        String storage = readRepositoryFile(
                "app/src/main/java/app/wheelstop/android/storage/StorageManager.java");

        String legacyPrefix = "/storage/emulated/0/Android/data/app.wheelstop.android/files";
        assertFalse(index.contains(legacyPrefix));
        assertFalse(api.contains(legacyPrefix));
        assertFalse(scanner.contains(legacyPrefix));
        assertTrue(storage.contains("RecordingDirectoryRegistry.recordings"));
        assertTrue(storage.contains("RecordingDirectoryRegistry.surveillance"));
        assertTrue(storage.contains("RecordingDirectoryRegistry.proximity"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}