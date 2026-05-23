package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class BannerTest {

  @Test
  void readsBanner() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/banner.txt")) {
      assertNotNull(in);
      String text = new String(in.readAllBytes()).trim();
      assertNotNull(text);
    }
  }
}
