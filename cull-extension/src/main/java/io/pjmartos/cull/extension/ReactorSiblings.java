package io.pjmartos.cull.extension;

import io.pjmartos.cull.core.CrossRef;
import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.SourceWalker;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * Resolves the reactor modules a project depends on and lets a downstream module reason about
 * upstream changes at class granularity instead of treating an upstream artifact as one opaque
 * hash.
 *
 * <p>Building the per-class index is a filename walk only (cheap). Hashing is done lazily and only
 * for the classes a caller actually needs (the prior depended-on set for change detection, the
 * committed dependency set for the snapshot) — never the entire upstream output every build.
 */
final class ReactorSiblings {

  private static final Set<String> RELEVANT_SCOPES =
      Set.of("compile", "system", "runtime", "test", "provided");

  private ReactorSiblings() {}

  /** Reactor module {@code groupId:artifactId} → its {@code target/classes} directory. */
  static Map<String, Path> resolve(MavenProject project, MavenSession session) {
    Map<String, Path> out = new LinkedHashMap<>();
    if (project == null || session == null || session.getProjects() == null) {
      return out;
    }
    Map<String, MavenProject> reactorByGav = new HashMap<>();
    for (MavenProject p : session.getProjects()) {
      reactorByGav.put(p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion(), p);
    }
    Set<Artifact> artifacts = project.getArtifacts();
    if (artifacts == null) {
      return out;
    }
    for (Artifact a : artifacts) {
      // A null scope is anomalous for a resolved artifact; treat it conservatively
      // as non-relevant so its content stays in the coarse checksum (never miss).
      String scope = a.getScope();
      if (scope == null || !RELEVANT_SCOPES.contains(scope)) {
        continue;
      }
      MavenProject sibling =
          reactorByGav.get(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion());
      if (sibling != null && sibling.getBuild() != null) {
        out.put(
            a.getGroupId() + ":" + a.getArtifactId(),
            Path.of(sibling.getBuild().getOutputDirectory()));
      }
    }
    return out;
  }

  static boolean isReactorSibling(Artifact a, MavenSession session) {
    if (a == null || session == null || session.getProjects() == null) {
      return false;
    }
    for (MavenProject p : session.getProjects()) {
      if (p.getGroupId().equals(a.getGroupId())
          && p.getArtifactId().equals(a.getArtifactId())
          && p.getVersion().equals(a.getVersion())) {
        return true;
      }
    }
    return false;
  }

  /** Class-ownership + file location index over the reactor siblings — no hashing. */
  static final class Index {
    final Map<String, String> ownerByClass;
    final Map<CrossRef, Path> fileByRef;
    final boolean ambiguous;

    Index(Map<String, String> ownerByClass, Map<CrossRef, Path> fileByRef, boolean ambiguous) {
      this.ownerByClass = ownerByClass;
      this.fileByRef = fileByRef;
      this.ambiguous = ambiguous;
    }

    boolean usable() {
      return !ambiguous && !fileByRef.isEmpty();
    }
  }

  /**
   * Walks every {@code .class} under each reactor sibling recording which module owns each FQCN and
   * where its file is — but does not hash. If the same FQCN is owned by more than one module the
   * index is marked ambiguous, which (by design) disables cross-module analysis for the whole
   * module and falls back to the coarse, always-sound checksum.
   */
  static Index index(Map<String, Path> siblings) {
    Map<String, String> owner = new HashMap<>();
    Map<CrossRef, Path> fileByRef = new HashMap<>();
    boolean ambiguous = false;
    for (Map.Entry<String, Path> e : siblings.entrySet()) {
      String moduleId = e.getKey();
      Path dir = e.getValue();
      try {
        for (Path p : SourceWalker.listFiles(dir)) {
          Path fn = p.getFileName();
          if (fn == null || !fn.toString().endsWith(".class")) {
            continue;
          }
          String rel = dir.relativize(p).toString().replace('\\', '/');
          String className = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
          String prevOwner = owner.put(className, moduleId);
          if (prevOwner != null && !prevOwner.equals(moduleId)) {
            ambiguous = true;
          }
          fileByRef.put(new CrossRef(moduleId, className), p);
        }
      } catch (IOException ex) {
        ambiguous = true;
      }
    }
    return new Index(owner, fileByRef, ambiguous);
  }

  /** Hashes only the requested cross-refs; refs whose file is absent are simply omitted. */
  static Map<CrossRef, byte[]> hash(Index index, Collection<CrossRef> wanted) {
    Map<CrossRef, byte[]> out = new HashMap<>();
    for (CrossRef cr : wanted) {
      Path file = index.fileByRef.get(cr);
      if (file == null) {
        continue;
      }
      try {
        out.put(cr, FileHasher.sha256(file));
      } catch (IOException ignored) {
        // a class that vanished mid-build is treated as "changed" by the caller
      }
    }
    return out;
  }
}
