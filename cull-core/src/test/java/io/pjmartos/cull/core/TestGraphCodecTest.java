package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestGraphCodecTest {

  @Test
  void roundTrip() throws IOException {
    Map<String, Set<RelPath>> tests = new HashMap<>();
    tests.put(
        "com.example.AlphaTest",
        set(RelPath.of("src/main/java/A.java"), RelPath.of("src/main/java/B.java")));
    tests.put("com.example.BetaTest", set(RelPath.of("src/main/java/B.java")));

    Map<RelPath, byte[]> hashes = new HashMap<>();
    hashes.put(RelPath.of("src/main/java/A.java"), bytes(32, 0x01));
    hashes.put(RelPath.of("src/main/java/B.java"), bytes(32, 0x02));

    Set<String> failed = set("com.example.BetaTest");

    TestGraph g = new TestGraph(tests, hashes, failed, false);
    byte[] bytes = TestGraphCodec.encode(g);
    TestGraph back = TestGraphCodec.decode(bytes);
    assertEquals(g.tests(), back.tests());
    assertEquals(g.hashes().keySet(), back.hashes().keySet());
    assertEquals(g.failedLastRun(), back.failedLastRun());
    assertEquals(g.degraded(), back.degraded());
  }

  @Test
  void integrityFailureRejected() throws IOException {
    TestGraph g = TestGraph.empty();
    byte[] bytes = TestGraphCodec.encode(g);
    bytes[0] ^= 0x55;
    assertThrows(IOException.class, () -> TestGraphCodec.decode(bytes));
  }

  @Test
  void deterministicEncoding() throws IOException {
    Map<String, Set<RelPath>> a = new HashMap<>();
    a.put("Z", set(RelPath.of("z")));
    a.put("A", set(RelPath.of("a")));
    Map<String, Set<RelPath>> b = new HashMap<>();
    b.put("A", set(RelPath.of("a")));
    b.put("Z", set(RelPath.of("z")));
    Map<RelPath, byte[]> h = new HashMap<>();
    h.put(RelPath.of("a"), bytes(32, 1));
    h.put(RelPath.of("z"), bytes(32, 2));

    byte[] e1 = TestGraphCodec.encode(new TestGraph(a, h, Set.of(), false));
    byte[] e2 = TestGraphCodec.encode(new TestGraph(b, h, Set.of(), false));
    assertEquals(Hex.encode(e1), Hex.encode(e2));
  }

  @Test
  void degradedFlagRoundTrip() throws IOException {
    Map<String, Set<RelPath>> tests = new HashMap<>();
    tests.put("com.example.FooTest", set(RelPath.of("src/main/java/A.java")));
    Map<RelPath, byte[]> hashes = new HashMap<>();
    hashes.put(RelPath.of("src/main/java/A.java"), bytes(32, 0x01));
    Set<String> failed = set();

    TestGraph g = new TestGraph(tests, hashes, failed, true);
    byte[] bytes = TestGraphCodec.encode(g);
    TestGraph back = TestGraphCodec.decode(bytes);
    assertTrue(back.degraded());
    assertEquals(g.tests(), back.tests());
  }

  @Test
  void crossModuleTablesRoundTrip() throws IOException {
    Map<String, Set<RelPath>> tests = new HashMap<>();
    tests.put(
        "com.example.BvTest", set(RelPath.of("target/test-classes/com/example/BvTest.class")));
    Map<RelPath, byte[]> hashes = new HashMap<>();
    hashes.put(RelPath.of("target/test-classes/com/example/BvTest.class"), bytes(32, 0x07));

    CrossRef v = new CrossRef("com.example:mod-a", "com.example.a.V");
    CrossRef u = new CrossRef("com.example:mod-a", "com.example.a.U");
    Map<String, Set<CrossRef>> cross = new HashMap<>();
    cross.put("com.example.BvTest", set(v));
    Map<CrossRef, byte[]> upstream = new HashMap<>();
    upstream.put(v, bytes(32, 0x11));
    upstream.put(u, bytes(32, 0x22));

    TestGraph g = new TestGraph(tests, hashes, set(), false, cross, upstream);
    TestGraph back = TestGraphCodec.decode(TestGraphCodec.encode(g));

    assertEquals(g.crossDeps(), back.crossDeps());
    assertEquals(g.upstreamHashes().keySet(), back.upstreamHashes().keySet());
    assertArrayEquals(back.upstreamHashes().get(v), bytes(32, 0x11));
    assertEquals(Set.of("com.example.BvTest"), back.testsImpactedByCrossRefs(set(v)));
    assertTrue(back.testsImpactedByCrossRefs(set(u)).isEmpty());
    assertTrue(
        back.testsImpactedByCrossRefs(Set.of()).isEmpty(),
        "empty changed-ref set short-circuits to no impacted tests");
  }

  @Test
  void legacyShortHeaderIsRejectedNotMisread() {
    // A stale pre-v2 / truncated file must surface as IOException so the caller
    // treats it as cache-absence and bootstraps, never as a misparsed graph.
    byte[] legacy = {'C', 'U', 'L', 'L', 1, 0, 0, 0};
    assertThrows(IOException.class, () -> TestGraphCodec.decode(legacy));
  }

  @Test
  void knownTestsRoundTrip() throws IOException {
    Map<String, Set<RelPath>> tests = new HashMap<>();
    tests.put("com.example.RanTest", set(RelPath.of("src/main/java/A.java")));
    Map<RelPath, byte[]> hashes = new HashMap<>();
    hashes.put(RelPath.of("src/main/java/A.java"), bytes(32, 0x01));

    Set<String> known = set("com.example.RanTest", "com.example.DisabledIT");
    TestGraph g = new TestGraph(tests, hashes, set(), false, Map.of(), Map.of(), known);

    TestGraph back = TestGraphCodec.decode(TestGraphCodec.encode(g));
    assertEquals(known, back.knownTests());
    assertEquals(g.tests(), back.tests());
    // The never-executed candidate is "known" but carries no dependency edge.
    assertTrue(back.knownTests().contains("com.example.DisabledIT"));
    assertFalse(back.tests().contains("com.example.DisabledIT"));
  }

  @Test
  void legacyCacheWithoutKnownTestsBlockDecodesAsEmpty()
      throws IOException, NoSuchAlgorithmException {
    Map<String, Set<RelPath>> tests = new HashMap<>();
    tests.put("com.example.FooTest", set(RelPath.of("src/main/java/A.java")));
    Map<RelPath, byte[]> hashes = new HashMap<>();
    hashes.put(RelPath.of("src/main/java/A.java"), bytes(32, 0x09));
    TestGraph g = new TestGraph(tests, hashes, set(), false);

    // A pre-knownTests (v2) file ends right after upstreamHashes: no trailing
    // count byte at all. The current encoder appends an empty-set block, which
    // is a single varint-zero byte; strip it and re-seal to reproduce a genuine
    // legacy payload, then assert tolerant decode rather than a misparse.
    byte[] full = TestGraphCodec.encode(g);
    byte[] legacyPayload = Arrays.copyOf(full, full.length - 32 - 1);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(legacyPayload);
    byte[] legacy = Arrays.copyOf(legacyPayload, legacyPayload.length + 32);
    System.arraycopy(digest, 0, legacy, legacyPayload.length, 32);

    TestGraph back = TestGraphCodec.decode(legacy);
    assertTrue(back.knownTests().isEmpty(), "absent block decodes as empty, not a misparse");
    assertEquals(g.tests(), back.tests());
    assertEquals(g.hashes().keySet(), back.hashes().keySet());
  }

  @SafeVarargs
  private static <T> Set<T> set(T... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }

  private static byte[] bytes(int n, int fill) {
    byte[] out = new byte[n];
    Arrays.fill(out, (byte) fill);
    return out;
  }
}
