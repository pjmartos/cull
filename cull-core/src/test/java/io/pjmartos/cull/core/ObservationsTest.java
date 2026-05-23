package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservationsTest {

  // -- Happy paths --

  @Test
  void readFileWithForkMetadataAndRecords(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("observations-f1.bin");
    writeForkFile(
        f,
        "fork-a",
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_CLASS_LOAD, "com.A", "com/X"),
        rec(Observations.TAG_TEST_END, "com.A", ""));

    Observations.Fork fork = Observations.readFile(f);
    assertEquals("fork-a", fork.forkId);
    assertEquals(3, fork.records.size());
  }

  @Test
  void readFileWithoutForkMetadataDefaultsToUnknown(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("observations-nometa.bin");
    writeRecords(
        f,
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_TEST_END, "com.A", ""));

    Observations.Fork fork = Observations.readFile(f);
    assertEquals("unknown", fork.forkId);
    assertEquals(2, fork.records.size());
  }

  @Test
  void readDirectoryCollectsMultipleFiles(@TempDir Path tmp) throws IOException {
    writeForkFile(
        tmp.resolve("observations-a.bin"), "a", rec(Observations.TAG_TEST_START, "com.A", ""));
    writeForkFile(
        tmp.resolve("observations-b.bin"), "b", rec(Observations.TAG_TEST_START, "com.B", ""));

    List<Observations.Fork> forks = Observations.readDirectory(tmp);
    assertEquals(2, forks.size());
  }

  @Test
  void mergeRuntimeClassDepsAssociatesLoadsWithTests(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeForkFile(
        f,
        "f",
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_CLASS_LOAD, "com.A", "com/X"),
        rec(Observations.TAG_CLASS_LOAD, "com.A", "com/Y"),
        rec(Observations.TAG_TEST_END, "com.A", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)));
    assertEquals(Set.of("com/X", "com/Y"), deps.get("com.A"));
  }

  @Test
  void mergeRuntimeResourceDepsAssociatesReadsWithTests(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeForkFile(
        f,
        "f",
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_RESOURCE_READ, "com.A", "res/config.xml"),
        rec(Observations.TAG_TEST_END, "com.A", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeResourceDeps(List.of(Observations.readFile(f)));
    assertEquals(Set.of("res/config.xml"), deps.get("com.A"));
  }

  @Test
  void orphanResourceAttributedToEveryTestInFork(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeForkFile(
        f,
        "f",
        rec(Observations.TAG_ORPHAN_RESOURCE, null, "res/bootstrap.properties"),
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_RESOURCE_READ, "com.A", "res/a.xml"),
        rec(Observations.TAG_TEST_END, "com.A", ""),
        rec(Observations.TAG_TEST_START, "com.B", ""),
        rec(Observations.TAG_TEST_END, "com.B", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeResourceDeps(List.of(Observations.readFile(f)));
    assertTrue(
        deps.get("com.A").contains("res/bootstrap.properties"),
        "orphan resource read during discovery must reach every fork test");
    assertTrue(deps.get("com.B").contains("res/bootstrap.properties"));
    assertTrue(deps.get("com.A").contains("res/a.xml"), "test-attributed read still kept");
  }

  @Test
  void orphanResourceWithNoTestsInForkIsDroppedNotCrashed(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeForkFile(f, "f", rec(Observations.TAG_ORPHAN_RESOURCE, null, "res/x"));
    Map<String, Set<String>> deps =
        Observations.mergeRuntimeResourceDeps(List.of(Observations.readFile(f)));
    assertTrue(deps.isEmpty(), "no test to attribute to → dropped, no NPE");
  }

  // -- Minority flows: discovery/orphan attribution --

  @Test
  void discoveryClassesAttributedToAllTestsInFork(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("fork-x"),
        rec(Observations.TAG_DISCOVERY_CLASS, null, "com/Boot"),
        rec(Observations.TAG_DISCOVERY_END, null, ""),
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_TEST_END, "com.A", ""),
        rec(Observations.TAG_TEST_START, "com.B", ""),
        rec(Observations.TAG_TEST_END, "com.B", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)));
    assertTrue(deps.get("com.A").contains("com/Boot"));
    assertTrue(deps.get("com.B").contains("com/Boot"));
  }

  @Test
  void orphanBeforeDiscoveryEndTreatedAsDiscovery(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("f"),
        rec(Observations.TAG_ORPHAN_CLASS, null, "com/PreBoot"),
        rec(Observations.TAG_DISCOVERY_END, null, ""),
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_TEST_END, "com.A", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)));
    assertTrue(deps.get("com.A").contains("com/PreBoot"));
  }

  @Test
  void orphanAfterDiscoveryEndAttributedToForkTests(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("f"),
        rec(Observations.TAG_DISCOVERY_END, null, ""),
        rec(Observations.TAG_TEST_START, "com.A", ""),
        rec(Observations.TAG_TEST_END, "com.A", ""),
        rec(Observations.TAG_ORPHAN_CLASS, null, "com/Late"));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)));
    assertTrue(deps.get("com.A").contains("com/Late"));
  }

  // -- Class-window attribution (gate: fork emitted TAG_CLASS_START) --

  @Test
  void classWindowDropsDiscoveryAndScopesLoadsPerClass(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("fork-cw"),
        rec(Observations.TAG_DISCOVERY_CLASS, null, "com/OtherTest"),
        rec(Observations.TAG_DISCOVERY_END, null, ""),
        rec(Observations.TAG_CLASS_START, "com.M", ""),
        rec(Observations.TAG_CLASS_LOAD, "com.M", "com/MOnly"),
        rec(Observations.TAG_CLASS_END, "com.M", ""),
        rec(Observations.TAG_CLASS_START, "com.S", ""),
        rec(Observations.TAG_CLASS_LOAD, "com.S", "com/V"),
        rec(Observations.TAG_CLASS_END, "com.S", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)));
    assertEquals(Set.of("com/MOnly"), deps.get("com.M"));
    assertEquals(Set.of("com/V"), deps.get("com.S"));
    assertFalse(
        deps.get("com.M").contains("com/V"), "a class window must not inherit another's loads");
    assertFalse(
        deps.get("com.M").contains("com/OtherTest"),
        "discovery-phase loads are dropped once a fork uses class windows");
  }

  @Test
  void orphanBetweenClassWindowsGoesToNextWindow(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("f"),
        rec(Observations.TAG_CLASS_START, "com.A", ""),
        rec(Observations.TAG_CLASS_END, "com.A", ""),
        rec(Observations.TAG_ORPHAN_CLASS, null, "com/BetweenSetup"),
        rec(Observations.TAG_CLASS_START, "com.B", ""),
        rec(Observations.TAG_CLASS_END, "com.B", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)));
    assertTrue(deps.get("com.B").contains("com/BetweenSetup"), "next-window setup");
    assertFalse(deps.getOrDefault("com.A", Set.of()).contains("com/BetweenSetup"));
  }

  @Test
  void trailingOrphanWithClassWindowsGoesToLastClass(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("f"),
        rec(Observations.TAG_CLASS_START, "com.A", ""),
        rec(Observations.TAG_CLASS_END, "com.A", ""),
        rec(Observations.TAG_ORPHAN_CLASS, null, "com/LateAsyncCleanup"));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)));
    assertTrue(deps.get("com.A").contains("com/LateAsyncCleanup"));
  }

  @Test
  void legacyFanoutOverrideRestoresFanEvenWithClassWindows(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("f"),
        rec(Observations.TAG_DISCOVERY_CLASS, null, "com/Boot"),
        rec(Observations.TAG_DISCOVERY_END, null, ""),
        rec(Observations.TAG_CLASS_START, "com.A", ""),
        rec(Observations.TAG_CLASS_END, "com.A", ""),
        rec(Observations.TAG_CLASS_START, "com.B", ""),
        rec(Observations.TAG_CLASS_END, "com.B", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(f)), true);
    assertTrue(deps.get("com.A").contains("com/Boot"), "escape hatch restores fan-out");
    assertTrue(deps.get("com.B").contains("com/Boot"));
  }

  @Test
  void classWindowResourceReadsScopedDiscoveryReadsDropped(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    writeRecords(
        f,
        encodeForkMeta("f"),
        rec(Observations.TAG_ORPHAN_RESOURCE, null, "res/bootstrap.properties"),
        rec(Observations.TAG_DISCOVERY_END, null, ""),
        rec(Observations.TAG_CLASS_START, "com.A", ""),
        rec(Observations.TAG_RESOURCE_READ, "com.A", "res/a.xml"),
        rec(Observations.TAG_CLASS_END, "com.A", ""),
        rec(Observations.TAG_CLASS_START, "com.B", ""),
        rec(Observations.TAG_CLASS_END, "com.B", ""));

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeResourceDeps(List.of(Observations.readFile(f)));
    assertEquals(Set.of("res/a.xml"), deps.get("com.A"));
    assertFalse(
        deps.getOrDefault("com.B", Set.of()).contains("res/bootstrap.properties"),
        "pre-first-window resource read is a bootstrap artifact, dropped with class windows");
  }

  // -- Edge cases: empty files, non-matching filenames, corrupt field length --

  @Test
  void emptyFileProducesNoRecords(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("observations-empty.bin");
    Files.write(f, new byte[0]);

    Observations.Fork fork = Observations.readFile(f);
    assertEquals("unknown", fork.forkId);
    assertTrue(fork.records.isEmpty());
  }

  @Test
  void readDirectorySkipsNonObservationFiles(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("not-observations.txt"), "garbage");
    Files.writeString(tmp.resolve("other.bin"), "more garbage");

    List<Observations.Fork> forks = Observations.readDirectory(tmp);
    assertTrue(forks.isEmpty());
  }

  @Test
  void readDirectoryReturnsEmptyForNonexistentDir(@TempDir Path tmp) throws IOException {
    List<Observations.Fork> forks = Observations.readDirectory(tmp.resolve("nope"));
    assertTrue(forks.isEmpty());
  }

  @Test
  void fieldLengthBeyondMaxRejected(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    try (OutputStream out = Files.newOutputStream(f)) {
      out.write(Observations.TAG_CLASS_LOAD);
      writeRawVarint(out, 1); // testId length
      out.write('X');
      writeRawVarint(out, Observations.MAX_OBSERVATION_FIELD_BYTES + 1L);
    }
    assertThrows(IOException.class, () -> Observations.readFile(f));
  }

  @Test
  void truncatedMidFieldThrows(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("obs.bin");
    try (OutputStream out = Files.newOutputStream(f)) {
      out.write(Observations.TAG_CLASS_LOAD);
      writeRawVarint(out, 3); // testId length claims 3 bytes
      out.write('A');
      // only 1 byte written, then EOF — readN should throw
    }
    assertThrows(IOException.class, () -> Observations.readFile(f));
  }

  // -- Failure modes: corrupt files skipped in readDirectory --

  @Test
  void readDirectorySkipsCorruptFiles(@TempDir Path tmp) throws IOException {
    writeForkFile(
        tmp.resolve("observations-valid.bin"), "v", rec(Observations.TAG_TEST_START, "com.V", ""));
    Files.write(tmp.resolve("observations-corrupt.bin"), new byte[] {(byte) 0xFF});

    List<Observations.Fork> forks = Observations.readDirectory(tmp);
    assertEquals(1, forks.size());
    assertEquals("v", forks.get(0).forkId);
  }

  // -- Failure mode: a crashed fork's torn tail must not discard the whole fork --

  @Test
  void crashedForkTailRecordDoesNotDiscardWholeFork(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("observations-crashed.bin");
    try (OutputStream out = Files.newOutputStream(f)) {
      out.write(encodeForkMeta("fork-crash"));
      out.write(rec(Observations.TAG_TEST_START, "com.A", ""));
      out.write(rec(Observations.TAG_CLASS_LOAD, "com.A", "com/Loaded"));
      out.write(rec(Observations.TAG_TEST_END, "com.A", ""));
      // Simulate a JVM crash mid-write: a tag byte with a truncated body.
      out.write(Observations.TAG_CLASS_LOAD);
      writeRawVarint(out, 5); // testId claims 5 bytes
      out.write(new byte[] {'c', 'o'}); // only 2 bytes then EOF
    }

    Observations.Fork fork = Observations.readFile(f);
    assertEquals("fork-crash", fork.forkId);
    assertEquals(3, fork.records.size());

    // readDirectory must retain the surviving records, not discard the fork.
    List<Observations.Fork> forks = Observations.readDirectory(tmp);
    assertEquals(1, forks.size());
    Map<String, Set<String>> deps = Observations.mergeRuntimeClassDeps(forks);
    assertTrue(deps.get("com.A").contains("com/Loaded"));
  }

  @Test
  void oversizeTailFieldLengthKeepsPriorRecords(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("observations-oversize.bin");
    try (OutputStream out = Files.newOutputStream(f)) {
      out.write(encodeForkMeta("fork-z"));
      out.write(rec(Observations.TAG_TEST_START, "com.A", ""));
      out.write(rec(Observations.TAG_CLASS_LOAD, "com.A", "com/Good"));
      // Corrupt trailing record with an absurd payload length.
      out.write(Observations.TAG_CLASS_LOAD);
      writeRawVarint(out, 1);
      out.write('X');
      writeRawVarint(out, Observations.MAX_OBSERVATION_FIELD_BYTES + 1L);
    }

    Observations.Fork fork = Observations.readFile(f);
    assertEquals("fork-z", fork.forkId);
    assertEquals(2, fork.records.size());
    Map<String, Set<String>> deps = Observations.mergeRuntimeClassDeps(List.of(fork));
    assertEquals(Set.of("com/Good"), deps.get("com.A"));
  }

  // -- Helpers --

  /** Encode a single observation record into its binary form. */
  private static byte[] rec(int tag, String testId, String payload) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try {
      buf.write(tag);
      if (testId != null) {
        byte[] tb = testId.getBytes(StandardCharsets.UTF_8);
        writeRawVarint(buf, tb.length);
        buf.write(tb);
      }
      byte[] pb = payload.getBytes(StandardCharsets.UTF_8);
      writeRawVarint(buf, pb.length);
      buf.write(pb);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return buf.toByteArray();
  }

  /** Encode a fork metadata header (TAG_FORK_METADATA + forkId + timestamp). */
  private static byte[] encodeForkMeta(String forkId) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try {
      buf.write(Observations.TAG_FORK_METADATA);
      byte[] id = forkId.getBytes(StandardCharsets.UTF_8);
      writeRawVarint(buf, id.length);
      buf.write(id);
      writeRawVarint(buf, 8);
      buf.write(new byte[8]);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return buf.toByteArray();
  }

  /** Write raw record bytes to a file. */
  private static void writeRecords(Path file, byte[]... records) throws IOException {
    try (OutputStream out = Files.newOutputStream(file)) {
      for (byte[] r : records) {
        out.write(r);
      }
    }
  }

  /** Write a fork file with fork metadata header and records. */
  private static void writeForkFile(Path file, String forkId, byte[]... records)
      throws IOException {
    writeRecords(file, merge(encodeForkMeta(forkId), records));
  }

  private static byte[] merge(byte[] head, byte[]... rest) {
    int total = head.length;
    for (byte[] r : rest) total += r.length;
    byte[] out = new byte[total];
    System.arraycopy(head, 0, out, 0, head.length);
    int off = head.length;
    for (byte[] r : rest) {
      System.arraycopy(r, 0, out, off, r.length);
      off += r.length;
    }
    return out;
  }

  private static void writeRawVarint(OutputStream out, long value) throws IOException {
    while ((value & ~0x7FL) != 0) {
      out.write((int) ((value & 0x7FL) | 0x80L));
      value >>>= 7;
    }
    out.write((int) value);
  }
}
