package io.pjmartos.cull.extension;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FileLockHandle implements AutoCloseable {

  private final FileChannel channel;
  private final FileLock lock;
  private final Path file;

  private FileLockHandle(FileChannel channel, FileLock lock, Path file) {
    this.channel = channel;
    this.lock = lock;
    this.file = file;
  }

  // FileLock + FileChannel lifetime is owned by the returned FileLockHandle.
  @SuppressWarnings("PMD.CloseResource")
  public static FileLockHandle tryAcquire(Path lockFile) {
    FileChannel ch = null;
    try {
      Path parent = lockFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      ch =
          FileChannel.open(
              lockFile,
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE);
      FileLock l = ch.tryLock();
      if (l == null) {
        ch.close();
        return null;
      }
      return new FileLockHandle(ch, l, lockFile);
    } catch (OverlappingFileLockException | IOException e) {
      closeQuietly(ch);
      return null;
    }
  }

  private static void closeQuietly(FileChannel ch) {
    if (ch == null) return;
    try {
      ch.close();
    } catch (IOException ignored) {
      // best effort
    }
  }

  public Path file() {
    return file;
  }

  public void release() {
    try {
      if (lock != null && lock.isValid()) {
        lock.release();
      }
    } catch (IOException ignored) {
      // best effort
    }
    try {
      if (channel.isOpen()) {
        channel.close();
      }
    } catch (IOException ignored) {
      // best effort
    }
  }

  @Override
  public void close() {
    release();
  }
}
