package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.TestGraph;
import io.pjmartos.cull.core.TestGraphCodec;
import io.pjmartos.cull.core.TestSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end of the selection algorithm + binary state file: bootstrap, persist, re-load,
 * unchanged-no-tests, change-one-source-one-test, change-shared-source-many-tests.
 */
class EndToEndSelectionTest {

  @Test
  void simulatedBuildOverThreeRevisions(@TempDir Path tmp) throws IOException {
    Path projectRoot = Files.createDirectory(tmp.resolve("proj"));
    Path mainSrc = Files.createDirectories(projectRoot.resolve("src/main/java"));
    Path testClasses = Files.createDirectories(projectRoot.resolve("target/test-classes"));

    Path foo = Files.writeString(mainSrc.resolve("Foo.java"), "v1-foo");
    Path bar = Files.writeString(mainSrc.resolve("Bar.java"), "v1-bar");

    Set<String> testClassNames = new LinkedHashSet<>(Set.of("FooTest", "BarTest"));
    Files.writeString(testClasses.resolve("FooTest.class"), "FooTest");
    Files.writeString(testClasses.resolve("BarTest.class"), "BarTest");

    TestGraph graph = TestGraph.empty();

    Map<RelPath, byte[]> hashes1 = hashFiles(projectRoot, foo, bar);
    TestSelection.Result r1 =
        TestSelection.select(
            new TestSelection.Inputs(
                graph.hashes(), graph, hashes1.keySet(), hashes1, testClassNames, false, Set.of()));
    assertTrue(r1.bootstrapped);
    assertEquals(testClassNames, r1.selectedTests);

    Map<String, Set<RelPath>> deps = new HashMap<>();
    deps.put("FooTest", Set.of(rel(projectRoot, foo)));
    deps.put("BarTest", Set.of(rel(projectRoot, bar)));
    TestGraph committed = new TestGraph(deps, hashes1, new java.util.LinkedHashSet<>(), false);
    Path stateFile = tmp.resolve("state.bin");
    Files.write(stateFile, TestGraphCodec.encode(committed));

    TestGraph reloaded = TestGraphCodec.decode(Files.readAllBytes(stateFile));

    Map<RelPath, byte[]> hashes2 = hashFiles(projectRoot, foo, bar);
    TestSelection.Result r2 =
        TestSelection.select(
            new TestSelection.Inputs(
                reloaded.hashes(),
                reloaded,
                hashes2.keySet(),
                hashes2,
                testClassNames,
                false,
                Set.of()));
    assertFalse(r2.bootstrapped);
    assertEquals(Set.of(), r2.selectedTests);

    Files.writeString(foo, "v2-foo");
    Map<RelPath, byte[]> hashes3 = hashFiles(projectRoot, foo, bar);
    TestSelection.Result r3 =
        TestSelection.select(
            new TestSelection.Inputs(
                reloaded.hashes(),
                reloaded,
                hashes3.keySet(),
                hashes3,
                testClassNames,
                false,
                Set.of()));
    assertEquals(Set.of("FooTest"), r3.selectedTests);
  }

  @Test
  void sharedDependencyChangeImpactsAllDependents(@TempDir Path tmp) throws IOException {
    Path projectRoot = Files.createDirectory(tmp.resolve("p"));
    Path mainSrc = Files.createDirectories(projectRoot.resolve("src/main/java"));
    Path shared = Files.writeString(mainSrc.resolve("Shared.java"), "v1");

    Map<RelPath, byte[]> hashes1 = hashFiles(projectRoot, shared);
    Map<String, Set<RelPath>> deps = new HashMap<>();
    deps.put("ATest", Set.of(rel(projectRoot, shared)));
    deps.put("BTest", Set.of(rel(projectRoot, shared)));
    deps.put("CTest", Set.of(rel(projectRoot, shared)));
    TestGraph graph = new TestGraph(deps, hashes1, new java.util.LinkedHashSet<>(), false);

    Files.writeString(shared, "v2");
    Map<RelPath, byte[]> hashes2 = hashFiles(projectRoot, shared);

    TestSelection.Result r =
        TestSelection.select(
            new TestSelection.Inputs(
                graph.hashes(),
                graph,
                hashes2.keySet(),
                hashes2,
                Set.of("ATest", "BTest", "CTest"),
                false,
                Set.of()));
    assertEquals(Set.of("ATest", "BTest", "CTest"), r.selectedTests);
  }

  private static Map<RelPath, byte[]> hashFiles(Path base, Path... files) throws IOException {
    Map<RelPath, byte[]> out = new HashMap<>();
    for (Path p : files) {
      out.put(rel(base, p), FileHasher.sha256(p));
    }
    return out;
  }

  private static RelPath rel(Path base, Path p) {
    return RelPath.relativize(base, p);
  }

  @SuppressWarnings("unused")
  private static Set<String> mut(Set<String> s) {
    return new HashSet<>(s);
  }

  @SuppressWarnings("unused")
  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
