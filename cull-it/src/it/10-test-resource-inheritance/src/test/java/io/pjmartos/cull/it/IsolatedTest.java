package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class IsolatedTest {

  @Test
  void readsOther() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/other.txt")) {
      assertNotNull(in);
      assertNotNull(new String(in.readAllBytes()));
    }
  }
}
