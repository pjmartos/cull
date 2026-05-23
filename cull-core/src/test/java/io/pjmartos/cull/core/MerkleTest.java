package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MerkleTest {

  @Test
  void emptyGroupHashesToSha256OfEmpty() {
    byte[] h = Merkle.hashGroup(List.of());
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hex.encode(h));
  }

  @Test
  void distinctTuplesHashDifferently() throws IOException {
    byte[] h1 = Merkle.hashGroup(List.of(Merkle.tupleFromStrings("a", "b")));
    byte[] h2 = Merkle.hashGroup(List.of(Merkle.tupleFromStrings("ab", "")));
    assertNotEquals(Hex.encode(h1), Hex.encode(h2));
  }

  @Test
  void tupleFramingDistinguishesAdjacency() throws IOException {
    byte[] t1 = Merkle.tuple("ab".getBytes(), "c".getBytes());
    byte[] t2 = Merkle.tuple("a".getBytes(), "bc".getBytes());
    assertNotEquals(Hex.encode(t1), Hex.encode(t2));
  }

  @Test
  void rootIsStableAcrossInvocations() {
    byte[] h1 = Merkle.sha256("alpha".getBytes(), "beta".getBytes());
    byte[] h2 = Merkle.sha256("alpha".getBytes(), "beta".getBytes());
    assertEquals(Hex.encode(h1), Hex.encode(h2));
  }
}
