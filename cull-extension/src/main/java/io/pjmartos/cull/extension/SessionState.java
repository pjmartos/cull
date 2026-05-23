package io.pjmartos.cull.extension;

import io.pjmartos.cull.core.RelPath;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SessionState {

  public final String projectChecksum;
  public final Path cacheBaseDir;
  public final Path stagingDir;
  public final Path projectBasedir;
  public final Path buildDirectory;
  public final Path mainClassesDir;
  public final Path testClassesDir;
  public final int retention;
  public final boolean wildcard;
  public final boolean degraded;
  public final boolean xmlReportsDisabled;
  public final Set<String> selectedTests;
  public final Map<RelPath, byte[]> currentHashes;
  public final Set<RelPath> resourceRoots;
  public final Map<String, String> reactorSiblings;
  public final String crossModule;
  // Full enumerated candidate test universe for this run; persisted into the
  // graph at commit so discovered-but-never-executed tests are not treated as
  // brand new on every subsequent build.
  public final Set<String> candidateTests;

  public SessionState(
      String projectChecksum,
      Path cacheBaseDir,
      Path stagingDir,
      Path projectBasedir,
      Path buildDirectory,
      Path mainClassesDir,
      Path testClassesDir,
      int retention,
      boolean wildcard,
      boolean degraded,
      boolean xmlReportsDisabled,
      Set<String> selectedTests,
      Map<RelPath, byte[]> currentHashes,
      Set<RelPath> resourceRoots) {
    this(
        projectChecksum,
        cacheBaseDir,
        stagingDir,
        projectBasedir,
        buildDirectory,
        mainClassesDir,
        testClassesDir,
        retention,
        wildcard,
        degraded,
        xmlReportsDisabled,
        selectedTests,
        currentHashes,
        resourceRoots,
        Collections.emptyMap(),
        "off");
  }

  public SessionState(
      String projectChecksum,
      Path cacheBaseDir,
      Path stagingDir,
      Path projectBasedir,
      Path buildDirectory,
      Path mainClassesDir,
      Path testClassesDir,
      int retention,
      boolean wildcard,
      boolean degraded,
      boolean xmlReportsDisabled,
      Set<String> selectedTests,
      Map<RelPath, byte[]> currentHashes,
      Set<RelPath> resourceRoots,
      Map<String, String> reactorSiblings,
      String crossModule) {
    this(
        projectChecksum,
        cacheBaseDir,
        stagingDir,
        projectBasedir,
        buildDirectory,
        mainClassesDir,
        testClassesDir,
        retention,
        wildcard,
        degraded,
        xmlReportsDisabled,
        selectedTests,
        currentHashes,
        resourceRoots,
        reactorSiblings,
        crossModule,
        Collections.emptySet());
  }

  public SessionState(
      String projectChecksum,
      Path cacheBaseDir,
      Path stagingDir,
      Path projectBasedir,
      Path buildDirectory,
      Path mainClassesDir,
      Path testClassesDir,
      int retention,
      boolean wildcard,
      boolean degraded,
      boolean xmlReportsDisabled,
      Set<String> selectedTests,
      Map<RelPath, byte[]> currentHashes,
      Set<RelPath> resourceRoots,
      Map<String, String> reactorSiblings,
      String crossModule,
      Set<String> candidateTests) {
    this.projectChecksum = projectChecksum;
    this.cacheBaseDir = cacheBaseDir;
    this.stagingDir = stagingDir;
    this.projectBasedir = projectBasedir;
    this.buildDirectory = buildDirectory;
    this.mainClassesDir = mainClassesDir;
    this.testClassesDir = testClassesDir;
    this.retention = retention;
    this.wildcard = wildcard;
    this.degraded = degraded;
    this.xmlReportsDisabled = xmlReportsDisabled;
    this.selectedTests = new LinkedHashSet<>(selectedTests);
    this.currentHashes = new LinkedHashMap<>(currentHashes);
    this.resourceRoots = new LinkedHashSet<>(resourceRoots);
    this.reactorSiblings = new LinkedHashMap<>(reactorSiblings);
    this.crossModule = crossModule;
    this.candidateTests = new LinkedHashSet<>(candidateTests);
  }
}
