package io.pjmartos.cull.extension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

public final class CullProperties {

  public static final String CACHE_DIR = "cull.cache.directory";
  public static final String DISABLED = "cull.disabled";
  public static final String FALLBACK_RUN_ALL = "cull.fallback.runAll";
  public static final String OBSERVATIONS_DIR = "cull.observations.directory";
  public static final String CACHE_RETENTION = "cull.cache.retention";
  public static final String IO_HOOKS_DISABLED = "cull.iohooks.disabled";
  public static final String SELECTED_TESTS = "cull.selected.tests";
  public static final String SESSION_ID = "cull.session.id";
  public static final String TEST_INCLUDES = "cull.test.includes";
  public static final String IT_INCLUDES = "cull.it.includes";
  public static final String IT_ENABLED = "cull.experimental.itSelection.enabled";
  public static final String CROSS_MODULE = "cull.crossmodule";
  public static final String COVERAGE_RETAIN = "cull.coverage.retain";

  /** Cross-module test-impact mode. {@code FULL} is the default. */
  public enum CrossModuleMode {
    OFF,
    FULL
  }

  private CullProperties() {}

  public static boolean isDisabled(MavenSession session) {
    return boolFromUser(session, DISABLED);
  }

  public static boolean isFallbackRunAll(MavenSession session) {
    return boolFromUser(session, FALLBACK_RUN_ALL);
  }

  public static boolean isItSelectionEnabled(MavenSession session) {
    return boolFromUser(session, IT_ENABLED);
  }

  public static int cacheRetention(MavenSession session) {
    String s = stringFromUser(session, CACHE_RETENTION, "5");
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return 5;
    }
  }

  public static Path cacheDirectory(MavenSession session) {
    String s = stringFromUser(session, CACHE_DIR, defaultCacheDir());
    return Paths.get(s);
  }

  public static String defaultCacheDir() {
    return System.getProperty("user.home") + "/.cull/cache";
  }

  public static Path observationsRoot(MavenSession session, MavenProject p) {
    String s = stringFromUser(session, OBSERVATIONS_DIR, null);
    if (s != null) {
      return Paths.get(s);
    }
    return Paths.get(p.getBuild().getDirectory()).resolve("cull").resolve("observations");
  }

  public static boolean ioHooksDisabled(MavenSession session) {
    return boolFromUser(session, IO_HOOKS_DISABLED);
  }

  /** Comma-separated test/IT name patterns, or {@code null} when not configured by the user. */
  public static String testIncludes(MavenSession session, boolean integration) {
    return stringFromUser(session, integration ? IT_INCLUDES : TEST_INCLUDES, null);
  }

  /**
   * The coverage-retention setting, lowercased and trimmed, or {@code null} when unset. {@code
   * "false"} disables retention; any other value (including the unset default) leaves it to
   * auto-detection of the JaCoCo plugin.
   */
  public static String coverageRetain(MavenSession session) {
    String v = stringFromUser(session, COVERAGE_RETAIN, null);
    return v == null ? null : v.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Cross-module mode; defaults to {@code FULL}. Any unrecognized value falls back to the default.
   */
  public static CrossModuleMode crossModule(MavenSession session) {
    String v = stringFromUser(session, CROSS_MODULE, null);
    if (v == null) {
      return CrossModuleMode.FULL;
    }
    if ("off".equals(v.trim().toLowerCase(Locale.ROOT))) {
      return CrossModuleMode.OFF;
    }
    return CrossModuleMode.FULL;
  }

  private static boolean boolFromUser(MavenSession session, String key) {
    String v = stringFromUser(session, key, null);
    if (v == null) return false;
    return "true".equalsIgnoreCase(v.trim());
  }

  private static String stringFromUser(MavenSession session, String key, String def) {
    String v = session.getUserProperties().getProperty(key);
    if (v != null) return v;
    v = session.getSystemProperties().getProperty(key);
    if (v != null) return v;
    return def;
  }
}
