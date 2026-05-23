package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.Observations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrphanAndDiscoveryAttributionTest {

  @Test
  void orphanLoadsBeforeDiscoveryEndCountAsDiscovery(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("o.bin");
    ObservationWriter w = new ObservationWriter(obs);
    w.writeForkMetadata("f");
    w.recordOrphanLoad("com/example/Bootstrap");
    w.recordDiscoveryEnd();
    w.recordTestStart("com.example.ATest");
    w.recordTestEnd("com.example.ATest");
    w.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    assertTrue(deps.get("com.example.ATest").contains("com/example/Bootstrap"));
  }

  @Test
  void orphanLoadsAfterDiscoveryEndAttributedToForkTests(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("o.bin");
    ObservationWriter w = new ObservationWriter(obs);
    w.writeForkMetadata("f");
    w.recordDiscoveryEnd();
    w.recordTestStart("com.example.ATest");
    w.recordTestEnd("com.example.ATest");
    w.recordOrphanLoad("com/example/AsyncCleanup");
    w.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    assertTrue(deps.get("com.example.ATest").contains("com/example/AsyncCleanup"));
  }

  @Test
  void orphansDoNotLeakAcrossForks(@TempDir Path tmp) throws IOException {
    Path obs1 = tmp.resolve("a.bin");
    ObservationWriter w1 = new ObservationWriter(obs1);
    w1.writeForkMetadata("forkA");
    w1.recordDiscoveryEnd();
    w1.recordTestStart("com.example.ATest");
    w1.recordTestEnd("com.example.ATest");
    w1.recordOrphanLoad("com/example/InForkAOnly");
    w1.closeQuietly();

    Path obs2 = tmp.resolve("b.bin");
    ObservationWriter w2 = new ObservationWriter(obs2);
    w2.writeForkMetadata("forkB");
    w2.recordDiscoveryEnd();
    w2.recordTestStart("com.example.BTest");
    w2.recordTestEnd("com.example.BTest");
    w2.closeQuietly();

    List<Observations.Fork> forks =
        List.of(Observations.readFile(obs1), Observations.readFile(obs2));
    Map<String, Set<String>> deps = Observations.mergeRuntimeClassDeps(forks);
    assertTrue(deps.get("com.example.ATest").contains("com/example/InForkAOnly"));
    assertFalse(deps.get("com.example.BTest").contains("com/example/InForkAOnly"));
  }
}
