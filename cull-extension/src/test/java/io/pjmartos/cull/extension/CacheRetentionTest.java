package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheRetentionTest {

  @Test
  void gcBelowRetentionKeepsAll(@TempDir Path tmp) throws IOException {
    Path base = Files.createDirectories(tmp);
    writeStateFile(base, "abc123");
    writeStateFile(base, "def456");

    CacheRetention.gc(base, 3); // retention 3, only 2 files
    assertTrue(Files.exists(base.resolve("abc123.state.bin")));
    assertTrue(Files.exists(base.resolve("def456.state.bin")));
  }

  @Test
  void gcAboveRetentionRemovesOldest(@TempDir Path tmp) throws IOException {
    Path base = Files.createDirectories(tmp);
    writeStateFile(base, "oldest");
    writeStateFile(base, "middle");
    writeStateFile(base, "newest");

    // Touch newest last-used so it sorts first
    Files.writeString(base.resolve("newest.last_used"), Instant.now().toString());
    Files.writeString(
        base.resolve("middle.last_used"), Instant.now().minusSeconds(3600).toString());
    Files.writeString(
        base.resolve("oldest.last_used"), Instant.now().minusSeconds(7200).toString());

    CacheRetention.gc(base, 2);
    assertTrue(Files.exists(base.resolve("newest.state.bin")));
    assertTrue(Files.exists(base.resolve("middle.state.bin")));
    assertFalse(Files.exists(base.resolve("oldest.state.bin")));
    assertFalse(Files.exists(base.resolve("oldest.last_used")));
  }

  @Test
  void gcWithZeroRetentionSkips() throws IOException {
    // retention <= 0 means no GC
    Path base = Files.createTempDirectory("cr-zero");
    writeStateFile(base, "abc");
    CacheRetention.gc(base, 0);
    assertTrue(Files.exists(base.resolve("abc.state.bin")));
  }

  @Test
  void gcWithNegativeRetentionSkips() throws IOException {
    Path base = Files.createTempDirectory("cr-neg");
    writeStateFile(base, "abc");
    CacheRetention.gc(base, -1);
    assertTrue(Files.exists(base.resolve("abc.state.bin")));
  }

  @Test
  void gcEvictsCoverageBaselinesInLockstep(@TempDir Path tmp) throws IOException {
    Path base = Files.createDirectories(tmp);
    writeStateFile(base, "keep");
    writeStateFile(base, "evict");
    // Both checksums carry retained-coverage baselines.
    Files.write(base.resolve("keep.jacoco.exec"), new byte[] {1});
    Files.write(base.resolve("evict.jacoco.exec"), new byte[] {1});
    Files.write(base.resolve("evict.jacoco-it.exec"), new byte[] {1});
    Files.writeString(base.resolve("keep.last_used"), Instant.now().toString());
    Files.writeString(base.resolve("evict.last_used"), Instant.now().minusSeconds(7200).toString());

    CacheRetention.gc(base, 1);

    assertTrue(Files.exists(base.resolve("keep.jacoco.exec")), "live baseline kept");
    assertFalse(Files.exists(base.resolve("evict.jacoco.exec")), "evicted unit baseline removed");
    assertFalse(Files.exists(base.resolve("evict.jacoco-it.exec")), "evicted IT baseline removed");
  }

  @Test
  void readLastUsedFallsBackToFileTime(@TempDir Path tmp) throws IOException {
    Path state = tmp.resolve("checksum.state.bin");
    Files.write(state, new byte[] {1, 2, 3});
    String lu = CacheRetention.readLastUsed(state);
    // Returns file modification time as fallback when .last_used doesn't exist
    assertFalse(lu.isEmpty());
  }

  @Test
  void readLastUsedReturnsContentWhenCompanionExists(@TempDir Path tmp) throws IOException {
    Path state = tmp.resolve("abc.state.bin");
    Files.write(state, new byte[] {1});
    Files.writeString(tmp.resolve("abc.last_used"), "2024-01-15T10:30:00Z");

    String lu = CacheRetention.readLastUsed(state);
    assertEquals("2024-01-15T10:30:00Z", lu);
  }

  private static void writeStateFile(Path base, String checksum) throws IOException {
    Path f = base.resolve(checksum + ".state.bin");
    Files.write(f, new byte[] {1, 2, 3});
    Path lu = base.resolve(checksum + ".last_used");
    Files.write(lu, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
  }
}
