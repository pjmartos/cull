// Generate a synthetic 300-file project: 250 production classes + 50 test classes.
File mainDir = new File(basedir, "src/main/java/io/pjmartos/cull/it/synth")
File testDir = new File(basedir, "src/test/java/io/pjmartos/cull/it/synth")
mainDir.mkdirs()
testDir.mkdirs()

(1..250).each { i ->
  File f = new File(mainDir, "Prod${i}.java")
  f.text = """package io.pjmartos.cull.it.synth;

public class Prod${i} {
  public int compute() {
    return ${i};
  }
}
"""
}

(1..50).each { i ->
  int prodIdx = ((i - 1) % 250) + 1
  File f = new File(testDir, "Synth${i}Test.java")
  f.text = """package io.pjmartos.cull.it.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class Synth${i}Test {
  @Test
  void runs() {
    assertEquals(${prodIdx}, new Prod${prodIdx}().compute());
  }
}
"""
}

return true
