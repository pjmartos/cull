package io.pjmartos.cull.it;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class CalcTest {

  @Test
  public void adds() {
    assertEquals(new Calc().add(2, 3), 5);
  }
}
