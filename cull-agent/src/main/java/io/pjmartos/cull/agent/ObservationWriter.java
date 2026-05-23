package io.pjmartos.cull.agent;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ObservationWriter {

  static final int TAG_CLASS_LOAD = 0;
  static final int TAG_RESOURCE_READ = 1;
  static final int TAG_ORPHAN_CLASS = 2;
  static final int TAG_ORPHAN_RESOURCE = 3;
  static final int TAG_DISCOVERY_CLASS = 4;
  static final int TAG_TEST_START = 5;
  static final int TAG_TEST_END = 6;
  static final int TAG_FORK_METADATA = 7;
  static final int TAG_DISCOVERY_END = 8;
  static final int TAG_CLASS_START = 9;
  static final int TAG_CLASS_END = 10;

  private final Path file;
  private final FileChannel channel;
  private final OutputStream out;
  private final Object lock = new Object();
  private int recordsSinceFlush;
  private volatile boolean closed;

  public ObservationWriter(Path file) throws IOException {
    this.file = file;
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    this.channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
    this.out = new BufferedOutputStream(Channels.newOutputStream(channel));
  }

  /**
   * Flushes buffered records and forces them to durable storage. The per-fork file is consumed by a
   * separate process at commit time, so a crash after JVM exit must not lose recorded observations.
   */
  public void fsync() {
    synchronized (lock) {
      if (closed) return;
      try {
        out.flush();
        channel.force(true);
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  public Path file() {
    return file;
  }

  public void writeForkMetadata(String forkId) throws IOException {
    synchronized (lock) {
      if (closed) return;
      byte[] f = forkId.getBytes(StandardCharsets.UTF_8);
      out.write(TAG_FORK_METADATA);
      writeVarint(out, f.length);
      out.write(f);
      writeVarint(out, 8);
      long ts = System.currentTimeMillis();
      for (int i = 7; i >= 0; i--) {
        out.write((int) ((ts >> (i * 8)) & 0xFF));
      }
      maybeFlush();
    }
  }

  public void recordTestStart(String testId) {
    write(TAG_TEST_START, testId, "");
  }

  public void recordTestEnd(String testId) {
    write(TAG_TEST_END, testId, "");
  }

  public void recordClassStart(String testId) {
    write(TAG_CLASS_START, testId, "");
  }

  public void recordClassEnd(String testId) {
    write(TAG_CLASS_END, testId, "");
  }

  public void recordClassLoad(String testId, String className) {
    write(TAG_CLASS_LOAD, testId, className);
  }

  public void recordOrphanLoad(String className) {
    write(TAG_ORPHAN_CLASS, null, className);
  }

  public void recordDiscoveryClass(String className) {
    write(TAG_DISCOVERY_CLASS, null, className);
  }

  public void recordResourceRead(String testId, String resourcePath) {
    write(TAG_RESOURCE_READ, testId, resourcePath);
  }

  public void recordOrphanResource(String resourcePath) {
    write(TAG_ORPHAN_RESOURCE, null, resourcePath);
  }

  public void recordDiscoveryEnd() {
    write(TAG_DISCOVERY_END, null, "");
  }

  private void write(int tag, String testId, String payload) {
    try {
      synchronized (lock) {
        if (closed) return;
        out.write(tag);
        if (testId != null) {
          byte[] tb = testId.getBytes(StandardCharsets.UTF_8);
          writeVarint(out, tb.length);
          out.write(tb);
        }
        byte[] pb = payload.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, pb.length);
        out.write(pb);
        maybeFlush();
      }
    } catch (IOException e) {
      // Recording is best-effort. Closing the writer prevents repeated I/O on
      // broken streams.
      closeQuietly();
    }
  }

  private void maybeFlush() throws IOException {
    recordsSinceFlush++;
    if (recordsSinceFlush >= 256) {
      out.flush();
      recordsSinceFlush = 0;
    }
  }

  public void closeQuietly() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      try {
        out.flush();
        channel.force(true);
        out.close();
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  static void writeVarint(OutputStream out, long value) throws IOException {
    while ((value & ~0x7FL) != 0) {
      out.write((int) ((value & 0x7FL) | 0x80L));
      value >>>= 7;
    }
    out.write((int) value);
  }
}
