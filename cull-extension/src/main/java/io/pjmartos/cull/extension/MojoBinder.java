package io.pjmartos.cull.extension;

import java.util.Collections;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;

public final class MojoBinder {

  static final String CULL_PLUGIN_GROUP = "io.github.pjmartos.cull";
  static final String CULL_PLUGIN_ARTIFACT = "cull-maven-plugin";

  private MojoBinder() {}

  public static void bind(MavenProject project) {
    Build build = project.getBuild();
    if (build == null) return;

    Plugin existing = build.getPluginsAsMap().get(CULL_PLUGIN_GROUP + ":" + CULL_PLUGIN_ARTIFACT);
    Plugin plugin;
    if (existing == null) {
      plugin = new Plugin();
      plugin.setGroupId(CULL_PLUGIN_GROUP);
      plugin.setArtifactId(CULL_PLUGIN_ARTIFACT);
      plugin.setVersion(resolveVersion());
      build.getPlugins().add(plugin);
      build.getPluginsAsMap().put(plugin.getKey(), plugin);
    } else {
      plugin = existing;
    }

    ensureExecution(plugin, "cull-select", "select", "process-test-classes");
    ensureExecution(plugin, "cull-select-it", "select-it", "pre-integration-test");
  }

  private static void ensureExecution(
      Plugin plugin, String executionId, String goal, String phase) {
    for (PluginExecution e : plugin.getExecutions()) {
      if (executionId.equals(e.getId())) return;
    }
    PluginExecution e = new PluginExecution();
    e.setId(executionId);
    e.setPhase(phase);
    e.setGoals(Collections.singletonList(goal));
    plugin.addExecution(e);
  }

  private static String resolveVersion() {
    Package pkg = MojoBinder.class.getPackage();
    String v = pkg == null ? null : pkg.getImplementationVersion();
    return v == null ? "0.0.1-SNAPSHOT" : v;
  }
}
