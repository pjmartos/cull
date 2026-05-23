package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link TestSelection}, covering bootstrap, impact analysis, and edge cases. */
class TestSelectionTest {

  @Test
  void bootstrapWhenNoPriorState() {
    Set<RelPath> files = set(RelPath.of("a"));
    Map<RelPath, byte[]> hashes = new HashMap<>();
    hashes.put(RelPath.of("a"), b(1));
    TestSelection.Inputs in =
        new TestSelection.Inputs(
            Map.of(), TestGraph.empty(), files, hashes, set("T"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertTrue(r.bootstrapped);
    assertEquals(set("T"), r.selectedTests);
  }

  @Test
  void selectsTestsImpactedByChange() {
    RelPath a = RelPath.of("a");
    RelPath b = RelPath.of("b");
    Map<RelPath, byte[]> prevHashes = new HashMap<>();
    prevHashes.put(a, b(1));
    prevHashes.put(b, b(2));
    Map<String, Set<RelPath>> graph = new HashMap<>();
    graph.put("Ta", set(a));
    graph.put("Tb", set(b));
    TestGraph g = new TestGraph(graph, prevHashes, Set.of(), false);

    Map<RelPath, byte[]> currentHashes = new HashMap<>();
    currentHashes.put(a, b(1));
    currentHashes.put(b, b(99));

    TestSelection.Inputs in =
        new TestSelection.Inputs(
            prevHashes, g, set(a, b), currentHashes, set("Ta", "Tb"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertFalse(r.bootstrapped);
    assertEquals(set("Tb"), r.selectedTests);
    assertTrue(r.changedPaths.contains(b));
  }

  @Test
  void newTestClassAlwaysSelected() {
    RelPath a = RelPath.of("a");
    Map<RelPath, byte[]> hashes = Map.of(a, b(1));
    TestGraph g = new TestGraph(Map.of("Ta", set(a)), hashes, Set.of(), false);
    TestSelection.Inputs in =
        new TestSelection.Inputs(hashes, g, set(a), hashes, set("Ta", "Tnew"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertTrue(r.selectedTests.contains("Tnew"));
    assertFalse(r.selectedTests.contains("Ta"));
  }

  @Test
  void previouslyFailedTestIsRetried() {
    RelPath a = RelPath.of("a");
    Map<RelPath, byte[]> hashes = Map.of(a, b(1));
    TestGraph g = new TestGraph(Map.of("Ta", set(a)), hashes, Set.of("Ta"), false);
    TestSelection.Inputs in =
        new TestSelection.Inputs(hashes, g, set(a), hashes, set("Ta"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertTrue(r.selectedTests.contains("Ta"));
  }

  @Test
  void degradedModeRunsAllOnResourceChange() {
    RelPath res = RelPath.of("src/test/resources/foo.txt");
    RelPath src = RelPath.of("src/main/java/A.java");
    Map<RelPath, byte[]> prev = new HashMap<>();
    prev.put(src, b(1));
    Map<RelPath, byte[]> cur = new HashMap<>();
    cur.put(src, b(1));
    cur.put(res, b(7));
    TestGraph g = new TestGraph(Map.of("Ta", set(src)), prev, Set.of(), false);

    Set<RelPath> roots = set(RelPath.of("src/test/resources"));
    TestSelection.Inputs in =
        new TestSelection.Inputs(prev, g, set(src, res), cur, set("Ta", "Tb"), true, roots);
    TestSelection.Result r = TestSelection.select(in);
    assertEquals(set("Ta", "Tb"), r.selectedTests);
  }

  @Test
  void removedFileMarksDependentTestsImpacted() {
    RelPath kept = RelPath.of("src/main/java/Kept.java");
    RelPath removed = RelPath.of("src/main/java/Removed.java");
    Map<RelPath, byte[]> prev = new HashMap<>();
    prev.put(kept, b(1));
    prev.put(removed, b(2));
    Map<RelPath, byte[]> cur = new HashMap<>();
    cur.put(kept, b(1));
    TestGraph g = new TestGraph(Map.of("Tk", set(kept), "Tr", set(removed)), prev, Set.of(), false);
    TestSelection.Inputs in =
        new TestSelection.Inputs(prev, g, set(kept), cur, set("Tk", "Tr"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertTrue(r.selectedTests.contains("Tr"));
    assertFalse(r.selectedTests.contains("Tk"));
  }

  @Test
  void deletedTestNotReselectedFromFailedList() {
    RelPath a = RelPath.of("src/main/java/A.java");
    Map<RelPath, byte[]> hashes = Map.of(a, b(1));
    TestGraph g =
        new TestGraph(
            Map.of("Talive", set(a), "Tdeleted", set(a)),
            hashes,
            Set.of("Talive", "Tdeleted"),
            false);
    TestSelection.Inputs in =
        new TestSelection.Inputs(hashes, g, set(a), hashes, set("Talive"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertTrue(r.selectedTests.contains("Talive"));
    assertFalse(r.selectedTests.contains("Tdeleted"));
  }

  @Test
  void noChangesProducesEmptySelectionWhenNoFailures() {
    RelPath a = RelPath.of("src/main/java/A.java");
    Map<RelPath, byte[]> hashes = Map.of(a, b(1));
    TestGraph g = new TestGraph(Map.of("Ta", set(a)), hashes, Set.of(), false);
    TestSelection.Inputs in =
        new TestSelection.Inputs(hashes, g, set(a), hashes, set("Ta"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertEquals(Set.of(), r.selectedTests);
    assertFalse(r.bootstrapped);
  }

  @Test
  void degradedModeIgnoresNonResourceChanges() {
    RelPath src = RelPath.of("src/main/java/A.java");
    Map<RelPath, byte[]> prev = Map.of(src, b(1));
    Map<RelPath, byte[]> cur = Map.of(src, b(99));
    TestGraph g = new TestGraph(Map.of("Ta", set(src), "Tb", set()), prev, Set.of(), false);
    Set<RelPath> roots = set(RelPath.of("src/test/resources"));
    TestSelection.Inputs in =
        new TestSelection.Inputs(prev, g, set(src), cur, set("Ta", "Tb"), true, roots);
    TestSelection.Result r = TestSelection.select(in);
    assertTrue(r.selectedTests.contains("Ta"));
    assertFalse(r.selectedTests.contains("Tb"));
  }

  @Test
  void discoveredButNeverExecutedCandidateNotReselectedWhenKnown() {
    RelPath a = RelPath.of("a");
    Map<RelPath, byte[]> hashes = Map.of(a, b(1));
    // "Tskipped" was a candidate last run but never executed (disabled / no
    // docker / abstract base) so it recorded no deps — it is in knownTests but
    // not testToDeps. Before the fix it was re-selected on every build.
    TestGraph g =
        new TestGraph(
            Map.of("Tran", set(a)),
            hashes,
            Set.of(),
            false,
            Map.of(),
            Map.of(),
            set("Tran", "Tskipped"));
    TestSelection.Inputs in =
        new TestSelection.Inputs(
            hashes, g, set(a), hashes, set("Tran", "Tskipped"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertFalse(r.bootstrapped);
    assertEquals(Set.of(), r.selectedTests);
  }

  @Test
  void genuinelyNewCandidateStillSelectedDespiteKnownSet() {
    RelPath a = RelPath.of("a");
    Map<RelPath, byte[]> hashes = Map.of(a, b(1));
    TestGraph g =
        new TestGraph(
            Map.of("Tran", set(a)),
            hashes,
            Set.of(),
            false,
            Map.of(),
            Map.of(),
            set("Tran", "Tskipped"));
    TestSelection.Inputs in =
        new TestSelection.Inputs(
            hashes, g, set(a), hashes, set("Tran", "Tskipped", "Tbrandnew"), false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertTrue(r.selectedTests.contains("Tbrandnew"), "a never-before-seen test must still run");
    assertFalse(r.selectedTests.contains("Tskipped"));
    assertFalse(r.selectedTests.contains("Tran"));
  }

  @Test
  void knownUnexecutedCandidateDoesNotWidenSelectionToEverything() {
    // Spring-petclinic shape: a production change impacts every executed test,
    // and a never-run candidate (a docker-disabled *IntegrationTests) is also a
    // candidate. The disabled one must stay out of the selection so it does not
    // push selected.size() up to currentTestClasses.size() and trip
    // SelectionEngine's "everything selected" wildcard full-suite rerun.
    RelPath prod = RelPath.of("src/main/java/App.java");
    Map<RelPath, byte[]> prev = Map.of(prod, b(1));
    Map<RelPath, byte[]> cur = Map.of(prod, b(2));
    TestGraph g =
        new TestGraph(
            Map.of("AlphaTest", set(prod), "BetaTest", set(prod)),
            prev,
            Set.of(),
            false,
            Map.of(),
            Map.of(),
            set("AlphaTest", "BetaTest", "MySqlIntegrationTests"));
    Set<String> candidates = set("AlphaTest", "BetaTest", "MySqlIntegrationTests");
    TestSelection.Inputs in =
        new TestSelection.Inputs(prev, g, set(prod), cur, candidates, false, Set.of());
    TestSelection.Result r = TestSelection.select(in);
    assertEquals(set("AlphaTest", "BetaTest"), r.selectedTests);
    assertTrue(
        r.selectedTests.size() < candidates.size(),
        "selection stays below the candidate count, so the wildcard rerun is not triggered");
  }

  @SafeVarargs
  private static <T> Set<T> set(T... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }

  private static byte[] b(int seed) {
    byte[] out = new byte[32];
    out[0] = (byte) seed;
    return out;
  }
}
