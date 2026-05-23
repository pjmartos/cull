package io.pjmartos.cull.extension;

import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.Varint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SessionStateCodec {

  private static final byte[] MAGIC = {'C', 'U', 'L', 'S'};
  // v3 adds the trailing candidateTests block. This is a per-build handoff
  // file under target/, rewritten every build, so a stale v2 file simply
  // degrades that one build to the in-realm registry (decode throws, caller
  // ignores) — no persistent cache is affected.
  private static final byte VERSION = 3;
  private static final int HASH_LEN = 32;
  private static final int FLAG_WILDCARD = 1;
  private static final int FLAG_DEGRADED = 2;
  private static final int FLAG_XML_REPORTS_DISABLED = 4;

  private SessionStateCodec() {}

  public static byte[] encode(SessionState s) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(MAGIC);
      out.write(VERSION);
      int flags = 0;
      if (s.wildcard) flags |= FLAG_WILDCARD;
      if (s.degraded) flags |= FLAG_DEGRADED;
      if (s.xmlReportsDisabled) flags |= FLAG_XML_REPORTS_DISABLED;
      out.write(flags);
      writeString(out, s.projectChecksum);
      writeString(out, s.cacheBaseDir.toString());
      writeString(out, s.stagingDir.toString());
      writeString(out, s.projectBasedir.toString());
      writeString(out, s.buildDirectory.toString());
      writeString(out, s.mainClassesDir.toString());
      writeString(out, s.testClassesDir.toString());
      Varint.writeUnsigned(out, s.retention);
      Varint.writeUnsigned(out, s.selectedTests.size());
      for (String t : s.selectedTests) {
        writeString(out, t);
      }
      Varint.writeUnsigned(out, s.currentHashes.size());
      for (Map.Entry<RelPath, byte[]> e : s.currentHashes.entrySet()) {
        writeString(out, e.getKey().value());
        byte[] h = e.getValue();
        if (h.length != HASH_LEN) {
          throw new IllegalArgumentException(
              "hash for " + e.getKey().value() + " has length " + h.length);
        }
        out.write(h);
      }
      Varint.writeUnsigned(out, s.resourceRoots.size());
      for (RelPath r : s.resourceRoots) {
        writeString(out, r.value());
      }
      writeString(out, s.crossModule == null ? "off" : s.crossModule);
      Varint.writeUnsigned(out, s.reactorSiblings.size());
      for (Map.Entry<String, String> e : s.reactorSiblings.entrySet()) {
        writeString(out, e.getKey());
        writeString(out, e.getValue());
      }
      Varint.writeUnsigned(out, s.candidateTests.size());
      for (String t : s.candidateTests) {
        writeString(out, t);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("encode failed", e);
    }
  }

  public static SessionState decode(byte[] bytes) throws IOException {
    InputStream in = new ByteArrayInputStream(bytes);
    byte[] magic = new byte[4];
    if (in.read(magic) != 4
        || magic[0] != 'C'
        || magic[1] != 'U'
        || magic[2] != 'L'
        || magic[3] != 'S') {
      throw new IOException("bad magic");
    }
    int version = in.read();
    if (version != VERSION) {
      throw new IOException("unsupported version: " + version);
    }
    int flags = in.read();
    if (flags < 0) {
      throw new IOException("EOF reading flags");
    }
    boolean wildcard = (flags & FLAG_WILDCARD) != 0;
    boolean degraded = (flags & FLAG_DEGRADED) != 0;
    boolean xmlReportsDisabled = (flags & FLAG_XML_REPORTS_DISABLED) != 0;
    String projectChecksum = readString(in);
    Path cacheBaseDir = Paths.get(readString(in));
    Path stagingDir = Paths.get(readString(in));
    Path projectBasedir = Paths.get(readString(in));
    Path buildDirectory = Paths.get(readString(in));
    Path mainClassesDir = Paths.get(readString(in));
    Path testClassesDir = Paths.get(readString(in));
    int retention = Varint.readUnsignedInt(in);
    int selectedCount = Varint.readUnsignedInt(in);
    Set<String> selected = new LinkedHashSet<>(selectedCount);
    for (int i = 0; i < selectedCount; i++) {
      selected.add(readString(in));
    }
    int hashCount = Varint.readUnsignedInt(in);
    Map<RelPath, byte[]> hashes = new LinkedHashMap<>(hashCount);
    for (int i = 0; i < hashCount; i++) {
      String p = readString(in);
      byte[] h = readN(in, HASH_LEN);
      hashes.put(RelPath.of(p), h);
    }
    int rootCount = Varint.readUnsignedInt(in);
    Set<RelPath> roots = new LinkedHashSet<>(rootCount);
    for (int i = 0; i < rootCount; i++) {
      roots.add(RelPath.of(readString(in)));
    }
    String crossModule = readString(in);
    int siblingCount = Varint.readUnsignedInt(in);
    Map<String, String> siblings = new LinkedHashMap<>(siblingCount);
    for (int i = 0; i < siblingCount; i++) {
      String k = readString(in);
      siblings.put(k, readString(in));
    }
    int candidateCount = Varint.readUnsignedInt(in);
    Set<String> candidateTests = new LinkedHashSet<>(candidateCount);
    for (int i = 0; i < candidateCount; i++) {
      candidateTests.add(readString(in));
    }
    return new SessionState(
        projectChecksum,
        cacheBaseDir,
        stagingDir,
        projectBasedir,
        buildDirectory,
        mainClassesDir,
        testClassesDir,
        retention,
        wildcard,
        degraded,
        xmlReportsDisabled,
        selected,
        hashes,
        roots,
        siblings,
        crossModule,
        candidateTests);
  }

  private static void writeString(OutputStream out, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    Varint.writeUnsigned(out, b.length);
    out.write(b);
  }

  private static String readString(InputStream in) throws IOException {
    int len = Varint.readUnsignedInt(in);
    return new String(readN(in, len), StandardCharsets.UTF_8);
  }

  private static byte[] readN(InputStream in, int n) throws IOException {
    byte[] out = new byte[n];
    int off = 0;
    while (off < n) {
      int r = in.read(out, off, n - off);
      if (r < 0) {
        throw new IOException("EOF reading " + n);
      }
      off += r;
    }
    return out;
  }
}
