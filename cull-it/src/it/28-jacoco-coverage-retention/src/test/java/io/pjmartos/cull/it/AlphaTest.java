package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AlphaTest {
  @Test
  void exercisesAlpha() {
    // Holds for both the original (1) and the mutated (2) body, so AlphaTest
    // passes in both runs even though its only dependency, Alpha, changed.
    assertTrue(new Alpha().value() >= 1);
  }
}
