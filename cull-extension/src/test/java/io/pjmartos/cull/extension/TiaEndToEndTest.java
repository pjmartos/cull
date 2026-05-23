package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.agent.ObservationWriter;
import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.Observations;
import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.TestGraph;
import io.pjmartos.cull.core.TestGraphCodec;
import io.pjmartos.cull.core.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the full pipeline as a single-JVM exercise: agent emits observations to a real file,
 * {@link Observations} reads them back, the resulting graph is persisted via {@link
 * TestGraphCodec}, then {@link TestSelection} runs against a modified workspace.
 */
class TiaEndToEndTest {

  @Test
  void agentObservationsDriveSubsequentSelection(@TempDir Path tmp) throws IOException {
    Path project = Files.createDirectories(tmp.resolve("proj"));
    Path mainJava = Files.createDirectories(project.resolve("src/main/java/com/example"));
    Path testJava = Files.createDirectories(project.resolve("src/test/java/com/example"));

    Path helper = Files.writeString(mainJava.resolve("Helper.java"), "v1");
    Path other = Files.writeString(mainJava.resolve("Other.java"), "v1");
    Path testA = Files.writeString(testJava.resolve("ATest.java"), "ATest-v1");
    Path testB = Files.writeString(testJava.resolve("BTest.java"), "BTest-v1");

    Path obsDir = Files.createDirectories(project.resolve("target/cull/staging"));
    Path obsFile = obsDir.resolve("observations-fork-1.bin");
    ObservationWriter w = new ObservationWriter(obsFile);
    w.writeForkMetadata("fork-1");
    w.recordTestStart("com.example.ATest");
    w.recordClassLoad("com.example.ATest", "com/example/Helper");
    w.recordTestEnd("com.example.ATest");
    w.recordTestStart("com.example.BTest");
    w.recordClassLoad("com.example.BTest", "com/example/Other");
    w.recordTestEnd("com.example.BTest");
    w.closeQuietly();

    List<Observations.Fork> forks = Observations.readDirectory(obsDir);
    Map<String, Set<String>> classDeps = Observations.mergeRuntimeClassDeps(forks);

    Map<String, RelPath> classToPath = new HashMap<>();
    classToPath.put("com/example/Helper", RelPath.relativize(project, helper));
    classToPath.put("com/example/Other", RelPath.relativize(project, other));

    Map<String, Set<RelPath>> deps = new HashMap<>();
    for (Map.Entry<String, Set<String>> e : classDeps.entrySet()) {
      Set<RelPath> paths = new HashSet<>();
      for (String cn : e.getValue()) {
        RelPath rp = classToPath.get(cn);
        if (rp != null) paths.add(rp);
      }
      deps.put(e.getKey(), paths);
    }
    deps.computeIfAbsent("com.example.ATest", k -> new HashSet<>())
        .add(RelPath.relativize(project, testA));
    deps.computeIfAbsent("com.example.BTest", k -> new HashSet<>())
        .add(RelPath.relativize(project, testB));

    Map<RelPath, byte[]> currentHashes = new HashMap<>();
    for (Path p : List.of(helper, other, testA, testB)) {
      currentHashes.put(RelPath.relativize(project, p), FileHasher.sha256(p));
    }

    TestGraph initial = new TestGraph(deps, currentHashes, new java.util.LinkedHashSet<>(), false);
    Path stateFile = tmp.resolve(".state.bin");
    Files.write(stateFile, TestGraphCodec.encode(initial));
    TestGraph reloaded = TestGraphCodec.decode(Files.readAllBytes(stateFile));

    Files.writeString(helper, "v2-modified");
    Map<RelPath, byte[]> hashesAfter = new HashMap<>();
    for (Path p : List.of(helper, other, testA, testB)) {
      hashesAfter.put(RelPath.relativize(project, p), FileHasher.sha256(p));
    }

    TestSelection.Result r =
        TestSelection.select(
            new TestSelection.Inputs(
                reloaded.hashes(),
                reloaded,
                hashesAfter.keySet(),
                hashesAfter,
                Set.of("com.example.ATest", "com.example.BTest"),
                false,
                Set.of()));

    assertEquals(Set.of("com.example.ATest"), r.selectedTests);
    assertTrue(r.changedPaths.contains(RelPath.relativize(project, helper)));
    assertFalse(r.bootstrapped);
  }

  @Test
  void perForkDiscoveryAttributionDoesNotLeakAcrossForks(@TempDir Path tmp) throws IOException {
    Path obsDir = Files.createDirectories(tmp.resolve("staging"));
    Path forkA = obsDir.resolve("observations-A.bin");
    Path forkB = obsDir.resolve("observations-B.bin");

    ObservationWriter wa = new ObservationWriter(forkA);
    wa.writeForkMetadata("fork-A");
    wa.recordDiscoveryClass("org/spring/Bootstrap");
    wa.recordDiscoveryEnd();
    wa.recordTestStart("com.example.ATest");
    wa.recordTestEnd("com.example.ATest");
    wa.closeQuietly();

    ObservationWriter wb = new ObservationWriter(forkB);
    wb.writeForkMetadata("fork-B");
    wb.recordDiscoveryEnd();
    wb.recordTestStart("com.example.BTest");
    wb.recordTestEnd("com.example.BTest");
    wb.closeQuietly();

    List<Observations.Fork> forks = Observations.readDirectory(obsDir);
    Map<String, Set<String>> deps = Observations.mergeRuntimeClassDeps(forks);

    assertTrue(deps.get("com.example.ATest").contains("org/spring/Bootstrap"));
    assertFalse(
        deps.computeIfAbsent("com.example.BTest", k -> new HashSet<>())
            .contains("org/spring/Bootstrap"),
        "discovery in fork A must not leak into fork B");
  }
}
