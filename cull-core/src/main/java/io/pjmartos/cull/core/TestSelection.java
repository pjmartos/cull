package io.pjmartos.cull.core;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TestSelection {

  public static final class Inputs {
    public final Map<RelPath, byte[]> prevHashes;
    public final TestGraph prevGraph;
    public final Set<RelPath> currentFiles;
    public final Map<RelPath, byte[]> currentHashes;
    public final Set<String> currentTestClasses;
    public final boolean degraded;
    public final Set<RelPath> resourceRoots;

    public Inputs(
        Map<RelPath, byte[]> prevHashes,
        TestGraph prevGraph,
        Set<RelPath> currentFiles,
        Map<RelPath, byte[]> currentHashes,
        Set<String> currentTestClasses,
        boolean degraded,
        Set<RelPath> resourceRoots) {
      this.prevHashes = Objects.requireNonNull(prevHashes);
      this.prevGraph = Objects.requireNonNull(prevGraph);
      this.currentFiles = Objects.requireNonNull(currentFiles);
      this.currentHashes = Objects.requireNonNull(currentHashes);
      this.currentTestClasses = Objects.requireNonNull(currentTestClasses);
      this.degraded = degraded;
      this.resourceRoots = resourceRoots == null ? Set.of() : resourceRoots;
    }
  }

  public static final class Result {
    public final Set<String> selectedTests;
    public final Set<RelPath> changedPaths;
    public final boolean bootstrapped;

    public Result(Set<String> selectedTests, Set<RelPath> changedPaths, boolean bootstrapped) {
      this.selectedTests = selectedTests;
      this.changedPaths = changedPaths;
      this.bootstrapped = bootstrapped;
    }
  }

  private TestSelection() {}

  public static Result select(Inputs in) {
    Set<RelPath> changed = new HashSet<>();
    for (RelPath p : in.currentFiles) {
      byte[] prev = in.prevHashes.get(p);
      byte[] cur = in.currentHashes.get(p);
      if (prev == null || cur == null || !equalBytes(prev, cur)) {
        changed.add(p);
      }
    }
    for (RelPath p : in.prevHashes.keySet()) {
      if (!in.currentFiles.contains(p)) {
        changed.add(p);
      }
    }

    boolean bootstrap = in.prevGraph.tests().isEmpty() && in.prevHashes.isEmpty();
    if (bootstrap) {
      return new Result(new HashSet<>(in.currentTestClasses), changed, true);
    }

    if (in.degraded && intersects(changed, in.resourceRoots)) {
      return new Result(new HashSet<>(in.currentTestClasses), changed, false);
    }

    Set<String> selected = new HashSet<>(in.prevGraph.testsImpactedBy(changed));
    selected.retainAll(in.currentTestClasses);

    // "Known" means the prior run was aware of the candidate, not that it
    // recorded a dependency. A discovered-but-never-executed test (disabled,
    // skipped, abstract base, no-docker) has no testToDeps entry but is in
    // knownTests; without the union it would be re-selected every build,
    // permanently, and inflate the selection until SelectionEngine's
    // "everything selected" check forces a full-suite rerun.
    Set<String> previouslyKnown = new HashSet<>(in.prevGraph.tests());
    previouslyKnown.addAll(in.prevGraph.knownTests());
    for (String t : in.currentTestClasses) {
      if (!previouslyKnown.contains(t)) {
        selected.add(t);
      }
    }

    for (String t : in.prevGraph.failedLastRun()) {
      if (in.currentTestClasses.contains(t)) {
        selected.add(t);
      }
    }
    return new Result(selected, changed, false);
  }

  private static boolean intersects(Set<RelPath> a, Set<RelPath> roots) {
    for (RelPath p : a) {
      for (RelPath r : roots) {
        if (p.value().startsWith(r.value() + "/") || p.value().equals(r.value())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean equalBytes(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
