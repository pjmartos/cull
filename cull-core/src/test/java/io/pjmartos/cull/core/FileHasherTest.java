package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link FileHasher}, covering single-file digests and concurrent {@code hashAll}. */
class FileHasherTest {

  @Test
  void knownVectorEmpty(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("empty");
    Files.write(f, new byte[0]);
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        Hex.encode(FileHasher.sha256(f)));
  }

  @Test
  void knownVectorAbc(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("abc");
    Files.write(f, "abc".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        Hex.encode(FileHasher.sha256(f)));
  }

  @Test
  void multiChunkFileHashesIdenticallyToInMemory(@TempDir Path tmp) throws IOException {
    // Larger than the 64 KiB streaming buffer so the read loop iterates many
    // times; the loop must consume every chunk (terminate only on EOF, -1),
    // and the streamed digest must match the in-memory digest of the same
    // bytes exactly.
    byte[] data = new byte[64 * 1024 * 3 + 7];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) ((i * 31 + 7) & 0xFF);
    }
    Path f = tmp.resolve("big.bin");
    Files.write(f, data);

    assertEquals(Hex.encode(FileHasher.sha256(data)), Hex.encode(FileHasher.sha256(f)));
  }

  @Test
  void hashAllComputesEveryFileDigestConcurrently(@TempDir Path tmp) throws IOException {
    List<Path> files = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      Path f = tmp.resolve("f" + i + ".bin");
      Files.write(f, ("payload-" + i).getBytes(StandardCharsets.UTF_8));
      files.add(f);
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      Map<Path, byte[]> result = FileHasher.hashAll(files, pool);
      assertEquals(files.size(), result.size());
      for (Path f : files) {
        assertEquals(
            Hex.encode(FileHasher.sha256(f)),
            Hex.encode(result.get(f)),
            "concurrent digest must match the single-threaded digest for " + f);
      }
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void hashAllOnEmptyCollectionReturnsEmptyMap() {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      assertTrue(FileHasher.hashAll(List.of(), pool).isEmpty());
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void hashAllSurfacesPerFileFailureAsIllegalState(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.bin");
    @SuppressWarnings("resource")
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class, () -> FileHasher.hashAll(List.of(missing), pool));
      assertTrue(ex.getMessage().contains("does-not-exist.bin"));
    } finally {
      pool.shutdown();
    }
  }
}
