package io.pjmartos.cull.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free reader/merger/writer for the JaCoCo execution-data ({@code .exec})
 * format, used to keep cull's retained coverage baseline compact.
 *
 * <p>cull seeds its cached baseline into JaCoCo's destFile and lets JaCoCo append the executed
 * subset; over many warm runs the file would otherwise accumulate one block-set per run (JaCoCo's
 * report ORs them, so it stays correct, just ever-larger). {@link #compact} parses such a file,
 * OR-merges all execution data sharing a class id into a single block, collapses the session
 * records into one, and rewrites a canonical (deterministically ordered) file — so the baseline
 * stays bounded regardless of how many runs accumulated.
 *
 * <p>The format is the stable JaCoCo 0.8.x layout (magic {@code 0xC0C0}, version {@code 0x1007}): a
 * header block, then a stream of session-info and execution-data blocks, terminated by EOF. An
 * unrecognized version, an unexpected block, or a truncated file raises {@link IOException};
 * callers fall back to copying the file verbatim, so an unknown future format never loses coverage
 * and never breaks the build.
 */
public final class JacocoExec {

  static final int MAGIC = 0xC0C0;
  static final int FORMAT_VERSION = 0x1007;
  static final int BLOCK_HEADER = 0x01;
  static final int BLOCK_SESSIONINFO = 0x10;
  static final int BLOCK_EXECUTIONDATA = 0x11;

  private JacocoExec() {}

  /**
   * Read {@code src}, OR-merge duplicate class blocks, and write a compacted, canonical {@code
   * .exec} to {@code dst}. Throws {@link IOException} when {@code src} is not a recognizable JaCoCo
   * file of the supported version, or is corrupt/truncated.
   */
  public static void compact(Path src, Path dst) throws IOException {
    ExecStore store;
    try (InputStream in = new BufferedInputStream(Files.newInputStream(src))) {
      store = read(in);
    }
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dst))) {
      write(store, out);
    }
  }

  /** Parse a {@code .exec} stream, OR-merging execution data that share a class id. */
  static ExecStore read(InputStream rawIn) throws IOException {
    DataInputStream in = new DataInputStream(rawIn);
    ExecStore store = new ExecStore();
    boolean first = true;
    for (; ; ) {
      int type = in.read();
      if (type == -1) {
        return store;
      }
      if (first && type != BLOCK_HEADER) {
        throw new IOException("not a JaCoCo exec stream (first block 0x" + hex(type) + ")");
      }
      first = false;
      switch (type) {
        case BLOCK_HEADER:
          readHeader(in);
          break;
        case BLOCK_SESSIONINFO:
          store.addSession(new SessionInfo(in.readUTF(), in.readLong(), in.readLong()));
          break;
        case BLOCK_EXECUTIONDATA:
          store.put(new ExecData(in.readLong(), in.readUTF(), readBooleanArray(in)));
          break;
        default:
          throw new IOException("unknown JaCoCo block type 0x" + hex(type));
      }
    }
  }

  /**
   * Write a canonical compacted {@code .exec}: header, one collapsed session, sorted class blocks.
   */
  static void write(ExecStore store, OutputStream rawOut) throws IOException {
    DataOutputStream out = new DataOutputStream(rawOut);
    out.writeByte(BLOCK_HEADER);
    out.writeChar(MAGIC);
    out.writeChar(FORMAT_VERSION);
    SessionInfo session = store.collapsedSession();
    if (session != null) {
      out.writeByte(BLOCK_SESSIONINFO);
      out.writeUTF(session.id);
      out.writeLong(session.start);
      out.writeLong(session.dump);
    }
    for (ExecData data : store.sortedData()) {
      // Match JaCoCo: only classes with at least one hit probe are emitted.
      if (!data.hasHits()) {
        continue;
      }
      out.writeByte(BLOCK_EXECUTIONDATA);
      out.writeLong(data.id);
      out.writeUTF(data.name);
      writeBooleanArray(out, data.probes);
    }
    out.flush();
  }

  private static void readHeader(DataInputStream in) throws IOException {
    int magic = in.readChar();
    if (magic != MAGIC) {
      throw new IOException("bad JaCoCo magic 0x" + hex(magic));
    }
    int version = in.readChar();
    if (version != FORMAT_VERSION) {
      throw new IOException("unsupported JaCoCo exec version 0x" + hex(version));
    }
  }

  // --- JaCoCo's CompactDataOutput/Input variable-length encodings ---

  @SuppressWarnings("PMD.UseVarargs") // a probe array, not a varargs API
  private static void writeBooleanArray(DataOutputStream out, boolean[] value) throws IOException {
    writeVarInt(out, value.length);
    int buffer = 0;
    int bufferSize = 0;
    for (boolean b : value) {
      if (b) {
        buffer |= 0x01 << bufferSize;
      }
      if (++bufferSize == 8) {
        out.writeByte(buffer);
        buffer = 0;
        bufferSize = 0;
      }
    }
    if (bufferSize > 0) {
      out.writeByte(buffer);
    }
  }

  private static boolean[] readBooleanArray(DataInputStream in) throws IOException {
    boolean[] value = new boolean[readVarInt(in)];
    int buffer = 0;
    int bufferSize = 0;
    for (int i = 0; i < value.length; i++) {
      if (bufferSize == 0) {
        buffer = 0xFF & in.readByte();
        bufferSize = 8;
      }
      value[i] = (buffer & 0x01) != 0;
      buffer >>>= 1;
      bufferSize--;
    }
    return value;
  }

  private static void writeVarInt(DataOutputStream out, int value) throws IOException {
    if ((value & 0xFFFFFF80) == 0) {
      out.writeByte(value);
    } else {
      out.writeByte(0x80 | (value & 0x7F));
      writeVarInt(out, value >>> 7);
    }
  }

  private static int readVarInt(DataInputStream in) throws IOException {
    int value = 0xFF & in.readByte();
    if ((value & 0x80) == 0) {
      return value;
    }
    return (value & 0x7F) | (readVarInt(in) << 7);
  }

  private static String hex(int b) {
    return Integer.toHexString(b & 0xFFFF);
  }

  /** Accumulates merged execution data keyed by class id, plus the session records seen. */
  static final class ExecStore {
    private final Map<Long, ExecData> byId = new HashMap<>();
    private final List<SessionInfo> sessions = new ArrayList<>();

    void put(ExecData data) throws IOException {
      ExecData existing = byId.get(data.id);
      if (existing == null) {
        byId.put(data.id, data);
      } else {
        existing.merge(data);
      }
    }

    void addSession(SessionInfo info) {
      sessions.add(info);
    }

    Collection<ExecData> data() {
      return byId.values();
    }

    List<SessionInfo> sessions() {
      return sessions;
    }

    List<ExecData> sortedData() {
      List<ExecData> list = new ArrayList<>(byId.values());
      list.sort(Comparator.comparingLong((ExecData d) -> d.id).thenComparing(d -> d.name));
      return list;
    }

    /**
     * Fold every session record into one with the earliest start and latest dump, so the baseline
     * does not accrue a session block per run. Returns {@code null} when the source had none.
     */
    SessionInfo collapsedSession() {
      if (sessions.isEmpty()) {
        return null;
      }
      long start = Long.MAX_VALUE;
      long dump = Long.MIN_VALUE;
      for (SessionInfo s : sessions) {
        start = Math.min(start, s.start);
        dump = Math.max(dump, s.dump);
      }
      return new SessionInfo("cull", start, dump);
    }
  }

  /** A single class's execution data: its JaCoCo id, VM name, and probe-hit array. */
  static final class ExecData {
    final long id;
    final String name;
    final boolean[] probes;

    @SuppressWarnings("PMD.UseVarargs") // a probe array, not a varargs API
    ExecData(long id, String name, boolean[] probes) {
      this.id = id;
      this.name = name;
      this.probes = probes.clone();
    }

    void merge(ExecData other) throws IOException {
      if (!name.equals(other.name) || probes.length != other.probes.length) {
        // A genuine CRC64 id with mismatched shape is effectively impossible; treat it as a
        // foreign/corrupt file and let the caller fall back to a verbatim copy.
        throw new IOException("conflicting execution data for class id " + id);
      }
      for (int i = 0; i < probes.length; i++) {
        probes[i] |= other.probes[i];
      }
    }

    boolean hasHits() {
      for (boolean b : probes) {
        if (b) {
          return true;
        }
      }
      return false;
    }
  }

  /** A JaCoCo session record (id and start/dump timestamps). */
  static final class SessionInfo {
    final String id;
    final long start;
    final long dump;

    SessionInfo(String id, long start, long dump) {
      this.id = id;
      this.start = start;
      this.dump = dump;
    }
  }
}
