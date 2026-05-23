package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SecondInheritedTest extends FixtureSupport {

  @Test
  void readsFixtureAlso() throws Exception {
    assertNotNull(readFixture());
  }
}
