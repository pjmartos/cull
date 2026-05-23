package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeHasherTest {

  @Test
  void nonDirectoryReturnsFixedHash(@TempDir Path tmp) throws IOException {
    byte[] h = TreeHasher.hashDirectory(tmp.resolve("nope"));
    assertEquals(32, h.length);
  }

  @Test
  void emptyDirectoryReturnsHash(@TempDir Path tmp) throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("empty"));
    byte[] h = TreeHasher.hashDirectory(dir);
    assertEquals(32, h.length);
    assertNotNull(h);
  }

  @Test
  void hashIsDeterministic(@TempDir Path tmp) throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("d"));
    Files.writeString(dir.resolve("a.txt"), "hello");

    byte[] h1 = TreeHasher.hashDirectory(dir);
    byte[] h2 = TreeHasher.hashDirectory(dir);
    assertEquals(Hex.encode(h1), Hex.encode(h2));
  }

  @Test
  void differentContentProducesDifferentHash(@TempDir Path tmp) throws IOException {
    Path d1 = Files.createDirectories(tmp.resolve("d1"));
    Files.writeString(d1.resolve("a.txt"), "hello");

    Path d2 = Files.createDirectories(tmp.resolve("d2"));
    Files.writeString(d2.resolve("a.txt"), "world");

    byte[] h1 = TreeHasher.hashDirectory(d1);
    byte[] h2 = TreeHasher.hashDirectory(d2);
    assertNotEquals(Hex.encode(h1), Hex.encode(h2));
  }

  @Test
  void unreadableFileSurfacesAsCheckedIOException(@TempDir Path tmp) throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("tree"));
    Path victim = dir.resolve("locked.bin");
    Files.write(victim, new byte[] {1, 2, 3, 4});

    // Open and lock the file while it is still readable. Stripping the read bit
    // first would make FileChannel.open(... READ ...) itself throw
    // AccessDeniedException on POSIX (e.g. the non-root Linux CI runner), before
    // we ever reach the assertion. On Windows the mandatory lock is what denies
    // the later read; on POSIX the lock is advisory, so we strip the read
    // permission below as well.
    try (FileChannel ch =
            FileChannel.open(victim, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock held = ch.lock()) {
      assertNotNull(held);
      victim.toFile().setReadable(false, false);
      try {
        boolean denied;
        try (InputStream in = Files.newInputStream(victim)) {
          in.read();
          denied = false;
        } catch (IOException expected) {
          denied = true;
        }
        assumeTrue(denied, "filesystem does not deny reading a locked/unreadable file");

        // Before the fix this escaped as an unchecked UncheckedIOException even
        // though hashDirectory declares `throws IOException`; the contract must
        // be honoured so callers can degrade gracefully (cull never fails the
        // build).
        assertThrows(IOException.class, () -> TreeHasher.hashDirectory(dir));
      } finally {
        victim.toFile().setReadable(true, false);
      }
    }
  }

  @Test
  void subdirectoryFilesAreIncluded(@TempDir Path tmp) throws IOException {
    Path root = Files.createDirectories(tmp.resolve("r"));
    Files.writeString(root.resolve("top.txt"), "top");
    Path sub = Files.createDirectories(root.resolve("sub"));
    Files.writeString(sub.resolve("inner.txt"), "inner");

    byte[] h = TreeHasher.hashDirectory(root);
    assertEquals(32, h.length);
  }
}
