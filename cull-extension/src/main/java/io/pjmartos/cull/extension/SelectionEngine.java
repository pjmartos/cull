package io.pjmartos.cull.extension;

import io.pjmartos.cull.core.CrossRef;
import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.SourceWalker;
import io.pjmartos.cull.core.TestGraph;
import io.pjmartos.cull.core.TestGraphCodec;
import io.pjmartos.cull.core.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

public final class SelectionEngine {

  static final String SESSION_STATE_FILENAME = "session-state.bin";
  static final String SESSION_STATE_FILENAME_IT = "session-state-it.bin";

  // A leftover cache lock is an OS advisory lock (FileChannel.tryLock), released
  // by the OS when the holder dies — so a crashed build never blocks the next
  // one. This threshold only governs reaping the orphaned, harmless lock *file*;
  // a generous value avoids racing a legitimately long-running concurrent build.
  static final long LOCK_STALE_MILLIS = 24L * 60L * 60L * 1000L;

  private SelectionEngine() {}

  // Lock ownership is transferred to the registered CullSession, which releases it.
  @SuppressWarnings("PMD.CloseResource")
  public static SelectionOutcome runFor(
      MavenProject project, MavenSession session, boolean integration) {
    if (CullProperties.isDisabled(session)) {
      return SelectionOutcome.wildcard("cull disabled");
    }
    if (integration && !CullProperties.isItSelectionEnabled(session)) {
      return SelectionOutcome.wildcard("IT selection disabled");
    }
    if (ForkConfig.forkDisabled(project, session, integration)) {
      return SelectionOutcome.wildcard("forkCount=0 / forkMode=never");
    }
    boolean fallbackRunAll = CullProperties.isFallbackRunAll(session);

    CullProperties.CrossModuleMode crossMode = CullProperties.crossModule(session);
    Map<String, Path> reactorSiblings = ReactorSiblings.resolve(project, session);
    boolean crossEligible =
        crossMode != CullProperties.CrossModuleMode.OFF
            && !reactorSiblings.isEmpty()
            && allDirsExist(reactorSiblings);
    ReactorSiblings.Index crossIndex =
        crossEligible ? ReactorSiblings.index(reactorSiblings) : null;
    if (crossIndex != null && !crossIndex.usable()) {
      crossEligible = false;
      crossIndex = null;
    }

    String projectChecksum = ProjectChecksum.compute(project, session, integration, crossEligible);
    Path cacheBase = cacheBaseFor(project, session);
    try {
      Files.createDirectories(cacheBase);
    } catch (IOException e) {
      return SelectionOutcome.wildcard("cache root unwritable: " + e.getMessage());
    }

    Path lockFile = cacheBase.resolve(".lock");
    cleanupStaleLock(lockFile);
    try (FileLockHandle lock = FileLockHandle.tryAcquire(lockFile)) {
      if (lock == null) {
        return SelectionOutcome.wildcard("cache lock unavailable");
      }

      Path stateFile =
          cacheBase.resolve(projectChecksum + (integration ? ".it" : "") + ".state.bin");
      TestGraph priorGraph = TestGraph.empty();
      if (Files.isRegularFile(stateFile)) {
        try {
          priorGraph = TestGraphCodec.decode(Files.readAllBytes(stateFile));
          touchLastUsed(
              cacheBase.resolve(projectChecksum + (integration ? ".it" : "") + ".last_used"));
        } catch (IOException e) {
          priorGraph = TestGraph.empty();
        }
      }

      boolean degraded = readAgentDegraded(session, priorGraph);

      List<Path> sourceFiles = collectSourceFiles(project);
      Path projectBase = projectBase(project);
      Map<RelPath, byte[]> currentHashes = hashAll(projectBase, sourceFiles);
      Set<RelPath> currentFiles = new LinkedHashSet<>(currentHashes.keySet());

      Set<String> testClasses = collectTestClassNames(project, session, integration);

      Set<RelPath> resourceRoots = collectResourceRoots(project, projectBase);

      TestSelection.Inputs in =
          new TestSelection.Inputs(
              priorGraph.hashes(),
              priorGraph,
              currentFiles,
              currentHashes,
              testClasses,
              degraded,
              resourceRoots);
      TestSelection.Result selection = TestSelection.select(in);

      runOpportunisticGc(cacheBase, CullProperties.cacheRetention(session));

      String sessionId = UUID.randomUUID().toString();
      Path stagingRoot = CullProperties.observationsRoot(session, project);
      Path stagingDir =
          stagingRoot != null
              ? stagingRoot.resolve(".staging-" + sessionId)
              : cacheBase.resolve(".staging-" + sessionId);
      try {
        Files.createDirectories(stagingDir);
      } catch (IOException e) {
        return SelectionOutcome.wildcard("staging dir creation failed: " + e.getMessage());
      }

      Set<String> selected = selection.selectedTests;
      if (crossEligible && !selection.bootstrapped) {
        // Hash only the classes B previously depended on (changedCrossRefs reads
        // exactly those keys), not the entire upstream output.
        Map<CrossRef, byte[]> currentUpstream =
            ReactorSiblings.hash(crossIndex, priorGraph.upstreamHashes().keySet());
        Set<CrossRef> changedExternal =
            changedCrossRefs(priorGraph.upstreamHashes(), currentUpstream);
        Set<String> impacted = priorGraph.testsImpactedByCrossRefs(changedExternal);
        impacted.retainAll(testClasses);
        if (!impacted.isEmpty()) {
          // FULL: rerun only the downstream tests whose cross-module dependency
          // closure reaches a changed reactor-sibling class. The stable key and
          // graph are kept and the snapshot refreshes on commit.
          selected = new HashSet<>(selected);
          selected.addAll(impacted);
        }
      }
      boolean wildcard = false;
      String wildcardReason = null;
      if (fallbackRunAll) {
        selected = new HashSet<>(testClasses);
        wildcard = true;
      } else if (!testClasses.isEmpty() && selected.size() >= testClasses.size()) {
        wildcard = true;
      }
      // Coverage-retention bootstrap: when this phase records retained JaCoCo
      // coverage but no baseline exists yet (first run after enabling retention,
      // or a cache that predates it), a partial run would let the report flap to
      // the executed subset on a clean build — and persisting that subset back
      // would keep it degraded. Run the whole suite once to capture a complete
      // baseline; subsequent runs seed it and select normally.
      if (!wildcard
          && !testClasses.isEmpty()
          && CoverageRetention.baselineNeedsBootstrap(
              project, session, projectChecksum, integration, cacheBase)) {
        selected = new HashSet<>(testClasses);
        wildcard = true;
        wildcardReason = "coverage baseline bootstrap";
      }

      boolean xmlReportsDisabled = xmlReportsDisabled(project);

      Map<String, String> siblingStrings = new LinkedHashMap<>();
      for (Map.Entry<String, Path> e : reactorSiblings.entrySet()) {
        siblingStrings.put(e.getKey(), e.getValue().toString());
      }

      SessionState state =
          new SessionState(
              projectChecksum,
              cacheBase,
              stagingDir,
              projectBase,
              Path.of(project.getBuild().getDirectory()),
              Path.of(project.getBuild().getOutputDirectory()),
              Path.of(project.getBuild().getTestOutputDirectory()),
              CullProperties.cacheRetention(session),
              wildcard,
              degraded,
              xmlReportsDisabled,
              selected,
              currentHashes,
              resourceRoots,
              siblingStrings,
              crossEligible ? crossMode.name() : "OFF",
              testClasses);

      persistSessionState(project, state, integration);

      CullSession cs =
          new CullSession(
              project,
              cacheBase,
              stagingDir,
              projectChecksum,
              priorGraph,
              currentHashes,
              selected,
              testClasses,
              resourceRoots,
              wildcard,
              lock,
              degraded,
              CullProperties.cacheRetention(session));
      cs.enableCrossModule(reactorSiblings, crossEligible);
      CullSessionRegistry.put(project, cs, integration);

      // Seed JaCoCo's destFile with the retained baseline so this run's (possibly
      // empty) subset is unioned into the full-suite coverage rather than
      // overwriting it. Inert when JaCoCo is absent or in overwrite mode.
      CoverageRetention.restore(project, session, projectChecksum, integration, cacheBase);

      return new SelectionOutcome(
          selected, wildcard, sessionId, stagingDir, projectChecksum, wildcardReason);
    }
  }

  /** Per-module cache directory: {@code <cacheRoot>/<groupId-as-path>/<artifactId>}. */
  static Path cacheBaseFor(MavenProject project, MavenSession session) {
    return CullProperties.cacheDirectory(session)
        .resolve(project.getGroupId().replace('.', '/'))
        .resolve(project.getArtifactId());
  }

  static Path sessionStateFile(MavenProject project, boolean integration) {
    Path buildDir = Path.of(project.getBuild().getDirectory());
    return buildDir
        .resolve("cull")
        .resolve(integration ? SESSION_STATE_FILENAME_IT : SESSION_STATE_FILENAME);
  }

  private static void persistSessionState(
      MavenProject project, SessionState state, boolean integration) {
    Path file = sessionStateFile(project, integration);
    Path parent = file.getParent();
    if (parent == null) return;
    try {
      Files.createDirectories(parent);
      Files.write(file, SessionStateCodec.encode(state));
    } catch (IOException ignored) {
      // best effort: cross-classloader handoff degrades to in-realm registry only
    }
  }

  private static boolean readAgentDegraded(MavenSession session, TestGraph priorGraph) {
    if (CullProperties.ioHooksDisabled(session)) {
      return true;
    }
    return priorGraph.degraded();
  }

  private static boolean xmlReportsDisabled(MavenProject project) {
    org.apache.maven.model.Plugin sure =
        project.getBuild().getPluginsAsMap().get("org.apache.maven.plugins:maven-surefire-plugin");
    return surefireConfigBoolean(sure);
  }

  private static boolean surefireConfigBoolean(Plugin plugin) {
    if (plugin == null) return false;
    Object cfg = plugin.getConfiguration();
    if (!(cfg instanceof org.codehaus.plexus.util.xml.Xpp3Dom)) return false;
    org.codehaus.plexus.util.xml.Xpp3Dom dom = (org.codehaus.plexus.util.xml.Xpp3Dom) cfg;
    org.codehaus.plexus.util.xml.Xpp3Dom child = dom.getChild("disableXmlReport");
    return child != null && "true".equalsIgnoreCase(String.valueOf(child.getValue()).trim());
  }

  static boolean parseForkCountAsZero(String value) {
    return ForkConfig.parseForkCountAsZero(value);
  }

  private static List<Path> collectSourceFiles(MavenProject project) {
    List<Path> files = new ArrayList<>();
    for (String r : project.getCompileSourceRoots()) {
      files.addAll(safeWalk(Path.of(r)));
    }
    for (String r : project.getTestCompileSourceRoots()) {
      files.addAll(safeWalk(Path.of(r)));
    }
    for (org.apache.maven.model.Resource r : project.getResources()) {
      files.addAll(safeWalk(Path.of(r.getDirectory())));
    }
    for (org.apache.maven.model.Resource r : project.getTestResources()) {
      files.addAll(safeWalk(Path.of(r.getDirectory())));
    }
    // From target/{classes,test-classes} only track compiled bytecode. The
    // other files there are either copies of src/main/resources (so they'd
    // double-count) or build-time generated artifacts (build-info.properties,
    // git.properties, META-INF/sbom/*) that get fresh timestamps every build
    // and would otherwise reselect every test that reads them on every run.
    // Runtime resource reads still map to their src/ counterparts via
    // CullSession.relativizeResource.
    Path mainClasses = Path.of(project.getBuild().getOutputDirectory());
    files.addAll(filterClassFiles(safeWalk(mainClasses)));
    Path testClasses = Path.of(project.getBuild().getTestOutputDirectory());
    files.addAll(filterClassFiles(safeWalk(testClasses)));
    return files;
  }

  private static List<Path> filterClassFiles(List<Path> in) {
    List<Path> out = new ArrayList<>(in.size());
    for (Path p : in) {
      Path fn = p.getFileName();
      if (fn != null && fn.toString().endsWith(".class")) {
        out.add(p);
      }
    }
    return out;
  }

  private static Set<RelPath> collectResourceRoots(MavenProject project, Path projectBase) {
    Set<RelPath> roots = new LinkedHashSet<>();
    for (org.apache.maven.model.Resource r : project.getResources()) {
      try {
        roots.add(RelPath.relativize(projectBase, Path.of(r.getDirectory())));
      } catch (RuntimeException ignored) {
        // resource directory outside project base: skip
      }
    }
    for (org.apache.maven.model.Resource r : project.getTestResources()) {
      try {
        roots.add(RelPath.relativize(projectBase, Path.of(r.getDirectory())));
      } catch (RuntimeException ignored) {
        // resource directory outside project base: skip
      }
    }
    return roots;
  }

  private static List<Path> safeWalk(Path root) {
    try {
      return SourceWalker.listFiles(root);
    } catch (IOException e) {
      return Collections.emptyList();
    }
  }

  @SuppressWarnings("PMD.CloseResource")
  private static Map<RelPath, byte[]> hashAll(Path projectBase, List<Path> files) {
    ExecutorService pool =
        Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
    try {
      Map<Path, byte[]> raw = FileHasher.hashAll(files, pool);
      Map<RelPath, byte[]> out = new HashMap<>(raw.size());
      for (Map.Entry<Path, byte[]> e : raw.entrySet()) {
        try {
          out.put(RelPath.relativize(projectBase, e.getKey()), e.getValue());
        } catch (RuntimeException ignored) {
          // path outside basedir: skip
        }
      }
      return out;
    } finally {
      pool.shutdown();
    }
  }

  private static Set<String> collectTestClassNames(
      MavenProject project, MavenSession session, boolean integration) {
    Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
    if (!Files.isDirectory(testClassesDir)) return Collections.emptySet();
    TestNameFilter filter = TestNameFilter.forProject(project, session, integration);
    Set<String> out = new HashSet<>();
    try {
      for (Path p : SourceWalker.listFiles(testClassesDir)) {
        Path fnPath = p.getFileName();
        if (fnPath == null) continue;
        String name = fnPath.toString();
        if (!name.endsWith(".class")) continue;
        if (name.contains("$")) continue;
        String rel = testClassesDir.relativize(p).toString().replace('\\', '/');
        String relNoExt = rel.substring(0, rel.length() - ".class".length());
        String fqcn = relNoExt.replace('/', '.');
        if (filter.matches(fqcn, relNoExt)) {
          out.add(fqcn);
        }
      }
    } catch (IOException e) {
      return Collections.emptySet();
    }
    return out;
  }

  private static Path projectBase(MavenProject project) {
    return Path.of(project.getBasedir().getAbsolutePath()).normalize();
  }

  private static boolean allDirsExist(Map<String, Path> siblings) {
    for (Path p : siblings.values()) {
      if (!Files.isDirectory(p)) {
        return false;
      }
    }
    return true;
  }

  private static Set<CrossRef> changedCrossRefs(
      Map<CrossRef, byte[]> prev, Map<CrossRef, byte[]> current) {
    Set<CrossRef> changed = new HashSet<>();
    for (Map.Entry<CrossRef, byte[]> e : prev.entrySet()) {
      byte[] cur = current.get(e.getKey());
      if (cur == null || !Arrays.equals(cur, e.getValue())) {
        changed.add(e.getKey());
      }
    }
    return changed;
  }

  static void cleanupStaleLock(Path lockFile) {
    cleanupStaleLock(lockFile, LOCK_STALE_MILLIS);
  }

  static void cleanupStaleLock(Path lockFile, long staleMillis) {
    if (staleMillis <= 0L) return;
    try {
      if (!Files.isRegularFile(lockFile)) return;
      java.nio.file.attribute.FileTime mtime = Files.getLastModifiedTime(lockFile);
      long ageMillis = System.currentTimeMillis() - mtime.toMillis();
      if (ageMillis > staleMillis) {
        System.err.println(
            "[cull] WARNING: stale lock file at "
                + lockFile
                + " (age "
                + (ageMillis / 3600000L)
                + "h); removing.");
        Files.deleteIfExists(lockFile);
      }
    } catch (IOException ignored) {
      // best effort
    }
  }

  static void runOpportunisticGc(Path cacheBase, int retention) {
    CacheRetention.gc(cacheBase, retention);
  }

  static String readLastUsed(Path stateFile) {
    return CacheRetention.readLastUsed(stateFile);
  }

  private static void touchLastUsed(Path lastUsed) {
    try {
      Files.write(
          lastUsed,
          java.time.Instant.now().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
          java.nio.file.StandardOpenOption.WRITE);
    } catch (IOException ignored) {
      // best effort
    }
  }
}
