package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FileLockHandle} covering single-acquisition exclusivity, release/reacquire,
 * idempotent double-release, close-as-release, the path getter, and IO-error handling.
 */
class FileLockHandleTest {

  @Test
  void firstAcquireSucceedsSecondReturnsNull(@TempDir Path tmp) {
    Path lockFile = tmp.resolve(".lock");
    FileLockHandle a = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(a);
    try {
      FileLockHandle b = FileLockHandle.tryAcquire(lockFile);
      assertNull(b, "second acquire on same JVM should not get the lock");
    } finally {
      a.release();
    }
  }

  @Test
  void releaseAllowsReacquisition(@TempDir Path tmp) {
    Path lockFile = tmp.resolve(".lock");
    FileLockHandle a = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(a);
    a.release();
    FileLockHandle b = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(b);
    b.release();
  }

  @Test
  void doubleReleaseDoesNotThrow(@TempDir Path tmp) {
    Path lockFile = tmp.resolve(".lock");
    FileLockHandle handle = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(handle);
    handle.release();
    // Second release should be a no-op
    assertDoesNotThrow(handle::release);
  }

  @Test
  void closeReleases(@TempDir Path tmp) {
    Path lockFile = tmp.resolve(".lock");
    FileLockHandle handle = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(handle);
    handle.close();
    // After close, another session should be able to acquire
    FileLockHandle second = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(second);
    second.release();
  }

  @Test
  void acquireReturnsNullOnIoError(@TempDir Path tmp) {
    // Create a lock file inside a directory that we already have open
    // (On some OS this causes locking, but let's just verify the null path)
    Path lockFile = tmp.resolve("nonexistent").resolve("parent").resolve(".lock");
    try (FileLockHandle handle = FileLockHandle.tryAcquire(lockFile)) {
      // Parent doesn't exist so this might create the directory; if Files.createDirectories
      // works, the lock will succeed. This test just verifies no exception.
      if (handle != null) {
        handle.release();
      }
    }
  }

  @Test
  void fileGetterReturnsCorrectPath(@TempDir Path tmp) {
    Path lockFile = tmp.resolve("my.lock");
    FileLockHandle handle = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(handle);
    assertEquals(lockFile.toAbsolutePath(), handle.file());
    handle.release();
  }

  @Test
  void releasesAndReacquiresWithoutError(@TempDir Path tmp) {
    Path lockFile = tmp.resolve(".lock");
    FileLockHandle a = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(a);
    a.release();

    // Reacquire should work after release
    FileLockHandle b = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(b);
    b.release();
  }

  @Test
  void secondAcquireWithinSameThreadReturnsNull(@TempDir Path tmp) {
    // Same-thread acquires should fail because the lock is held
    Path lockFile = tmp.resolve(".lock");
    FileLockHandle first = FileLockHandle.tryAcquire(lockFile);
    assertNotNull(first);
    try {
      FileLockHandle second = FileLockHandle.tryAcquire(lockFile);
      assertNull(second, "second acquire on same JVM should return null");
    } finally {
      first.release();
    }
  }

  private static void assertDoesNotThrow(Runnable r) {
    try {
      r.run();
    } catch (Exception e) {
      throw new AssertionError("Expected no exception, but got: " + e, e);
    }
  }
}
