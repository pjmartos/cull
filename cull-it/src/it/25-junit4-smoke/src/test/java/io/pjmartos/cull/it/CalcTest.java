package io.pjmartos.cull.it;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CalcTest {

  @Test
  public void adds() {
    assertEquals(5, new Calc().add(2, 3));
  }
}
