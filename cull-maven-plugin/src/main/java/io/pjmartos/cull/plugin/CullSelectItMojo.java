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
    name = "select-it",
    defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class CullSelectItMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Override
  public void execute() {
    long startNanos = System.nanoTime();
    SelectionOutcome outcome;
    try {
      outcome = SelectionEngine.runFor(project, session, true);
    } catch (Throwable t) {
      // Hard invariant: cull never fails the build. Any failure degrades to
      // running all integration tests this invocation with no cache commit.
      outcome = SelectionOutcome.wildcard("cull error: " + t);
      getLog()
          .warn(
              "[cull] "
                  + project.getArtifactId()
                  + " (IT): selection failed ("
                  + t
                  + "); running all integration tests");
    }
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
    project
        .getProperties()
        .setProperty(CullProperties.SELECTED_TESTS + ".it", outcome.selectedTestsString());
    if (outcome.stagingDir != null) {
      project
          .getProperties()
          .setProperty("cull.observations.dir.it", outcome.stagingDir.toAbsolutePath().toString());
    } else {
      project
          .getProperties()
          .setProperty("cull.observations.dir.it", System.getProperty("java.io.tmpdir"));
    }
    project
        .getProperties()
        .setProperty(
            CullProperties.SESSION_ID + ".it",
            outcome.sessionId == null ? "n/a" : outcome.sessionId);
    String summary =
        outcome.wildcard
            ? "running all integration tests"
            : "selected " + outcome.selected.size() + " IT(s)";
    getLog()
        .info(
            "[cull] "
                + project.getArtifactId()
                + " (IT): "
                + summary
                + " in "
                + elapsedMillis
                + " ms");
  }
}
