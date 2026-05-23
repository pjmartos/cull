package io.pjmartos.cull.extension;

import io.pjmartos.cull.core.JacocoExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Coverage retention: keep JaCoCo's accumulated coverage across cull's partial test runs so the
 * report reflects the full suite, not just the executed subset.
 *
 * <p>cull caches each module's coverage {@code .exec} in its per-module cache keyed by the project
 * checksum (alongside {@code state.bin}), {@linkplain #restore restores it into JaCoCo's destFile
 * before tests run}, and {@linkplain #persist re-persists the union after a successful commit}.
 * JaCoCo's own append mode (its default) merges the executed subset into the seeded baseline at
 * dump time; entries whose class id no longer matches the recompiled bytecode are dropped by JaCoCo
 * at report time, so a stale baseline never reports coverage for code that has since changed. When
 * nothing relevant changed and cull selects zero tests, the seeded baseline is left untouched and
 * the report still reflects the full suite instead of flapping to zero.
 *
 * <p>The feature is auto-enabled whenever the {@code jacoco-maven-plugin} is configured and opted
 * out with {@code cull.coverage.retain=false}. It is inert (no seed, no persist) when JaCoCo runs
 * with {@code append=false}, since the agent would overwrite the seeded baseline and persisting the
 * subset-only result back would shrink the baseline.
 */
final class CoverageRetention {

  static final String JACOCO_GAV = "org.jacoco:jacoco-maven-plugin";
  static final String PREPARE_AGENT = "prepare-agent";
  static final String PREPARE_AGENT_IT = "prepare-agent-integration";
  static final String DEFAULT_EXEC = "jacoco.exec";
  static final String DEFAULT_EXEC_IT = "jacoco-it.exec";

  private CoverageRetention() {}

  /** Cache file name for a module's coverage baseline, per phase. */
  static String cacheFileName(String checksum, boolean integration) {
    return checksum + (integration ? ".jacoco-it.exec" : ".jacoco.exec");
  }

  /**
   * Seed the cached baseline into JaCoCo's destFile so append-mode unions the executed subset into
   * it. A no-op when retention is disabled, JaCoCo is absent or in overwrite mode, or no baseline
   * has been recorded yet. Never throws — coverage retention must not fail the build.
   */
  static void restore(
      MavenProject project,
      MavenSession session,
      String checksum,
      boolean integration,
      Path cacheBase) {
    try {
      if (checksum == null || cacheBase == null) {
        return;
      }
      Resolved cfg = resolve(project, session, integration);
      if (cfg == null) {
        return;
      }
      Path baseline = cacheBase.resolve(cacheFileName(checksum, integration));
      if (!Files.isRegularFile(baseline)) {
        return;
      }
      Path parent = cfg.destFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.copy(baseline, cfg.destFile, StandardCopyOption.REPLACE_EXISTING);
      System.err.println(
          "[cull] restored coverage baseline for "
              + project.getArtifactId()
              + (integration ? " (IT)" : ""));
    } catch (IOException | RuntimeException e) {
      System.err.println(
          "[cull] coverage baseline restore skipped for " + project.getArtifactId() + ": " + e);
    }
  }

  /**
   * Persist the post-run destFile (baseline ∪ executed subset) as the new baseline. Call only on a
   * successful commit; on rollback the prior baseline is left untouched. Never throws.
   */
  static void persist(
      MavenProject project,
      MavenSession session,
      String checksum,
      boolean integration,
      Path cacheBase) {
    try {
      if (checksum == null || cacheBase == null) {
        return;
      }
      Resolved cfg = resolve(project, session, integration);
      if (cfg == null) {
        return;
      }
      if (!Files.isRegularFile(cfg.destFile)) {
        return;
      }
      Files.createDirectories(cacheBase);
      Path baseline = cacheBase.resolve(cacheFileName(checksum, integration));
      Path tmp = cacheBase.resolve(cacheFileName(checksum, integration) + ".tmp");
      try {
        // Compact: OR-merge the blocks JaCoCo accumulated across seed+append so the
        // baseline stays bounded across many warm runs instead of growing per run.
        JacocoExec.compact(cfg.destFile, tmp);
      } catch (IOException compactionUnsupported) {
        // Unrecognized/incompatible JaCoCo format or a corrupt/partial dump: keep the
        // always-correct verbatim copy so retention still works, just uncompacted.
        Files.copy(cfg.destFile, tmp, StandardCopyOption.REPLACE_EXISTING);
      }
      CullSession.commitMove(tmp, baseline);
    } catch (IOException | RuntimeException e) {
      System.err.println(
          "[cull] coverage baseline persist skipped for " + project.getArtifactId() + ": " + e);
    }
  }

  /**
   * The effective JaCoCo destFile to seed/persist for this phase, or {@code null} when retention is
   * disabled, JaCoCo is not configured, or JaCoCo runs in overwrite ({@code append=false}) mode.
   */
  static Resolved resolve(MavenProject project, MavenSession session, boolean integration) {
    if ("false".equals(CullProperties.coverageRetain(session))) {
      return null;
    }
    if (project == null || project.getBuild() == null) {
      return null;
    }
    Plugin jacoco = project.getBuild().getPluginsAsMap().get(JACOCO_GAV);
    if (jacoco == null) {
      return null;
    }
    if (!append(project, session, jacoco, integration)) {
      return null;
    }
    Path dest = destFile(project, jacoco, integration);
    if (dest == null) {
      return null;
    }
    return new Resolved(dest);
  }

  /**
   * True when this phase records retained JaCoCo coverage but no baseline has been captured yet, so
   * the caller should run the whole suite once to record a complete baseline. Without this, a
   * partial run on a clean build would leave JaCoCo's destFile holding only the executed subset —
   * the report flaps to that subset, and {@link #persist} then bakes the subset in as the baseline,
   * keeping the report degraded until a full run happens to occur. This state arises naturally when
   * upgrading to a cull build that adds coverage retention (existing caches have {@code state.bin}
   * but no {@code .exec} baseline), or whenever a prior persist did not produce one.
   *
   * <p>Stricter than {@link #resolve}: it additionally requires the phase-appropriate {@code
   * prepare-agent} goal to be bound, so a project that records only unit (or only integration)
   * coverage is never forced to run the other phase's whole suite just because its baseline is
   * absent. Never throws — coverage retention must not fail the build.
   */
  static boolean baselineNeedsBootstrap(
      MavenProject project,
      MavenSession session,
      String checksum,
      boolean integration,
      Path cacheBase) {
    try {
      if (checksum == null || cacheBase == null) {
        return false;
      }
      if (!prepareAgentBound(project, integration)) {
        return false;
      }
      if (resolve(project, session, integration) == null) {
        return false;
      }
      return !Files.isRegularFile(cacheBase.resolve(cacheFileName(checksum, integration)));
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Whether the JaCoCo plugin binds this phase's {@code prepare-agent} goal in any execution. */
  private static boolean prepareAgentBound(MavenProject project, boolean integration) {
    if (project == null || project.getBuild() == null) {
      return false;
    }
    Plugin jacoco = project.getBuild().getPluginsAsMap().get(JACOCO_GAV);
    if (jacoco == null) {
      return false;
    }
    String goal = integration ? PREPARE_AGENT_IT : PREPARE_AGENT;
    for (PluginExecution e : jacoco.getExecutions()) {
      if (e.getGoals() != null && e.getGoals().contains(goal)) {
        return true;
      }
    }
    return false;
  }

  private static Path destFile(MavenProject project, Plugin jacoco, boolean integration) {
    Path buildDir = Path.of(project.getBuild().getDirectory());
    String goal = integration ? PREPARE_AGENT_IT : PREPARE_AGENT;
    String configured = readParam(jacoco, goal, "destFile");
    if (configured == null || configured.isEmpty()) {
      return buildDir.resolve(integration ? DEFAULT_EXEC_IT : DEFAULT_EXEC);
    }
    return resolvePath(project, buildDir, configured);
  }

  private static boolean append(
      MavenProject project, MavenSession session, Plugin jacoco, boolean integration) {
    String goal = integration ? PREPARE_AGENT_IT : PREPARE_AGENT;
    String v = readParam(jacoco, goal, "append");
    if (v == null) {
      v = property(project, session, "jacoco.append");
    }
    // JaCoCo's append parameter defaults to true.
    return v == null || "true".equalsIgnoreCase(v.trim());
  }

  /**
   * Read a JaCoCo parameter, preferring an execution that binds {@code goal} and falling back to
   * the plugin-level configuration. Both layers are interpolated by Maven before mojos run.
   */
  private static String readParam(Plugin plugin, String goal, String name) {
    for (PluginExecution e : plugin.getExecutions()) {
      if (e.getGoals() != null && e.getGoals().contains(goal)) {
        String v = childValue(e.getConfiguration(), name);
        if (v != null) {
          return v;
        }
      }
    }
    return childValue(plugin.getConfiguration(), name);
  }

  private static String childValue(Object cfg, String name) {
    if (!(cfg instanceof Xpp3Dom)) {
      return null;
    }
    Xpp3Dom child = ((Xpp3Dom) cfg).getChild(name);
    if (child == null || child.getValue() == null) {
      return null;
    }
    String v = child.getValue().trim();
    return v.isEmpty() ? null : v;
  }

  private static String property(MavenProject project, MavenSession session, String key) {
    if (session != null) {
      String v = session.getUserProperties().getProperty(key);
      if (v != null) {
        return v;
      }
      v = session.getSystemProperties().getProperty(key);
      if (v != null) {
        return v;
      }
    }
    return project.getProperties().getProperty(key);
  }

  /**
   * Resolve a configured destFile to an absolute path. Handles the common unexpanded {@code
   * ${project.build.directory}} / {@code ${project.basedir}} placeholders defensively, and resolves
   * relative paths against the project basedir (how Maven binds {@code File} parameters).
   */
  private static Path resolvePath(MavenProject project, Path buildDir, String raw) {
    String basedir =
        project.getBasedir() == null ? buildDir.toString() : project.getBasedir().getAbsolutePath();
    String s =
        raw.replace("${project.build.directory}", buildDir.toString())
            .replace("${project.basedir}", basedir)
            .replace("${basedir}", basedir);
    Path p = Path.of(s);
    return p.isAbsolute() ? p : Path.of(basedir).resolve(p).normalize();
  }

  /**
   * The JaCoCo destFile to seed/persist for a single phase. Only produced when retention is active
   * and JaCoCo is in append mode, so no append flag needs to be carried.
   */
  static final class Resolved {
    final Path destFile;

    Resolved(Path destFile) {
      this.destFile = destFile;
    }
  }
}
