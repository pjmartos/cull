package io.pjmartos.cull.plugin;

import io.pjmartos.cull.extension.CullProperties;
import io.pjmartos.cull.extension.SelectionEngine;
import io.pjmartos.cull.extension.SelectionOutcome;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo(
    name = "select",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class CullSelectMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Override
  public void execute() {
    long startNanos = System.nanoTime();
    SelectionOutcome outcome;
    try {
      outcome = SelectionEngine.runFor(project, session, false);
    } catch (Throwable t) {
      // Hard invariant: cull never fails the build. Any failure degrades to
      // running all tests this invocation with no cache commit.
      outcome = SelectionOutcome.wildcard("cull error: " + t);
      getLog()
          .warn(
              "[cull] "
                  + project.getArtifactId()
                  + ": selection failed ("
                  + t
                  + "); running all tests");
    }
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
    publishProperties(outcome);
    String summary =
        outcome.wildcard
            ? "running all tests ("
                + (outcome.reasonForWildcard == null ? "wildcard" : outcome.reasonForWildcard)
                + ")"
            : "selected " + outcome.selected.size() + " test(s)";
    getLog()
        .info(
            "[cull] " + project.getArtifactId() + ": " + summary + " in " + elapsedMillis + " ms");
  }

  private void publishProperties(SelectionOutcome outcome) {
    project
        .getProperties()
        .setProperty(CullProperties.SELECTED_TESTS, outcome.selectedTestsString());
    if (outcome.stagingDir != null) {
      project
          .getProperties()
          .setProperty("cull.observations.dir", outcome.stagingDir.toAbsolutePath().toString());
    } else {
      project
          .getProperties()
          .setProperty("cull.observations.dir", System.getProperty("java.io.tmpdir"));
    }
    project
        .getProperties()
        .setProperty(
            CullProperties.SESSION_ID, outcome.sessionId == null ? "n/a" : outcome.sessionId);
  }
}
