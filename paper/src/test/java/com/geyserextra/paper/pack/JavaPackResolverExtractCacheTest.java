package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the extract cache against the failure it was written for.
 *
 * <p>The resolver used to delete and rewrite its extract directory on every single resolve. That
 * is invisible with one caller and destructive with two: a pack rebuild runs on a scheduler
 * thread, and while it reads item textures out of the directory a second rebuild wipes the
 * directory out from under it. Every texture lost that way is silently dropped from the Bedrock
 * pack and the item falls back to a vanilla look — 537 of them in one observed startup
 * (2026-08-24), with nothing failing and only warnings to show for it.
 *
 * <p>These tests assert the two properties that fix rests on: an unchanged ZIP is not re-extracted
 * at all, and a changed one still is.
 */
@DisplayName("JavaPackResolver extract cache")
class JavaPackResolverExtractCacheTest {

    private static final String ENTRY = "assets/trinityforge/textures/item/probe.png";

    /**
     * Entries in the ZIP used by the concurrency test.
     *
     * <p>Sized to matter: the deployed pack extracts hundreds of item textures, and the race is
     * only reachable while an extraction is in flight. A one-entry ZIP extracts too fast to
     * collide — an earlier draft of this test passed against the unfixed code for that reason.</p>
     */
    private static final int BULK_ENTRIES = 300;

    @Test
    @DisplayName("leaves the extracted files alone when the ZIP has not changed")
    void reusesExtractionWhenZipUnchanged(@TempDir Path tmp) throws IOException {
        Path zip = writeZip(tmp.resolve("pack.zip"), "first");
        Path dataFolder = tmp.resolve("plugin");

        Path first = resolveOnce(zip, dataFolder);
        // A file no extraction would ever produce. Re-extracting deletes the directory, so its
        // survival is a direct assertion that no deleteRecursive ran.
        Path sentinel = first.resolve("sentinel.txt");
        Files.writeString(sentinel, "kept", StandardCharsets.UTF_8);

        Path second = resolveOnce(zip, dataFolder);

        assertThat(second).isEqualTo(first);
        assertThat(sentinel).exists();
        assertThat(second.resolve(ENTRY)).hasContent("first");
    }

    @Test
    @DisplayName("re-extracts when the ZIP is replaced, so operator edits still land")
    void reExtractsWhenZipChanges(@TempDir Path tmp) throws IOException {
        Path zip = writeZip(tmp.resolve("pack.zip"), "first");
        Path dataFolder = tmp.resolve("plugin");

        Path first = resolveOnce(zip, dataFolder);
        Path sentinel = first.resolve("sentinel.txt");
        Files.writeString(sentinel, "should not survive", StandardCharsets.UTF_8);

        writeZip(zip, "second edition");
        Path second = resolveOnce(zip, dataFolder);

        assertThat(second.resolve(ENTRY)).hasContent("second edition");
        assertThat(sentinel).doesNotExist();
    }

    @Test
    @DisplayName("keeps every extracted file readable while other threads resolve the same pack")
    void concurrentResolvesNeverHideExtractedFiles(@TempDir Path tmp) throws Exception {
        Path zip = writeBulkZip(tmp.resolve("pack.zip"), "shared");
        Path dataFolder = tmp.resolve("plugin");

        int threads = 4;
        int rounds = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        Path root = resolveOnce(zip, dataFolder);
                        // What the pack builder does next: read every mapped texture out of the
                        // directory it was just handed. Before the fix another thread's
                        // deleteRecursive lands inside this loop and the read throws.
                        for (int n = 0; n < BULK_ENTRIES; n++) {
                            String content = Files.readString(root.resolve(bulkEntry(n)),
                                StandardCharsets.UTF_8);
                            if (!"shared".equals(content)) {
                                failures.add("unexpected content: " + content);
                            }
                        }
                    }
                } catch (Exception ex) {
                    failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    done.countDown();
                }
            }, "resolve-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("workers finished").isTrue();
        assertThat(failures).isEmpty();
    }

    private static String bulkEntry(int index) {
        return "assets/trinityforge/textures/item/bulk_" + index + ".png";
    }

    /** A ZIP with enough entries that extracting it takes long enough to race against. */
    private static Path writeBulkZip(Path zip, String payload) throws IOException {
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < BULK_ENTRIES; i++) {
                out.putNextEntry(new ZipEntry(bulkEntry(i)));
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        Files.setLastModifiedTime(zip, FileTime.fromMillis(1_700_000_000_000L + payload.length()));
        return zip;
    }

    private static Path resolveOnce(Path zip, Path dataFolder) {
        JavaPackResolver resolver = new JavaPackResolver(
            zip.toString(), null, dataFolder, Logger.getLogger("test"));
        Optional<Path> resolved = resolver.resolve();
        assertThat(resolved).isPresent();
        return resolved.get();
    }

    /**
     * Writes a one-entry ZIP, stamping a modification time derived from the payload.
     *
     * <p>Explicit rather than left to the clock: the cache keys on size and mtime, and two writes
     * inside the same filesystem timestamp tick would otherwise look identical and make
     * {@link #reExtractsWhenZipChanges} pass for the wrong reason.
     */
    private static Path writeZip(Path zip, String payload) throws IOException {
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(ENTRY));
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.setLastModifiedTime(zip, FileTime.fromMillis(1_700_000_000_000L + payload.length()));
        return zip;
    }
}
