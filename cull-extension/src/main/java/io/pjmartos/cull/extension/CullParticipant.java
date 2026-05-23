package io.pjmartos.cull.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "cull")
public class CullParticipant extends AbstractMavenLifecycleParticipant {

  @Override
  public void afterProjectsRead(MavenSession session) {
    if (CullProperties.isDisabled(session)) {
      return;
    }
    for (MavenProject p : session.getProjects()) {
      try {
        ConfigInjector.inject(p, session);
        MojoBinder.bind(p);
      } catch (RuntimeException e) {
        System.err.println(
            "[cull] WARNING: skipping project " + p.getArtifactId() + ": " + e.getMessage());
      }
    }
  }

  @Override
  public void afterSessionEnd(MavenSession session) {
    try {
      if (CullProperties.isDisabled(session)) {
        return;
      }
      for (MavenProject p : session.getProjects()) {
        finalizeOne(p, session, false);
        finalizeOne(p, session, true);
      }
    } catch (Throwable t) {
      System.err.println(
          "[cull] afterSessionEnd failed (suppressed to honor build invariant): " + t);
    }
  }

  private void finalizeOne(MavenProject p, MavenSession session, boolean integration) {
    CullSession cs = loadSession(p, integration);
    if (cs == null) return;
    try {
      if (cs.allTestsPassed(session)) {
        cs.commit();
        // Persist the union (retained baseline ∪ this run's subset) that JaCoCo
        // wrote into its destFile, so the next run can seed it again. Only on a
        // verified-passing commit; a rollback leaves the prior baseline intact.
        CoverageRetention.persist(
            p,
            session,
            cs.projectChecksum(),
            integration,
            SelectionEngine.cacheBaseFor(p, session));
        System.err.println(
            "[cull] committed cache for "
                + p.getArtifactId()
                + (integration ? " (IT)" : "")
                + " checksum="
                + cs.projectChecksum());
      } else {
        cs.rollback();
        System.err.println(
            "[cull] rolled back (test failures or no reports) for "
                + p.getArtifactId()
                + (integration ? " (IT)" : ""));
      }
    } catch (RuntimeException e) {
      System.err.println("[cull] commit/rollback failed for " + p.getArtifactId() + ": " + e);
      try {
        cs.rollback();
      } catch (RuntimeException ignored) {
        // best effort
      }
    } finally {
      deleteStateFile(p, integration);
    }
  }

  private CullSession loadSession(MavenProject p, boolean integration) {
    CullSession cs = CullSessionRegistry.remove(p, integration);
    if (cs != null) {
      return cs;
    }
    Path file = SelectionEngine.sessionStateFile(p, integration);
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      SessionState state = SessionStateCodec.decode(Files.readAllBytes(file));
      return CullSession.fromState(state);
    } catch (IOException e) {
      System.err.println("[cull] failed to load session state for " + p.getArtifactId() + ": " + e);
      return null;
    }
  }

  private void deleteStateFile(MavenProject p, boolean integration) {
    Path file = SelectionEngine.sessionStateFile(p, integration);
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // best effort
    }
  }
}
