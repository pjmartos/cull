package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CorruptStateFallbackTest {

  @Test
  void truncatedFileFailsIntegrity() {
    byte[] truncated = new byte[16];
    assertThrows(IOException.class, () -> TestGraphCodec.decode(truncated));
  }

  @Test
  void byteSwappedTrailerFailsIntegrity() throws IOException {
    TestGraph empty = TestGraph.empty();
    byte[] encoded = TestGraphCodec.encode(empty);
    encoded[encoded.length - 1] ^= 0x01;
    assertThrows(IOException.class, () -> TestGraphCodec.decode(encoded));
  }

  @Test
  void selectionFallsBackToBootstrapOnEmptyGraph() {
    TestGraph empty = TestGraph.empty();
    Set<RelPath> files = new HashSet<>();
    files.add(RelPath.of("a"));
    Set<String> tests = new HashSet<>();
    tests.add("Ta");
    java.util.Map<RelPath, byte[]> hashes = new java.util.HashMap<>();
    hashes.put(RelPath.of("a"), new byte[32]);
    TestSelection.Result r =
        TestSelection.select(
            new TestSelection.Inputs(empty.hashes(), empty, files, hashes, tests, false, Set.of()));
    assertTrue(r.bootstrapped);
    assertTrue(r.selectedTests.contains("Ta"));
  }
}
