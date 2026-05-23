package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ForkCountParsingTest {

  @Test
  void zeroLiteral() {
    assertTrue(SelectionEngine.parseForkCountAsZero("0"));
    assertTrue(SelectionEngine.parseForkCountAsZero(" 0 "));
  }

  @Test
  void zeroMultiplier() {
    assertTrue(SelectionEngine.parseForkCountAsZero("0C"));
    assertTrue(SelectionEngine.parseForkCountAsZero("0c"));
    assertTrue(SelectionEngine.parseForkCountAsZero("0.0C"));
  }

  @Test
  void nonZeroValues() {
    assertFalse(SelectionEngine.parseForkCountAsZero("1"));
    assertFalse(SelectionEngine.parseForkCountAsZero("2C"));
    assertFalse(SelectionEngine.parseForkCountAsZero("0.5C"));
    assertFalse(SelectionEngine.parseForkCountAsZero("1.0C"));
  }

  @Test
  void emptyAndNullAndJunk() {
    assertFalse(SelectionEngine.parseForkCountAsZero(null));
    assertFalse(SelectionEngine.parseForkCountAsZero(""));
    assertFalse(SelectionEngine.parseForkCountAsZero("abc"));
    assertFalse(SelectionEngine.parseForkCountAsZero("xC"));
  }
}
