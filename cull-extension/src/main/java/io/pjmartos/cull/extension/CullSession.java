package io.pjmartos.cull.extension;

import io.pjmartos.cull.core.ClassFileMetadata;
import io.pjmartos.cull.core.ClassFileReader;
import io.pjmartos.cull.core.CrossRef;
import io.pjmartos.cull.core.Observations;
import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.SourceWalker;
import io.pjmartos.cull.core.TestGraph;
import io.pjmartos.cull.core.TestGraphCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.maven.execution.BuildFailure;
import org.apache.maven.execution.BuildSummary;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

public final class CullSession {

  private final MavenProject project;
  private final Path projectBasedir;
  private final Path buildDirectory;
  private final Path mainClassesDir;
  private final Path testClassesDir;
  private final Path cacheBaseDir;
  private final Path stagingDir;
  private final String projectChecksum;
  private final TestGraph priorGraph;
  private final Map<RelPath, byte[]> currentHashes;
  private final Set<String> selectedTests;
  // The full enumerated candidate universe for this run, persisted into the
  // committed graph as knownTests so discovered-but-never-executed tests are
  // not re-selected on every future build. Empty for legacy callers.
  private final Set<String> candidateTests;
  private final Set<RelPath> sourceRoots;
  private final boolean wildcard;
  private final AtomicReference<FileLockHandle> lock;
  private final boolean degraded;
  private final boolean xmlReportsDisabled;
  private final int retention;
  // Set once via enableCrossModule before the session is handed off; read at
  // commit time, which may run on a different thread (afterSessionEnd).
  private volatile Map<String, Path> reactorSiblings = Collections.emptyMap();
  private volatile boolean crossModuleEnabled;

  static final String AGENT_DEGRADED_MARKER = "agent-degraded.marker";

  public CullSession(
      MavenProject project,
      Path cacheBaseDir,
      Path stagingDir,
      String projectChecksum,
      TestGraph priorGraph,
      Map<RelPath, byte[]> currentHashes,
      Set<String> selectedTests,
      Set<RelPath> sourceRoots,
      boolean wildcard,
      FileLockHandle lock,
      boolean degraded,
      int retention) {
    this(
        project,
        cacheBaseDir,
        stagingDir,
        projectChecksum,
        priorGraph,
        currentHashes,
        selectedTests,
        Collections.emptySet(),
        sourceRoots,
        wildcard,
        lock,
        degraded,
        retention);
  }

  public CullSession(
      MavenProject project,
      Path cacheBaseDir,
      Path stagingDir,
      String projectChecksum,
      TestGraph priorGraph,
      Map<RelPath, byte[]> currentHashes,
      Set<String> selectedTests,
      Set<String> candidateTests,
      Set<RelPath> sourceRoots,
      boolean wildcard,
      FileLockHandle lock,
      boolean degraded,
      int retention) {
    this.project = project;
    this.projectBasedir = Path.of(project.getBasedir().getAbsolutePath()).normalize();
    this.buildDirectory = Path.of(project.getBuild().getDirectory());
    this.mainClassesDir = Path.of(project.getBuild().getOutputDirectory());
    this.testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
    this.cacheBaseDir = cacheBaseDir;
    this.stagingDir = stagingDir;
    this.projectChecksum = projectChecksum;
    this.priorGraph = priorGraph;
    this.currentHashes = currentHashes;
    this.selectedTests = selectedTests;
    this.candidateTests = new HashSet<>(candidateTests);
    this.sourceRoots = sourceRoots;
    this.wildcard = wildcard;
    this.lock = new AtomicReference<>(lock);
    this.degraded = degraded;
    this.xmlReportsDisabled = surefirePluginXmlDisabled(project);
    this.retention = retention;
  }

  CullSession(
      Path projectBasedir,
      Path buildDirectory,
      Path mainClassesDir,
      Path testClassesDir,
      Path cacheBaseDir,
      Path stagingDir,
      String projectChecksum,
      TestGraph priorGraph,
      Map<RelPath, byte[]> currentHashes,
      Set<String> selectedTests,
      Set<RelPath> sourceRoots,
      boolean wildcard,
      boolean degraded,
      boolean xmlReportsDisabled,
      int retention) {
    this(
        projectBasedir,
        buildDirectory,
        mainClassesDir,
        testClassesDir,
        cacheBaseDir,
        stagingDir,
        projectChecksum,
        priorGraph,
        currentHashes,
        selectedTests,
        Collections.emptySet(),
        sourceRoots,
        wildcard,
        degraded,
        xmlReportsDisabled,
        retention);
  }

  CullSession(
      Path projectBasedir,
      Path buildDirectory,
      Path mainClassesDir,
      Path testClassesDir,
      Path cacheBaseDir,
      Path stagingDir,
      String projectChecksum,
      TestGraph priorGraph,
      Map<RelPath, byte[]> currentHashes,
      Set<String> selectedTests,
      Set<String> candidateTests,
      Set<RelPath> sourceRoots,
      boolean wildcard,
      boolean degraded,
      boolean xmlReportsDisabled,
      int retention) {
    this.project = null;
    this.projectBasedir = projectBasedir;
    this.buildDirectory = buildDirectory;
    this.mainClassesDir = mainClassesDir;
    this.testClassesDir = testClassesDir;
    this.cacheBaseDir = cacheBaseDir;
    this.stagingDir = stagingDir;
    this.projectChecksum = projectChecksum;
    this.priorGraph = priorGraph;
    this.currentHashes = currentHashes;
    this.selectedTests = selectedTests;
    this.candidateTests = new HashSet<>(candidateTests);
    this.sourceRoots = sourceRoots;
    this.wildcard = wildcard;
    this.lock = new AtomicReference<>(null);
    this.degraded = degraded;
    this.xmlReportsDisabled = xmlReportsDisabled;
    this.retention = retention;
  }

  public static CullSession fromState(SessionState state) {
    Path stateFile = state.cacheBaseDir.resolve(state.projectChecksum + ".state.bin");
    TestGraph priorGraph = TestGraph.empty();
    if (Files.isRegularFile(stateFile)) {
      try {
        priorGraph = TestGraphCodec.decode(Files.readAllBytes(stateFile));
      } catch (IOException ignored) {
        priorGraph = TestGraph.empty();
      }
    }
    CullSession cs =
        new CullSession(
            state.projectBasedir,
            state.buildDirectory,
            state.mainClassesDir,
            state.testClassesDir,
            state.cacheBaseDir,
            state.stagingDir,
            state.projectChecksum,
            priorGraph,
            state.currentHashes,
            state.selectedTests,
            state.candidateTests,
            state.resourceRoots,
            state.wildcard,
            state.degraded,
            state.xmlReportsDisabled,
            state.retention);
    Map<String, Path> siblings = new HashMap<>();
    for (Map.Entry<String, String> e : state.reactorSiblings.entrySet()) {
      siblings.put(e.getKey(), Path.of(e.getValue()));
    }
    cs.enableCrossModule(
        siblings, !"OFF".equalsIgnoreCase(state.crossModule) && !siblings.isEmpty());
    return cs;
  }

  void enableCrossModule(Map<String, Path> siblings, boolean enabled) {
    this.reactorSiblings = siblings == null ? Collections.emptyMap() : siblings;
    this.crossModuleEnabled = enabled && !this.reactorSiblings.isEmpty();
  }

  public Set<String> selectedTests() {
    return selectedTests;
  }

  public boolean wildcard() {
    return wildcard;
  }

  public Path stagingDir() {
    return stagingDir;
  }

  public String projectChecksum() {
    return projectChecksum;
  }

  public boolean allTestsPassed(MavenSession session) {
    if (project != null) {
      BuildSummary summary = session.getResult().getBuildSummary(project);
      if (summary instanceof BuildFailure) {
        return false;
      }
    }
    if (session.getResult().hasExceptions()) {
      return false;
    }
    return testReportsAllPass();
  }

  boolean testReportsAllPass() {
    if (xmlReportsDisabled) {
      System.err.println(
          "[cull] WARNING: <disableXmlReport>true</disableXmlReport> detected for "
              + projectLabel()
              + "; selection still applied but commit skipped (no way to verify pass/fail).");
      return false;
    }
    Path surefire = buildDirectory.resolve("surefire-reports");
    Path failsafe = buildDirectory.resolve("failsafe-reports");
    SurefireReports.Outcome outcome = SurefireReports.scan(surefire, failsafe);
    if (outcome == SurefireReports.Outcome.HAS_FAILURE) {
      return false;
    }
    if (outcome == SurefireReports.Outcome.NO_REPORTS) {
      return selectedTests.isEmpty();
    }
    if (!wildcard) {
      Set<String> reported = SurefireReports.reportedClassNames(surefire, failsafe);
      for (String selected : selectedTests) {
        if (!reported.contains(selected)) {
          return false;
        }
      }
    }
    return true;
  }

  private String projectLabel() {
    return project == null ? buildDirectory.toString() : project.getArtifactId();
  }

  private static boolean surefirePluginXmlDisabled(MavenProject p) {
    if (p == null || p.getBuild() == null) return false;
    org.apache.maven.model.Plugin plugin =
        p.getBuild().getPluginsAsMap().get("org.apache.maven.plugins:maven-surefire-plugin");
    if (plugin == null) return false;
    Object cfg = plugin.getConfiguration();
    if (!(cfg instanceof org.codehaus.plexus.util.xml.Xpp3Dom)) return false;
    org.codehaus.plexus.util.xml.Xpp3Dom dom = (org.codehaus.plexus.util.xml.Xpp3Dom) cfg;
    org.codehaus.plexus.util.xml.Xpp3Dom child = dom.getChild("disableXmlReport");
    return child != null && "true".equalsIgnoreCase(String.valueOf(child.getValue()).trim());
  }

  public void commit() {
    try {
      doCommit();
    } catch (IOException e) {
      throw new IllegalStateException("commit failed", e);
    } finally {
      cleanup();
    }
  }

  public void rollback() {
    cleanup();
  }

  // h.release() closes the lock
  @SuppressWarnings("PMD.CloseResource")
  private void cleanup() {
    try {
      deleteRecursive(stagingDir);
    } catch (IOException ignored) {
      // best effort
    }
    FileLockHandle h = lock.getAndSet(null);
    if (h != null) {
      h.release();
    }
  }

  private void doCommit() throws IOException {
    List<Observations.Fork> forks = Observations.readDirectory(stagingDir);
    boolean legacyFanout = Boolean.getBoolean("cull.attribution.legacyFanout");
    Map<String, Set<String>> runtimeClasses =
        Observations.mergeRuntimeClassDeps(forks, legacyFanout);
    Map<String, Set<String>> runtimeResources =
        Observations.mergeRuntimeResourceDeps(forks, legacyFanout);

    Map<String, Set<RelPath>> tests = new HashMap<>();
    for (Map.Entry<String, Set<RelPath>> e : priorGraph.testToDeps().entrySet()) {
      tests.put(e.getKey(), new HashSet<>(e.getValue()));
    }

    Map<String, RelPath> classNameToPath = buildClassToPathIndex();
    Map<String, Set<String>> staticRefsCache = new HashMap<>();

    for (Map.Entry<String, Set<String>> e : runtimeClasses.entrySet()) {
      String testId = e.getKey();
      // A test always depends on its own class — and, through the static
      // closure, on its supertypes (e.g. an abstract base test class) and
      // referenced collaborators. The test class is loaded during JUnit
      // discovery and not re-defined inside its window, so it is absent from
      // the windowed runtime loads; seed it explicitly so self-mutation and
      // base-class changes stay sound without resurrecting discovery fan-out.
      Set<String> seedClassNames = new HashSet<>(e.getValue());
      seedClassNames.add(testId);
      Set<String> closureClassNames =
          transitiveClosure(seedClassNames, classNameToPath, staticRefsCache);
      Set<RelPath> deps = tests.computeIfAbsent(testId, k -> new HashSet<>());
      for (String cn : closureClassNames) {
        RelPath p = classNameToPath.get(cn);
        if (p != null) {
          deps.add(p);
        }
      }
    }
    // Resource reads from ClassLoader/Class.getResource* (instrumented by IoHooks)
    // may include classpath-only paths inside dependency JARs that cannot map to a
    // project source file. Those entries produce a null rp from relativizeResource()
    // and are harmlessly skipped — only project-source resources are tracked.
    for (Map.Entry<String, Set<String>> e : runtimeResources.entrySet()) {
      String testId = e.getKey();
      Set<RelPath> deps = tests.computeIfAbsent(testId, k -> new HashSet<>());
      for (String r : e.getValue()) {
        RelPath rp = relativizeResource(r);
        if (rp != null && currentHashes.containsKey(rp)) {
          deps.add(rp);
        }
      }
    }

    Set<String> failed =
        SurefireReports.failedClassNames(
            buildDirectory.resolve("surefire-reports"), buildDirectory.resolve("failsafe-reports"));

    Map<String, Set<CrossRef>> crossDeps = new HashMap<>();
    Map<CrossRef, byte[]> upstreamSnapshot = new HashMap<>();
    if (crossModuleEnabled) {
      ReactorSiblings.Index idx = ReactorSiblings.index(reactorSiblings);
      if (idx.usable()) {
        for (Map.Entry<String, Set<CrossRef>> e : priorGraph.crossDeps().entrySet()) {
          crossDeps.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        Map<String, String> owner = idx.ownerByClass;
        for (Map.Entry<String, Set<String>> e : runtimeClasses.entrySet()) {
          Set<CrossRef> refs = crossDeps.computeIfAbsent(e.getKey(), k -> new HashSet<>());
          Set<String> closure = transitiveClosure(e.getValue(), classNameToPath, staticRefsCache);
          for (String cn : e.getValue()) {
            addCrossRefIfSibling(cn, classNameToPath, owner, refs);
          }
          for (String localCn : closure) {
            Set<String> srefs = staticRefsCache.get(localCn);
            if (srefs == null) {
              continue;
            }
            for (String r : srefs) {
              addCrossRefIfSibling(r, classNameToPath, owner, refs);
            }
          }
        }
        crossDeps.values().removeIf(Set::isEmpty);
        Set<CrossRef> used = new HashSet<>();
        for (Set<CrossRef> v : crossDeps.values()) {
          used.addAll(v);
        }
        // Hash only the depended-on set: this module's own snapshot of the
        // sibling classes it depends on, not the entire upstream output.
        upstreamSnapshot.putAll(ReactorSiblings.hash(idx, used));
      }
    }

    boolean stillDegraded = this.degraded || sawDegradedMarkerInStaging();
    // Persist the full candidate universe (union with anything carried over)
    // so a discovered-but-never-executed test stays "known" next run instead
    // of being perpetually re-selected.
    Set<String> knownTests = new HashSet<>(candidateTests);
    knownTests.addAll(tests.keySet());
    knownTests.addAll(priorGraph.knownTests());
    TestGraph newGraph =
        new TestGraph(
            tests, currentHashes, failed, stillDegraded, crossDeps, upstreamSnapshot, knownTests);
    byte[] encoded = TestGraphCodec.encode(newGraph);

    Files.createDirectories(stagingDir);
    Path stagingFile = stagingDir.resolve("new.state.bin");
    Files.write(
        stagingFile,
        encoded,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);

    Path target = cacheBaseDir.resolve(projectChecksum + ".state.bin");
    Files.createDirectories(cacheBaseDir);
    commitMove(stagingFile, target);

    Path lastUsed = cacheBaseDir.resolve(projectChecksum + ".last_used");
    Files.write(
        lastUsed,
        Instant.now().toString().getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);

    gcOldEntries();
  }

  private boolean sawDegradedMarkerInStaging() {
    return Files.isRegularFile(stagingDir.resolve(AGENT_DEGRADED_MARKER));
  }

  private static void addCrossRefIfSibling(
      String className,
      Map<String, RelPath> classNameToPath,
      Map<String, String> owner,
      Set<CrossRef> refs) {
    if (classNameToPath.containsKey(className)) {
      return;
    }
    String moduleId = owner.get(className);
    if (moduleId != null) {
      refs.add(new CrossRef(moduleId, className));
    }
  }

  // Closure = seed ∪ direct (1-hop) project-internal refs of each seed. Static
  // closure is deliberately scoped to "declared but unloaded interfaces" and
  // direct same-package references — explicitly 1-hop, not a full transitive
  // walk. Walking transitively explodes through DI graphs (e.g. a Spring
  // @Controller references a @Component which references its collaborators,
  // ad infinitum) and pulls effectively the whole project into every test's
  // deps. Cross-module caller resolution adds another hop on top of this
  // result, giving the wider closure required there.
  private Set<String> transitiveClosure(
      Set<String> seeds,
      Map<String, RelPath> classNameToPath,
      Map<String, Set<String>> staticRefsCache) {
    Set<String> result = new HashSet<>(seeds);
    for (String seed : seeds) {
      if (!classNameToPath.containsKey(seed)) continue;
      Set<String> refs =
          staticRefsCache.computeIfAbsent(seed, k -> readStaticRefs(k, classNameToPath));
      for (String r : refs) {
        if (classNameToPath.containsKey(r)) {
          result.add(r);
        }
      }
    }
    return result;
  }

  private Set<String> readStaticRefs(String className, Map<String, RelPath> classNameToPath) {
    RelPath p = classNameToPath.get(className);
    if (p == null) return Collections.emptySet();
    Path file = p.resolveAgainst(projectBasedir);
    if (!Files.isRegularFile(file)) return Collections.emptySet();
    try {
      ClassFileMetadata m = ClassFileReader.parse(Files.readAllBytes(file));
      // ClassFileReader emits internal (slash) names; classNameToPath and the
      // reactor-sibling owner index are dotted. Normalize so static-closure
      // refs actually match — without this the static-dependency path for
      // same-module and cross-module "declared but unloaded" types never resolves.
      Set<String> out = new HashSet<>();
      if (m.superClass() != null) out.add(m.superClass().replace('/', '.'));
      for (String i : m.interfaces()) out.add(i.replace('/', '.'));
      for (String r : m.referencedClasses()) out.add(r.replace('/', '.'));
      return out;
    } catch (IOException | RuntimeException e) {
      return Collections.emptySet();
    }
  }

  private RelPath relativizeResource(String r) {
    if (r == null) return null;

    String resourceKey = r.startsWith("/") ? r.substring(1) : r;
    if (!resourceKey.isEmpty() && !resourceKey.contains(":") && !resourceKey.startsWith("\\")) {
      RelPath fromClasspath = tryClasspathResource(resourceKey);
      if (fromClasspath != null) {
        return fromClasspath;
      }
    }

    Path resourceFile;
    try {
      resourceFile = Path.of(r);
    } catch (RuntimeException e) {
      return null;
    }
    if (resourceFile.isAbsolute()) {
      return tryAbsoluteResource(resourceFile);
    }

    //noinspection unchecked
    for (Set<RelPath> roots : new Set[] {this.sourceRoots, Set.<RelPath>of()}) {
      for (RelPath root : roots) {
        Path rootAbs = root.resolveAgainst(projectBasedir);
        Path candidate = rootAbs.resolve(resourceFile).normalize();
        if (candidate.startsWith(rootAbs) && Files.isRegularFile(candidate)) {
          try {
            return RelPath.relativize(projectBasedir, candidate);
          } catch (RuntimeException ignored) {
            // outside project base
          }
        }
      }
    }
    return null;
  }

  private RelPath tryClasspathResource(String resourceKey) {
    // For non-.class resources, prefer the src/{main,test}/resources
    // counterpart over the target/classes copy: the build copy is regenerated
    // every build for some plugins (build-info.properties, git.properties,
    // SBOM JSON) with no source-content change, which would otherwise
    // reselect every test that loads them. .class files have no src/
    // counterpart, so for those we still return the target path.
    boolean isClass = resourceKey.endsWith(".class");
    if (!isClass) {
      RelPath fromSrc = findInResourceRoots(resourceKey);
      if (fromSrc != null) return fromSrc;
    }
    Path testClasses = testClassesDir.resolve(resourceKey).normalize();
    if (testClasses.startsWith(testClassesDir) && Files.isRegularFile(testClasses)) {
      try {
        return RelPath.relativize(projectBasedir, testClasses);
      } catch (RuntimeException ignored) {
        // outside project base
      }
    }
    Path mainClasses = mainClassesDir.resolve(resourceKey).normalize();
    if (mainClasses.startsWith(mainClassesDir) && Files.isRegularFile(mainClasses)) {
      try {
        return RelPath.relativize(projectBasedir, mainClasses);
      } catch (RuntimeException ignored) {
        // outside project base
      }
    }
    return null;
  }

  private RelPath findInResourceRoots(String resourceKey) {
    for (RelPath root : sourceRoots) {
      Path rootAbs = root.resolveAgainst(projectBasedir);
      Path candidate = rootAbs.resolve(resourceKey).normalize();
      if (candidate.startsWith(rootAbs) && Files.isRegularFile(candidate)) {
        try {
          return RelPath.relativize(projectBasedir, candidate);
        } catch (RuntimeException ignored) {
          // outside project base
        }
      }
    }
    return null;
  }

  private RelPath tryAbsoluteResource(Path resourceFile) {
    // For absolute paths under target/{classes,test-classes}, redirect non-.class
    // resources to their src/ counterpart for the same reason as
    // tryClasspathResource: target/-only generated files (build-info,
    // git.properties, SBOM) churn every build and would reselect tests that
    // read them, but every real resource the runtime sees in target/ was
    // copied from src/.
    Path fileName = resourceFile.getFileName();
    boolean isClass = fileName != null && fileName.toString().endsWith(".class");
    if (resourceFile.startsWith(testClassesDir)) {
      if (!isClass) {
        Path rel = testClassesDir.relativize(resourceFile);
        RelPath fromSrc = findInResourceRoots(rel.toString().replace('\\', '/'));
        if (fromSrc != null) return fromSrc;
      }
      try {
        return RelPath.relativize(projectBasedir, resourceFile);
      } catch (RuntimeException ignored) {
        // outside project base
      }
    } else if (resourceFile.startsWith(mainClassesDir)) {
      if (!isClass) {
        Path rel = mainClassesDir.relativize(resourceFile);
        RelPath fromSrc = findInResourceRoots(rel.toString().replace('\\', '/'));
        if (fromSrc != null) return fromSrc;
      }
      try {
        return RelPath.relativize(projectBasedir, resourceFile);
      } catch (RuntimeException ignored) {
        // outside project base
      }
    }
    //noinspection unchecked
    for (Set<RelPath> roots : new Set[] {this.sourceRoots, Set.<RelPath>of()}) {
      for (RelPath root : roots) {
        Path rootAbs = root.resolveAgainst(projectBasedir);
        if (resourceFile.startsWith(rootAbs)) {
          if (Files.isRegularFile(resourceFile)) {
            try {
              return RelPath.relativize(projectBasedir, resourceFile);
            } catch (RuntimeException ignored) {
              // outside project base
            }
          }
        }
      }
    }
    return null;
  }

  private Map<String, RelPath> buildClassToPathIndex() {
    Map<String, RelPath> index = new HashMap<>();
    buildIndex(mainClassesDir, index);
    buildIndex(testClassesDir, index);
    return index;
  }

  private void buildIndex(Path classDir, Map<String, RelPath> index) {
    if (!Files.isDirectory(classDir)) return;
    try {
      SourceWalker.listFiles(classDir)
          .forEach(
              p -> {
                Path fnPath = p.getFileName();
                if (fnPath == null) return;
                String name = fnPath.toString();
                if (name.endsWith(".class")) {
                  String rel = classDir.relativize(p).toString().replace('\\', '/');
                  String className =
                      rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                  index.put(className, RelPath.relativize(projectBasedir, p));
                }
              });
    } catch (IOException ignored) {
      // best effort
    }
  }

  @FunctionalInterface
  interface AtomicMover {
    void move(Path from, Path to) throws IOException;
  }

  static void commitMove(Path from, Path to) throws IOException {
    commitMove(from, to, CullSession::atomicMove);
  }

  static void commitMove(Path from, Path to, AtomicMover mover) throws IOException {
    try {
      mover.move(from, to);
    } catch (UnsupportedOperationException | IOException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void atomicMove(Path from, Path to) throws IOException {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void deleteRecursive(Path root) throws IOException {
    if (!Files.isDirectory(root)) return;
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> sorted =
          walk.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
      for (Path p : sorted) {
        Files.deleteIfExists(p);
      }
    }
  }

  private void gcOldEntries() {
    CacheRetention.gc(cacheBaseDir, retention);
  }

  String readLastUsed(Path stateFile) {
    return CacheRetention.readLastUsed(stateFile);
  }
}
