package io.pjmartos.cull.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class TestGraphCodec {

  private static final byte[] MAGIC = {'C', 'U', 'L', 'L'};
  private static final byte FORMAT_VERSION = 2;
  private static final int FLAG_DEGRADED = 0x01;

  private TestGraphCodec() {}

  public static byte[] encode(TestGraph graph) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(MAGIC);
    body.write(FORMAT_VERSION);
    body.write(0); // reserved
    int flagsHi = 0;
    int flagsLo = 0;
    if (graph.degraded()) {
      flagsHi |= FLAG_DEGRADED;
    }
    body.write(flagsHi);
    body.write(flagsLo);

    TreeMap<RelPath, byte[]> sortedHashes = new TreeMap<>(graph.hashes());
    Map<RelPath, Integer> pathIndex = new HashMap<>();
    Varint.writeUnsigned(body, sortedHashes.size());
    int idx = 0;
    for (Map.Entry<RelPath, byte[]> e : sortedHashes.entrySet()) {
      pathIndex.put(e.getKey(), idx++);
      byte[] pathBytes = e.getKey().value().getBytes(StandardCharsets.UTF_8);
      Varint.writeUnsigned(body, pathBytes.length);
      body.write(pathBytes);
      byte[] hash = e.getValue();
      if (hash.length != 32) {
        throw new IOException("expected SHA-256 (32 bytes), got " + hash.length);
      }
      body.write(hash);
    }

    TreeMap<String, Set<RelPath>> sortedTests = new TreeMap<>(graph.testToDeps());
    Varint.writeUnsigned(body, sortedTests.size());
    for (Map.Entry<String, Set<RelPath>> e : sortedTests.entrySet()) {
      byte[] testBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
      Varint.writeUnsigned(body, testBytes.length);
      body.write(testBytes);
      TreeSet<RelPath> sortedDeps = new TreeSet<>(e.getValue());
      Varint.writeUnsigned(body, sortedDeps.size());
      for (RelPath p : sortedDeps) {
        Integer i = pathIndex.get(p);
        if (i == null) {
          throw new IOException("dep path missing from path table: " + p);
        }
        Varint.writeUnsigned(body, i);
      }
    }

    TreeSet<String> sortedFailed = new TreeSet<>(graph.failedLastRun());
    Varint.writeUnsigned(body, sortedFailed.size());
    for (String s : sortedFailed) {
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      Varint.writeUnsigned(body, b.length);
      body.write(b);
    }

    TreeSet<CrossRef> sortedRefs = new TreeSet<>();
    for (Set<CrossRef> refs : graph.crossDeps().values()) {
      sortedRefs.addAll(refs);
    }
    sortedRefs.addAll(graph.upstreamHashes().keySet());
    Map<CrossRef, Integer> refIndex = new HashMap<>();
    Varint.writeUnsigned(body, sortedRefs.size());
    int ri = 0;
    for (CrossRef cr : sortedRefs) {
      refIndex.put(cr, ri++);
      writeString(body, cr.moduleId());
      writeString(body, cr.className());
    }

    TreeMap<String, Set<CrossRef>> sortedCross = new TreeMap<>(graph.crossDeps());
    Varint.writeUnsigned(body, sortedCross.size());
    for (Map.Entry<String, Set<CrossRef>> e : sortedCross.entrySet()) {
      writeString(body, e.getKey());
      TreeSet<CrossRef> deps = new TreeSet<>(e.getValue());
      Varint.writeUnsigned(body, deps.size());
      for (CrossRef cr : deps) {
        Varint.writeUnsigned(body, refIndex.get(cr));
      }
    }

    TreeMap<CrossRef, byte[]> sortedUpstream = new TreeMap<>(graph.upstreamHashes());
    Varint.writeUnsigned(body, sortedUpstream.size());
    for (Map.Entry<CrossRef, byte[]> e : sortedUpstream.entrySet()) {
      Varint.writeUnsigned(body, refIndex.get(e.getKey()));
      byte[] hash = e.getValue();
      if (hash.length != 32) {
        throw new IOException("expected SHA-256 (32 bytes), got " + hash.length);
      }
      body.write(hash);
    }

    // Trailing, additive block (no version bump): old readers stop after
    // upstreamHashes and ignore these bytes (still inside the digested payload,
    // so integrity holds); new readers below treat its absence as "empty".
    TreeSet<String> sortedKnown = new TreeSet<>(graph.knownTests());
    Varint.writeUnsigned(body, sortedKnown.size());
    for (String s : sortedKnown) {
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      Varint.writeUnsigned(body, b.length);
      body.write(b);
    }

    byte[] payload = body.toByteArray();
    byte[] digest = sha256(payload);
    ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 32);
    out.write(payload);
    out.write(digest);
    return out.toByteArray();
  }

  public static TestGraph decode(byte[] bytes) throws IOException {
    if (bytes.length < 8 + 32) {
      throw new IOException("file too small");
    }
    byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
    byte[] storedDigest = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
    byte[] computed = sha256(payload);
    if (!Arrays.equals(storedDigest, computed)) {
      throw new IOException("integrity check failed");
    }
    return decodePayload(payload);
  }

  private static TestGraph decodePayload(byte[] payload) throws IOException {
    InputStream in = new ByteArrayInputStream(payload);
    byte[] magic = new byte[4];
    if (in.read(magic) != 4 || !Arrays.equals(magic, MAGIC)) {
      throw new IOException("bad magic");
    }
    int format = in.read();
    if (format != FORMAT_VERSION) {
      throw new IOException("unsupported format version: " + format);
    }
    in.read(); // reserved
    int flagsHi = in.read();
    in.read(); // flagsLo — reserved for future flag bits
    boolean degraded = (flagsHi & FLAG_DEGRADED) != 0;

    int pathCount = Varint.readUnsignedInt(in);
    List<RelPath> paths = new ArrayList<>(pathCount);
    Map<RelPath, byte[]> hashes = new HashMap<>(pathCount);
    for (int i = 0; i < pathCount; i++) {
      int len = Varint.readUnsignedInt(in);
      byte[] pb = readN(in, len);
      byte[] hash = readN(in, 32);
      RelPath p = RelPath.of(new String(pb, StandardCharsets.UTF_8));
      paths.add(p);
      hashes.put(p, hash);
    }

    int testCount = Varint.readUnsignedInt(in);
    Map<String, Set<RelPath>> tests = new HashMap<>(testCount);
    for (int i = 0; i < testCount; i++) {
      int len = Varint.readUnsignedInt(in);
      byte[] tb = readN(in, len);
      String testId = new String(tb, StandardCharsets.UTF_8);
      int depCount = Varint.readUnsignedInt(in);
      Set<RelPath> deps = new LinkedHashSet<>(depCount);
      for (int d = 0; d < depCount; d++) {
        int idx = Varint.readUnsignedInt(in);
        if (idx < 0 || idx >= paths.size()) {
          throw new IOException("path index out of range: " + idx);
        }
        deps.add(paths.get(idx));
      }
      tests.put(testId, deps);
    }

    int failedCount = Varint.readUnsignedInt(in);
    Set<String> failed = new HashSet<>(failedCount);
    for (int i = 0; i < failedCount; i++) {
      int len = Varint.readUnsignedInt(in);
      byte[] b = readN(in, len);
      failed.add(new String(b, StandardCharsets.UTF_8));
    }

    int refCount = Varint.readUnsignedInt(in);
    List<CrossRef> refs = new ArrayList<>(refCount);
    for (int i = 0; i < refCount; i++) {
      String moduleId = readString(in);
      String className = readString(in);
      refs.add(new CrossRef(moduleId, className));
    }

    int crossTestCount = Varint.readUnsignedInt(in);
    Map<String, Set<CrossRef>> crossDeps = new HashMap<>(crossTestCount);
    for (int i = 0; i < crossTestCount; i++) {
      String testId = readString(in);
      int depCount = Varint.readUnsignedInt(in);
      Set<CrossRef> deps = new LinkedHashSet<>(depCount);
      for (int d = 0; d < depCount; d++) {
        int idx = Varint.readUnsignedInt(in);
        if (idx < 0 || idx >= refs.size()) {
          throw new IOException("cross-ref index out of range: " + idx);
        }
        deps.add(refs.get(idx));
      }
      crossDeps.put(testId, deps);
    }

    int upstreamCount = Varint.readUnsignedInt(in);
    Map<CrossRef, byte[]> upstreamHashes = new HashMap<>(upstreamCount);
    for (int i = 0; i < upstreamCount; i++) {
      int idx = Varint.readUnsignedInt(in);
      if (idx < 0 || idx >= refs.size()) {
        throw new IOException("cross-ref index out of range: " + idx);
      }
      upstreamHashes.put(refs.get(idx), readN(in, 32));
    }

    // Backward compatible: a pre-knownTests cache ends here. ByteArrayInputStream
    // reports exact remaining bytes, so absence => empty (self-heals on commit).
    Set<String> knownTests = new HashSet<>();
    if (in.available() > 0) {
      int knownCount = Varint.readUnsignedInt(in);
      for (int i = 0; i < knownCount; i++) {
        int len = Varint.readUnsignedInt(in);
        byte[] b = readN(in, len);
        knownTests.add(new String(b, StandardCharsets.UTF_8));
      }
    }

    return new TestGraph(tests, hashes, failed, degraded, crossDeps, upstreamHashes, knownTests);
  }

  private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    Varint.writeUnsigned(out, b.length);
    out.write(b, 0, b.length);
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
        throw new IOException("EOF reading " + n + " bytes");
      }
      off += r;
    }
    return out;
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
