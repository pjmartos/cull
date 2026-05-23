package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WidgetTest {

  @Test
  void widgetHasExpectedSize() {
    assertEquals(TestHelper.expectedSize(), new Widget().size());
  }
}
