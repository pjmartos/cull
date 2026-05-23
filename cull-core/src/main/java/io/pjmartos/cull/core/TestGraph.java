package io.pjmartos.cull.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class TestGraph {

  private final Map<String, Set<RelPath>> testToDeps;
  private final Map<RelPath, Set<String>> reverseIndex;
  private final Map<RelPath, byte[]> hashes;
  private final Set<String> failedLastRun;
  private final boolean degraded;
  private final Map<String, Set<CrossRef>> crossDeps;
  private final Map<CrossRef, byte[]> upstreamHashes;
  // The full set of test classes the prior selection run enumerated as
  // candidates — not just those that recorded a dependency. A candidate that
  // was discovered but never executed (disabled, skipped, abstract base,
  // testcontainers-without-docker) records no deps and so is absent from
  // testToDeps; without this set TestSelection would treat it as brand new and
  // re-select it on every build, defeating culling and tripping the
  // "everything selected" wildcard. Empty for graphs decoded from a
  // pre-knownTests cache, which self-heal on the next commit.
  private final Set<String> knownTests;

  public TestGraph(
      Map<String, Set<RelPath>> testToDeps,
      Map<RelPath, byte[]> hashes,
      Set<String> failedLastRun,
      boolean degraded) {
    this(
        testToDeps,
        hashes,
        failedLastRun,
        degraded,
        Collections.emptyMap(),
        Collections.emptyMap());
  }

  public TestGraph(
      Map<String, Set<RelPath>> testToDeps,
      Map<RelPath, byte[]> hashes,
      Set<String> failedLastRun,
      boolean degraded,
      Map<String, Set<CrossRef>> crossDeps,
      Map<CrossRef, byte[]> upstreamHashes) {
    this(
        testToDeps,
        hashes,
        failedLastRun,
        degraded,
        crossDeps,
        upstreamHashes,
        Collections.emptySet());
  }

  public TestGraph(
      Map<String, Set<RelPath>> testToDeps,
      Map<RelPath, byte[]> hashes,
      Set<String> failedLastRun,
      boolean degraded,
      Map<String, Set<CrossRef>> crossDeps,
      Map<CrossRef, byte[]> upstreamHashes,
      Set<String> knownTests) {
    this.testToDeps = new HashMap<>();
    this.reverseIndex = new HashMap<>();
    for (Map.Entry<String, Set<RelPath>> e : testToDeps.entrySet()) {
      Set<RelPath> deps = new LinkedHashSet<>(e.getValue());
      this.testToDeps.put(e.getKey(), deps);
      for (RelPath p : deps) {
        this.reverseIndex.computeIfAbsent(p, k -> new HashSet<>()).add(e.getKey());
      }
    }
    this.hashes = new HashMap<>(hashes);
    this.failedLastRun = new HashSet<>(failedLastRun);
    this.degraded = degraded;
    this.crossDeps = new HashMap<>();
    for (Map.Entry<String, Set<CrossRef>> e : crossDeps.entrySet()) {
      this.crossDeps.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
    }
    this.upstreamHashes = new HashMap<>(upstreamHashes);
    this.knownTests = new HashSet<>(knownTests);
  }

  public static TestGraph empty() {
    return new TestGraph(
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(), false);
  }

  public Map<String, Set<RelPath>> testToDeps() {
    return Collections.unmodifiableMap(testToDeps);
  }

  public Set<String> testsImpactedBy(Set<RelPath> changedPaths) {
    Set<String> result = new HashSet<>();
    for (RelPath p : changedPaths) {
      Set<String> tests = reverseIndex.get(p);
      if (tests != null) {
        result.addAll(tests);
      }
    }
    return result;
  }

  /** Tests whose recorded cross-module dependency closure intersects {@code changedRefs}. */
  public Set<String> testsImpactedByCrossRefs(Set<CrossRef> changedRefs) {
    Set<String> result = new HashSet<>();
    if (changedRefs.isEmpty()) {
      return result;
    }
    for (Map.Entry<String, Set<CrossRef>> e : crossDeps.entrySet()) {
      for (CrossRef cr : e.getValue()) {
        if (changedRefs.contains(cr)) {
          result.add(e.getKey());
          break;
        }
      }
    }
    return result;
  }

  public Map<RelPath, byte[]> hashes() {
    return Collections.unmodifiableMap(hashes);
  }

  public Set<String> failedLastRun() {
    return Collections.unmodifiableSet(failedLastRun);
  }

  public Set<String> tests() {
    return Collections.unmodifiableSet(testToDeps.keySet());
  }

  /**
   * Test classes the prior run enumerated as candidates, including ones that were discovered but
   * never executed and therefore recorded no dependency. Empty for pre-knownTests caches.
   */
  public Set<String> knownTests() {
    return Collections.unmodifiableSet(knownTests);
  }

  public boolean degraded() {
    return degraded;
  }

  public Map<String, Set<CrossRef>> crossDeps() {
    return Collections.unmodifiableMap(crossDeps);
  }

  public Map<CrossRef, byte[]> upstreamHashes() {
    return Collections.unmodifiableMap(upstreamHashes);
  }
}
