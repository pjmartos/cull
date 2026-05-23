package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JacocoExec} — the dependency-free JaCoCo {@code .exec} reader/merger/writer.
 * Synthetic round-trip and merge cases lock in the binary encoding; the real captured fixture
 * proves byte-fidelity against genuine JaCoCo 0.8.12 output and that compaction bounds file size.
 */
class JacocoExecTest {

  private static final String SAMPLE = "/jacoco/sample-jacoco-0.8.12.exec";

  // -- synthetic round-trip + merge --

  @Nested
  class RoundTrip {

    @Test
    void preservesSessionAndClassData() throws IOException {
      JacocoExec.ExecStore store = new JacocoExec.ExecStore();
      store.addSession(new JacocoExec.SessionInfo("session-a", 100L, 200L));
      store.put(
          new JacocoExec.ExecData(0x1111L, "com/example/Foo", probes(true, false, true, true)));
      store.put(new JacocoExec.ExecData(0x2222L, "com/example/Bar", probes(false, true)));

      JacocoExec.ExecStore back = reread(store);

      assertEquals(2, back.data().size());
      assertArrayEquals(probes(true, false, true, true), find(back, 0x1111L).probes);
      assertEquals("com/example/Bar", find(back, 0x2222L).name);
      // One collapsed session survives.
      assertEquals(1, back.sessions().size());
      assertEquals(100L, back.collapsedSession().start);
      assertEquals(200L, back.collapsedSession().dump);
    }

    @Test
    void allFalseClassesAreOmittedLikeJacoco() throws IOException {
      JacocoExec.ExecStore store = new JacocoExec.ExecStore();
      store.put(new JacocoExec.ExecData(1L, "com/Hit", probes(false, true)));
      store.put(new JacocoExec.ExecData(2L, "com/Miss", probes(false, false)));

      JacocoExec.ExecStore back = reread(store);

      assertEquals(1, back.data().size(), "classes with no hits are not emitted");
      assertNotNull(find(back, 1L));
    }

    @Test
    void emptyStoreWritesAReadableHeaderOnlyFile() throws IOException {
      JacocoExec.ExecStore back = reread(new JacocoExec.ExecStore());
      assertEquals(0, back.data().size());
      assertNull(back.collapsedSession());
    }
  }

  @Nested
  class Merge {

    @Test
    void overlappingClassIdsAreOrMerged() throws IOException {
      byte[] a = bytesOf(store(exec(7L, "com/C", probes(true, false, false, false))));
      byte[] b = bytesOf(store(exec(7L, "com/C", probes(false, false, true, false))));

      // Concatenating two complete exec streams mimics JaCoCo's seed+append dump.
      JacocoExec.ExecStore merged = JacocoExec.read(new ByteArrayInputStream(concat(a, b)));

      assertEquals(1, merged.data().size());
      assertArrayEquals(probes(true, false, true, false), find(merged, 7L).probes);
    }

    @Test
    void disjointClassIdsAreAllRetained() throws IOException {
      byte[] a = bytesOf(store(exec(1L, "com/A", probes(true))));
      byte[] b = bytesOf(store(exec(2L, "com/B", probes(true))));

      JacocoExec.ExecStore merged = JacocoExec.read(new ByteArrayInputStream(concat(a, b)));

      assertEquals(2, merged.data().size());
    }

    @Test
    void conflictingShapeForSameIdIsRejected() throws IOException {
      byte[] a = bytesOf(store(exec(9L, "com/A", probes(true, true))));
      byte[] b = bytesOf(store(exec(9L, "com/DIFFERENT", probes(true, true))));
      assertThrows(
          IOException.class, () -> JacocoExec.read(new ByteArrayInputStream(concat(a, b))));
    }
  }

  // -- real JaCoCo fixture: fidelity + compaction --

  @Nested
  class RealFixture {

    @Test
    void parsesGenuineJacocoOutputAndRetainsBetaCoverage() throws IOException {
      JacocoExec.ExecStore store = JacocoExec.read(new ByteArrayInputStream(sample()));

      JacocoExec.ExecData beta = byName(store, "io/pjmartos/cull/it/Beta");
      assertNotNull(beta, "captured exec must contain the Beta class");
      assertTrue(beta.hasHits(), "Beta must carry covered probes");
    }

    @Test
    void compactionIsIdempotent() throws IOException {
      byte[] once = compactBytes(sample());
      byte[] twice = compactBytes(once);
      assertArrayEquals(once, twice, "compacting an already-compact file is a no-op");
    }

    @Test
    void compactionBoundsSizeAcrossRepeatedAppends() throws IOException {
      byte[] real = sample();
      // Two identical dumps concatenated = the same coverage appended twice.
      byte[] doubled = concat(real, real);

      byte[] compactOnce = compactBytes(real);
      byte[] compactDoubled = compactBytes(doubled);

      assertArrayEquals(
          compactOnce,
          compactDoubled,
          "OR-merging duplicate blocks must yield a baseline that does not grow with run count");
      assertTrue(
          compactDoubled.length <= doubled.length,
          "compacted output must not exceed the accumulated input");
    }
  }

  // -- malformed input falls back via IOException --

  @Nested
  class Malformed {

    @Test
    void unknownVersionThrows() throws IOException {
      byte[] b = sample();
      b[3] = (byte) 0x99; // corrupt the format version high byte
      assertThrows(IOException.class, () -> JacocoExec.read(new ByteArrayInputStream(b)));
    }

    @Test
    void badMagicThrows() throws IOException {
      byte[] b = sample();
      b[1] = (byte) 0x00;
      assertThrows(IOException.class, () -> JacocoExec.read(new ByteArrayInputStream(b)));
    }

    @Test
    void firstBlockNotHeaderThrows() {
      byte[] b = {(byte) JacocoExec.BLOCK_EXECUTIONDATA, 0, 0};
      assertThrows(IOException.class, () -> JacocoExec.read(new ByteArrayInputStream(b)));
    }

    @Test
    void truncatedMidBlockThrows() throws IOException {
      byte[] full = sample();
      byte[] truncated = Arrays.copyOf(full, 10); // cuts into the first session block
      assertThrows(IOException.class, () -> JacocoExec.read(new ByteArrayInputStream(truncated)));
    }
  }

  // -- helpers --

  private static byte[] sample() throws IOException {
    try (InputStream in = JacocoExecTest.class.getResourceAsStream(SAMPLE)) {
      assertNotNull(in, "missing test resource " + SAMPLE);
      return in.readAllBytes();
    }
  }

  private static byte[] compactBytes(byte[] in) throws IOException {
    JacocoExec.ExecStore store = JacocoExec.read(new ByteArrayInputStream(in));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JacocoExec.write(store, out);
    return out.toByteArray();
  }

  private static JacocoExec.ExecStore reread(JacocoExec.ExecStore store) throws IOException {
    return JacocoExec.read(new ByteArrayInputStream(bytesOf(store)));
  }

  private static byte[] bytesOf(JacocoExec.ExecStore store) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JacocoExec.write(store, out);
    return out.toByteArray();
  }

  private static JacocoExec.ExecStore store(JacocoExec.ExecData data) throws IOException {
    JacocoExec.ExecStore s = new JacocoExec.ExecStore();
    s.addSession(new JacocoExec.SessionInfo("s", 1L, 2L));
    s.put(data);
    return s;
  }

  private static JacocoExec.ExecData exec(long id, String name, boolean[] probes) {
    return new JacocoExec.ExecData(id, name, probes);
  }

  private static JacocoExec.ExecData find(JacocoExec.ExecStore store, long id) {
    for (JacocoExec.ExecData d : store.data()) {
      if (d.id == id) {
        return d;
      }
    }
    return null;
  }

  private static JacocoExec.ExecData byName(JacocoExec.ExecStore store, String name) {
    for (JacocoExec.ExecData d : store.data()) {
      if (name.equals(d.name)) {
        return d;
      }
    }
    return null;
  }

  private static boolean[] probes(boolean... values) {
    return values;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
