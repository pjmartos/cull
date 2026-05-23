package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class StaticTest {

  @Test
  void readsStatic() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/static.txt")) {
      assertNotNull(in);
      assertNotNull(new String(in.readAllBytes()));
    }
  }
}
