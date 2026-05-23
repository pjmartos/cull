package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.RelPath;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SessionStateCodec} covering round-trips, flag-bit preservation, magic/version
 * validation, hash-length enforcement, and empty/minimal/large-state edge cases.
 */
class SessionStateCodecTest {

  @Test
  void roundtripsSimpleState() throws IOException {
    Map<RelPath, byte[]> hashes = new LinkedHashMap<>();
    hashes.put(RelPath.of("src/main/java/Foo.java"), bytes(0x01));
    hashes.put(RelPath.of("target/classes/Foo.class"), bytes(0x02));
    Set<String> selected = new LinkedHashSet<>();
    selected.add("com.example.FooTest");
    selected.add("com.example.BarTest");
    Set<RelPath> roots = new LinkedHashSet<>();
    roots.add(RelPath.of("src/main/resources"));

    SessionState s =
        new SessionState(
            "abc123",
            Paths.get("/cache"),
            Paths.get("/cache/.staging-x"),
            Paths.get("/work"),
            Paths.get("/work/target"),
            Paths.get("/work/target/classes"),
            Paths.get("/work/target/test-classes"),
            5,
            true,
            false,
            false,
            selected,
            hashes,
            roots);

    byte[] enc = SessionStateCodec.encode(s);
    SessionState d = SessionStateCodec.decode(enc);

    assertEquals("abc123", d.projectChecksum);
    assertEquals(5, d.retention);
    assertTrue(d.wildcard);
    assertEquals(2, d.selectedTests.size());
    assertTrue(d.selectedTests.contains("com.example.FooTest"));
    assertEquals(2, d.currentHashes.size());
    assertArrayEquals(bytes(0x01), d.currentHashes.get(RelPath.of("src/main/java/Foo.java")));
    assertEquals(1, d.resourceRoots.size());
  }

  @Test
  void preservesEachFlagBit() throws IOException {
    SessionState s = stateWithFlags(false, false, false);
    SessionState d = SessionStateCodec.decode(SessionStateCodec.encode(s));
    assertFalse(d.wildcard);
    assertFalse(d.degraded);
    assertFalse(d.xmlReportsDisabled);

    s = stateWithFlags(true, true, true);
    d = SessionStateCodec.decode(SessionStateCodec.encode(s));
    assertTrue(d.wildcard);
    assertTrue(d.degraded);
    assertTrue(d.xmlReportsDisabled);

    s = stateWithFlags(false, true, false);
    d = SessionStateCodec.decode(SessionStateCodec.encode(s));
    assertFalse(d.wildcard);
    assertTrue(d.degraded);
    assertFalse(d.xmlReportsDisabled);
  }

  @Test
  void rejectsBadMagic() {
    byte[] bad = new byte[16];
    bad[0] = 'X';
    assertThrows(IOException.class, () -> SessionStateCodec.decode(bad));
  }

  @Test
  void rejectsBadVersion() {
    byte[] bad = new byte[16];
    bad[0] = 'C';
    bad[1] = 'U';
    bad[2] = 'L';
    bad[3] = 'S';
    bad[4] = 99;
    assertThrows(IOException.class, () -> SessionStateCodec.decode(bad));
  }

  @Test
  void rejectsHashOfWrongLength() {
    Map<RelPath, byte[]> hashes = new LinkedHashMap<>();
    hashes.put(RelPath.of("a.java"), new byte[7]);
    SessionState s =
        new SessionState(
            "x",
            Paths.get("/c"),
            Paths.get("/s"),
            Paths.get("/p"),
            Paths.get("/p/target"),
            Paths.get("/p/target/classes"),
            Paths.get("/p/target/test-classes"),
            1,
            false,
            false,
            false,
            Set.of(),
            hashes,
            Set.of());
    assertThrows(IllegalArgumentException.class, () -> SessionStateCodec.encode(s));
  }

  @Test
  void roundtripsMinimalEmptyState() throws IOException {
    SessionState s = state(0);
    byte[] enc = SessionStateCodec.encode(s);
    SessionState d = SessionStateCodec.decode(enc);
    assertEquals("empty", d.projectChecksum);
    assertTrue(d.selectedTests.isEmpty());
    assertTrue(d.currentHashes.isEmpty());
    assertTrue(d.resourceRoots.isEmpty());
  }

  @Test
  void roundtripsWithEmptyStrings() throws IOException {
    Map<RelPath, byte[]> hashes = new LinkedHashMap<>();
    hashes.put(RelPath.of("e"), new byte[32]);
    Set<String> selected = new LinkedHashSet<>();
    selected.add("e");
    Set<RelPath> roots = new LinkedHashSet<>();
    roots.add(RelPath.of("r"));

    SessionState s =
        new SessionState(
            "",
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            0,
            false,
            false,
            false,
            selected,
            hashes,
            roots);
    byte[] enc = SessionStateCodec.encode(s);
    SessionState d = SessionStateCodec.decode(enc);
    assertEquals("", d.projectChecksum);
    assertTrue(d.selectedTests.contains("e"));
    assertTrue(d.currentHashes.containsKey(RelPath.of("e")));
    assertTrue(d.resourceRoots.contains(RelPath.of("r")));
  }

  @Test
  void roundtripsMaxFlagBits() throws IOException {
    SessionState s = stateWithFlags(true, true, true);
    byte[] enc = SessionStateCodec.encode(s);
    SessionState d = SessionStateCodec.decode(enc);
    assertTrue(d.wildcard);
    assertTrue(d.degraded);
    assertTrue(d.xmlReportsDisabled);
  }

  @Test
  void roundtripsMixedPathSlashes() throws IOException {
    SessionState s =
        new SessionState(
            "chk",
            Paths.get("/cache/dir"),
            Paths.get("/staging/dir"),
            Paths.get("/project/dir"),
            Paths.get("/project/dir/target"),
            Paths.get("/project/dir/target/classes"),
            Paths.get("/project/dir/target/test-classes"),
            3,
            false,
            false,
            false,
            Set.of(),
            Map.of(),
            Set.of());
    byte[] enc = SessionStateCodec.encode(s);
    SessionState d = SessionStateCodec.decode(enc);
    assertEquals(3, d.retention);
    // Paths may get normalised; check that the final path segments match
    assertTrue(d.cacheBaseDir.toString().replace('\\', '/').contains("cache/dir"));
    assertTrue(d.projectBasedir.toString().replace('\\', '/').contains("project/dir"));
  }

  @Test
  void encodeDoesNotThrowOnMaxRetention() {
    SessionState s =
        new SessionState(
            "x",
            Paths.get("/c"),
            Paths.get("/s"),
            Paths.get("/p"),
            Paths.get("/p/target"),
            Paths.get("/p/target/classes"),
            Paths.get("/p/target/test-classes"),
            99999,
            false,
            false,
            false,
            Set.of(),
            Map.of(),
            Set.of());
    assertDoesNotThrow(() -> SessionStateCodec.encode(s));
  }

  @Test
  void roundtripsWithManyHashes() throws IOException {
    Map<RelPath, byte[]> hashes = new LinkedHashMap<>();
    for (int i = 0; i < 100; i++) {
      byte[] h = new byte[32];
      h[0] = (byte) i;
      hashes.put(RelPath.of("file" + i + ".java"), h);
    }
    SessionState s =
        new SessionState(
            "many",
            Paths.get("/c"),
            Paths.get("/s"),
            Paths.get("/p"),
            Paths.get("/p/target"),
            Paths.get("/p/target/classes"),
            Paths.get("/p/target/test-classes"),
            5,
            false,
            false,
            false,
            Set.of(),
            hashes,
            Set.of());
    byte[] enc = SessionStateCodec.encode(s);
    SessionState d = SessionStateCodec.decode(enc);
    assertEquals(100, d.currentHashes.size());
  }

  @Test
  void roundtripsV2CrossModuleFields() throws IOException {
    Map<String, String> siblings = new LinkedHashMap<>();
    siblings.put("com.example:mod-a", "/work/a/target/classes");
    siblings.put("com.example:mod-b", "/work/b/target/classes");

    SessionState s =
        new SessionState(
            "csum",
            Paths.get("/c"),
            Paths.get("/s"),
            Paths.get("/p"),
            Paths.get("/p/target"),
            Paths.get("/p/target/classes"),
            Paths.get("/p/target/test-classes"),
            5,
            false,
            false,
            false,
            Set.of(),
            Map.of(),
            Set.of(),
            siblings,
            "FULL");

    SessionState d = SessionStateCodec.decode(SessionStateCodec.encode(s));

    assertEquals("FULL", d.crossModule, "cross-module mode must survive the v2 handoff");
    assertEquals(2, d.reactorSiblings.size());
    assertEquals("/work/a/target/classes", d.reactorSiblings.get("com.example:mod-a"));
    assertEquals("/work/b/target/classes", d.reactorSiblings.get("com.example:mod-b"));
  }

  @Test
  void legacyCtorDefaultsCrossModuleToOffAndEmptySiblings() throws IOException {
    SessionState d = SessionStateCodec.decode(SessionStateCodec.encode(state(0)));
    assertEquals("off", d.crossModule);
    assertTrue(d.reactorSiblings.isEmpty());
  }

  @Test
  void roundtripsCandidateTests() throws IOException {
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add("com.example.FooTest");
    candidates.add("com.example.MySqlIntegrationTests");

    SessionState s =
        new SessionState(
            "csum",
            Paths.get("/c"),
            Paths.get("/s"),
            Paths.get("/p"),
            Paths.get("/p/target"),
            Paths.get("/p/target/classes"),
            Paths.get("/p/target/test-classes"),
            5,
            false,
            false,
            false,
            Set.of(),
            Map.of(),
            Set.of(),
            Map.of(),
            "OFF",
            candidates);

    SessionState d = SessionStateCodec.decode(SessionStateCodec.encode(s));

    assertEquals(2, d.candidateTests.size());
    assertTrue(d.candidateTests.contains("com.example.FooTest"));
    assertTrue(
        d.candidateTests.contains("com.example.MySqlIntegrationTests"),
        "a never-executed candidate must survive the handoff so it is persisted as known");
  }

  @Test
  void legacyCtorDefaultsCandidateTestsToEmpty() throws IOException {
    SessionState d = SessionStateCodec.decode(SessionStateCodec.encode(state(0)));
    assertTrue(d.candidateTests.isEmpty());
  }

  private static SessionState state(int seed) {
    return new SessionState(
        "empty",
        Paths.get("/c"),
        Paths.get("/s"),
        Paths.get("/p"),
        Paths.get("/p/target"),
        Paths.get("/p/target/classes"),
        Paths.get("/p/target/test-classes"),
        0,
        false,
        false,
        false,
        Set.of(),
        Map.of(),
        Set.of());
  }

  private static SessionState stateWithFlags(boolean wild, boolean deg, boolean xml) {
    return new SessionState(
        "x",
        Paths.get("/c"),
        Paths.get("/s"),
        Paths.get("/p"),
        Paths.get("/p/target"),
        Paths.get("/p/target/classes"),
        Paths.get("/p/target/test-classes"),
        1,
        wild,
        deg,
        xml,
        Set.of(),
        Map.of(),
        Set.of());
  }

  private static byte[] bytes(int seed) {
    byte[] b = new byte[32];
    for (int i = 0; i < 32; i++) {
      b[i] = (byte) ((seed + i) & 0xFF);
    }
    return b;
  }
}
