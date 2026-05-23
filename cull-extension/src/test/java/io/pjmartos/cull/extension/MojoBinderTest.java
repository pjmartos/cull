package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MojoBinder} — auto-binds cull-maven-plugin executions to process-test-classes
 * and pre-integration-test.
 */
class MojoBinderTest {

  @Test
  void bindAddsPluginAndBothExecutionsWhenAbsent() {
    MavenProject project = new MavenProject();
    Build build = new Build();
    project.setBuild(build);

    MojoBinder.bind(project);

    Plugin plugin = findPlugin(build);
    assertNotNull(plugin, "cull-maven-plugin must be added");
    assertEquals("io.github.pjmartos.cull", plugin.getGroupId());
    assertEquals("cull-maven-plugin", plugin.getArtifactId());

    boolean hasSelect = false;
    boolean hasSelectIt = false;
    for (PluginExecution e : plugin.getExecutions()) {
      if ("cull-select".equals(e.getId())) {
        hasSelect = true;
        assertEquals("process-test-classes", e.getPhase());
        assertTrue(e.getGoals().contains("select"), "select execution must have 'select' goal");
      }
      if ("cull-select-it".equals(e.getId())) {
        hasSelectIt = true;
        assertEquals("pre-integration-test", e.getPhase());
        assertTrue(
            e.getGoals().contains("select-it"), "select-it execution must have 'select-it' goal");
      }
    }
    assertTrue(hasSelect, "cull-select execution must be present");
    assertTrue(hasSelectIt, "cull-select-it execution must be present");
  }

  @Test
  void bindDoesNotDuplicateExistingExecutions() {
    MavenProject project = new MavenProject();
    Build build = new Build();
    Plugin existing = new Plugin();
    existing.setGroupId("io.github.pjmartos.cull");
    existing.setArtifactId("cull-maven-plugin");
    existing.setVersion("0.0.1-SNAPSHOT");
    PluginExecution exec = new PluginExecution();
    exec.setId("cull-select");
    exec.setPhase("process-test-classes");
    exec.addGoal("select");
    existing.addExecution(exec);
    build.addPlugin(existing);
    project.setBuild(build);

    MojoBinder.bind(project);

    Plugin plugin = findPlugin(build);
    assertEquals(
        2,
        plugin.getExecutions().size(),
        "second execution (select-it) should be added without duplicating the first");
  }

  @Test
  void bindSkippedForNullBuild() {
    MavenProject project = new MavenProject();
    // No build set — MojoBinder.bind should return gracefully
    MojoBinder.bind(project);
    // No exception expected
  }

  @Test
  void bindUsesResolvedCullVersion() {
    MavenProject project = new MavenProject();
    Build build = new Build();
    project.setBuild(build);

    MojoBinder.bind(project);

    Plugin plugin = findPlugin(build);
    assertEquals(
        CullVersion.get(),
        plugin.getVersion(),
        "bound plugin version must track cull's own version, not a hardcoded literal");
  }

  @Test
  void existingPluginWithoutExecutionsGetsBothBound() {
    MavenProject project = new MavenProject();
    Build build = new Build();
    Plugin existing = new Plugin();
    existing.setGroupId("io.github.pjmartos.cull");
    existing.setArtifactId("cull-maven-plugin");
    existing.setVersion("1.0.0");
    existing.setConfiguration(new org.codehaus.plexus.util.xml.Xpp3Dom("configuration"));
    build.addPlugin(existing);
    project.setBuild(build);

    MojoBinder.bind(project);

    Plugin plugin = findPlugin(build);
    assertEquals(
        2,
        plugin.getExecutions().size(),
        "existing plugin without executions should get both added");
    assertTrue(plugin.getExecutions().stream().anyMatch(e -> "cull-select".equals(e.getId())));
    assertTrue(plugin.getExecutions().stream().anyMatch(e -> "cull-select-it".equals(e.getId())));
  }

  private static Plugin findPlugin(Build build) {
    for (Plugin p : build.getPlugins()) {
      if ("io.github.pjmartos.cull".equals(p.getGroupId())
          && "cull-maven-plugin".equals(p.getArtifactId())) {
        return p;
      }
    }
    return null;
  }
}
