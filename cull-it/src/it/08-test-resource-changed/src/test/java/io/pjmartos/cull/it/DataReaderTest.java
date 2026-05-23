package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class DataReaderTest {

  @Test
  void readsTestResource() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/data.txt")) {
      assertNotNull(in);
      String content = new String(in.readAllBytes()).trim();
      assertNotNull(content);
    }
  }
}
