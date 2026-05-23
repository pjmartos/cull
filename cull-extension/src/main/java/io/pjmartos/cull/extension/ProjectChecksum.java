package io.pjmartos.cull.extension;

import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.Hex;
import io.pjmartos.cull.core.Merkle;
import io.pjmartos.cull.core.TreeHasher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public final class ProjectChecksum {

  private static final Set<String> PHASE_AT_OR_BEFORE_TEST = phaseSetAtOrBeforeTest();

  private ProjectChecksum() {}

  public static String compute(MavenProject project, MavenSession session) {
    return compute(project, session, false);
  }

  /**
   * When {@code excludeReactorSiblingContent} is set, a reactor sibling contributes only its
   * coordinates/version to the checksum, not its compiled bytes — so a content-only change upstream
   * keeps this module's cache key stable; that content change is instead fed into test selection
   * alongside this module's own changed files.
   */
  public static String compute(
      MavenProject project, MavenSession session, boolean excludeReactorSiblingContent) {
    try {
      ChecksumExclusions exclusions = ChecksumExclusions.load(project, session);
      byte[] gCompile =
          hashArtifacts(
              filterByScope(project, "compile", "system"), session, excludeReactorSiblingContent);
      byte[] gRuntime =
          hashArtifacts(filterByScope(project, "runtime"), session, excludeReactorSiblingContent);
      byte[] gTest =
          hashArtifacts(filterByScope(project, "test"), session, excludeReactorSiblingContent);
      byte[] gProvided =
          hashArtifacts(filterByScope(project, "provided"), session, excludeReactorSiblingContent);
      byte[] gPlugins = hashRelevantPlugins(project, session, exclusions);
      byte[] gPolicy = Merkle.sha256(exclusions.fingerprint());
      byte[] root = Merkle.sha256(gCompile, gRuntime, gTest, gProvided, gPlugins, gPolicy);
      return Hex.encode(root);
    } catch (IOException e) {
      throw new IllegalStateException("checksum computation failed", e);
    }
  }

  private static List<Artifact> filterByScope(MavenProject project, String... scopes) {
    Set<String> wanted = Set.of(scopes);
    List<Artifact> out = new ArrayList<>();
    Set<Artifact> all = project.getArtifacts();
    if (all != null) {
      for (Artifact a : all) {
        if (a.getScope() != null && wanted.contains(a.getScope())) {
          out.add(a);
        }
      }
    }
    out.sort(
        Comparator.comparing(Artifact::getGroupId)
            .thenComparing(Artifact::getArtifactId)
            .thenComparing(Artifact::getVersion)
            .thenComparing(a -> a.getClassifier() == null ? "" : a.getClassifier())
            .thenComparing(Artifact::getType));
    return out;
  }

  private static byte[] hashArtifacts(
      List<Artifact> artifacts, MavenSession session, boolean excludeReactorSiblingContent)
      throws IOException {
    List<byte[]> tuples = new ArrayList<>();
    for (Artifact a : artifacts) {
      byte[] fileHash =
          excludeReactorSiblingContent && ReactorSiblings.isReactorSibling(a, session)
              ? new byte[32]
              : artifactHash(a, session);
      byte[] tuple =
          Merkle.tuple(
              bytes(a.getGroupId()),
              bytes(a.getArtifactId()),
              bytes(a.getVersion()),
              bytes(a.getClassifier()),
              bytes(a.getType()),
              fileHash);
      tuples.add(tuple);
    }
    return Merkle.hashGroup(tuples);
  }

  private static byte[] artifactHash(Artifact a, MavenSession session) throws IOException {
    if (a.getFile() != null && a.getFile().isFile()) {
      return FileHasher.sha256(a.getFile().toPath());
    }
    if (session != null) {
      for (MavenProject sibling : session.getProjects()) {
        if (sibling.getGroupId().equals(a.getGroupId())
            && sibling.getArtifactId().equals(a.getArtifactId())
            && sibling.getVersion().equals(a.getVersion())) {
          Path classes = Path.of(sibling.getBuild().getOutputDirectory());
          if (Files.isDirectory(classes)) {
            return TreeHasher.hashDirectory(classes);
          }
        }
      }
    }
    return new byte[32];
  }

  private static byte[] hashRelevantPlugins(
      MavenProject project, MavenSession session, ChecksumExclusions exclusions)
      throws IOException {
    List<Plugin> plugins = new ArrayList<>();
    for (Plugin p : project.getBuildPlugins()) {
      if (isRelevant(p)) {
        plugins.add(p);
      }
    }
    plugins.sort(Comparator.comparing(Plugin::getGroupId).thenComparing(Plugin::getArtifactId));
    Map<String, byte[]> pluginArtifactHashes = pluginArtifactHashes(project);
    Path localRepo = localRepoBase(session);
    List<byte[]> tuples = new ArrayList<>();
    for (Plugin p : plugins) {
      String canonical = canonicalConfig(p, project, session, exclusions);
      String executions = canonicalExecutions(p, project, session, exclusions);
      byte[] fileHash = pluginFileHash(p, pluginArtifactHashes, localRepo);
      byte[] tuple =
          Merkle.tuple(
              bytes(p.getGroupId()),
              bytes(p.getArtifactId()),
              bytes(p.getVersion()),
              bytes(canonical),
              bytes(executions),
              fileHash);
      tuples.add(tuple);
    }
    return Merkle.hashGroup(tuples);
  }

  private static Map<String, byte[]> pluginArtifactHashes(MavenProject project) throws IOException {
    Map<String, byte[]> out = new HashMap<>();
    Set<Artifact> arts = project.getPluginArtifacts();
    if (arts == null) {
      return out;
    }
    for (Artifact a : arts) {
      if (a.getFile() != null && a.getFile().isFile()) {
        out.put(
            pluginKey(a.getGroupId(), a.getArtifactId(), a.getVersion()),
            FileHasher.sha256(a.getFile().toPath()));
      }
    }
    return out;
  }

  private static byte[] pluginFileHash(Plugin p, Map<String, byte[]> resolved, Path localRepo)
      throws IOException {
    byte[] direct = resolved.get(pluginKey(p.getGroupId(), p.getArtifactId(), p.getVersion()));
    if (direct != null) {
      return direct;
    }
    if (localRepo != null && p.getVersion() != null) {
      Path jar =
          localRepo
              .resolve(p.getGroupId().replace('.', '/'))
              .resolve(p.getArtifactId())
              .resolve(p.getVersion())
              .resolve(p.getArtifactId() + "-" + p.getVersion() + ".jar");
      if (Files.isRegularFile(jar)) {
        return FileHasher.sha256(jar);
      }
    }
    return new byte[32];
  }

  private static String pluginKey(String gid, String aid, String version) {
    return (gid == null ? "" : gid)
        + ":"
        + (aid == null ? "" : aid)
        + ":"
        + (version == null ? "" : version);
  }

  private static Path localRepoBase(MavenSession session) {
    if (session == null
        || session.getRequest() == null
        || session.getRequest().getLocalRepository() == null) {
      return null;
    }
    String base = session.getRequest().getLocalRepository().getBasedir();
    return base == null ? null : Path.of(base);
  }

  private static boolean isRelevant(Plugin p) {
    for (PluginExecution e : p.getExecutions()) {
      String phase = e.getPhase();
      if (phase != null && PHASE_AT_OR_BEFORE_TEST.contains(phase)) {
        return true;
      }
      if (phase == null) {
        // executions without explicit phase get the goal's default phase, which we
        // can't resolve here without the plugin descriptor. Be conservative: include.
        return true;
      }
    }
    return false;
  }

  private static String canonicalConfig(
      Plugin p, MavenProject project, MavenSession session, ChecksumExclusions exclusions) {
    Object cfg = p.getConfiguration();
    if (!(cfg instanceof Xpp3Dom)) {
      return "";
    }
    Xpp3Dom dom = interpolate((Xpp3Dom) cfg, project, session);
    exclusions.prune(p.getKey(), dom);
    return canonicalize(dom);
  }

  private static String canonicalExecutions(
      Plugin p, MavenProject project, MavenSession session, ChecksumExclusions exclusions) {
    List<PluginExecution> sorted = new ArrayList<>(p.getExecutions());
    sorted.sort(Comparator.comparing(PluginExecution::getId));
    StringBuilder sb = new StringBuilder();
    for (PluginExecution e : sorted) {
      sb.append(e.getId()).append("|").append(e.getPhase()).append("|");
      List<String> goals = new ArrayList<>(e.getGoals());
      Collections.sort(goals);
      sb.append(String.join(",", goals));
      Object cfg = e.getConfiguration();
      if (cfg instanceof Xpp3Dom) {
        Xpp3Dom dom = interpolate((Xpp3Dom) cfg, project, session);
        exclusions.prune(p.getKey(), dom);
        sb.append("|").append(canonicalize(dom));
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  static Xpp3Dom interpolate(Xpp3Dom in, MavenProject project, MavenSession session) {
    if (in == null) {
      return null;
    }
    Xpp3Dom out = new Xpp3Dom(in.getName());
    String[] attrNames = in.getAttributeNames();
    if (attrNames != null) {
      for (String a : attrNames) {
        out.setAttribute(a, expand(in.getAttribute(a), project, session));
      }
    }
    Xpp3Dom[] children = in.getChildren();
    if (children == null || children.length == 0) {
      out.setValue(expand(in.getValue(), project, session));
      return out;
    }
    for (Xpp3Dom child : children) {
      out.addChild(interpolate(child, project, session));
    }
    return out;
  }

  static String expand(String value, MavenProject project, MavenSession session) {
    if (value == null) {
      return null;
    }
    String s = value;
    for (int i = 0; i < 100 && s.contains("${"); i++) {
      StringBuilder out = new StringBuilder(s.length());
      int pos = 0;
      while (pos < s.length()) {
        int start = s.indexOf("${", pos);
        if (start < 0) {
          out.append(s, pos, s.length());
          break;
        }
        int end = s.indexOf('}', start);
        if (end < 0) {
          out.append(s, pos, s.length());
          break;
        }
        out.append(s, pos, start);
        String key = s.substring(start + 2, end);
        String resolved = resolveProperty(key, project, session);
        if (resolved == null) {
          out.append(s, start, end + 1);
        } else {
          out.append(resolved);
        }
        pos = end + 1;
      }
      String next = out.toString();
      if (next.equals(s)) {
        return next;
      }
      s = next;
    }
    return s;
  }

  private static String resolveProperty(String key, MavenProject project, MavenSession session) {
    if (project != null) {
      if ("project.version".equals(key) || "version".equals(key)) {
        return project.getVersion();
      }
      if ("project.groupId".equals(key) || "groupId".equals(key)) {
        return project.getGroupId();
      }
      if ("project.artifactId".equals(key) || "artifactId".equals(key)) {
        return project.getArtifactId();
      }
      if ("project.basedir".equals(key) || "basedir".equals(key)) {
        return project.getBasedir() == null ? null : project.getBasedir().getAbsolutePath();
      }
      String pv = project.getProperties() == null ? null : project.getProperties().getProperty(key);
      if (pv != null) {
        return pv;
      }
    }
    if (session != null) {
      if (session.getUserProperties() != null) {
        String uv = session.getUserProperties().getProperty(key);
        if (uv != null) {
          return uv;
        }
      }
      if (session.getSystemProperties() != null) {
        return session.getSystemProperties().getProperty(key);
      }
    }
    return null;
  }

  static String canonicalize(Xpp3Dom node) {
    StringBuilder sb = new StringBuilder();
    canonicalizeInto(node, sb);
    return sb.toString();
  }

  private static void canonicalizeInto(Xpp3Dom node, StringBuilder sb) {
    sb.append('<').append(node.getName());
    String[] attrNames = node.getAttributeNames();
    if (attrNames != null && attrNames.length > 0) {
      List<String> sortedAttrs = new ArrayList<>();
      Collections.addAll(sortedAttrs, attrNames);
      Collections.sort(sortedAttrs);
      for (String a : sortedAttrs) {
        sb.append(' ').append(a).append("=\"").append(node.getAttribute(a)).append('"');
      }
    }
    Xpp3Dom[] children = node.getChildren();
    if (children == null || children.length == 0) {
      String value = node.getValue();
      if (value == null) {
        sb.append("/>");
        return;
      }
      sb.append('>').append(value.trim()).append("</").append(node.getName()).append('>');
      return;
    }
    sb.append('>');
    Xpp3Dom[] sortedChildren = children.clone();
    java.util.Arrays.sort(
        sortedChildren,
        Comparator.comparing(Xpp3Dom::getName).thenComparing(ProjectChecksum::canonicalize));
    for (Xpp3Dom child : sortedChildren) {
      canonicalizeInto(child, sb);
    }
    sb.append("</").append(node.getName()).append('>');
  }

  private static byte[] bytes(String s) {
    return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
  }

  // Plugins bound at pre-integration-test / integration-test / verify run AFTER
  // unit tests and produce nothing unit-test compilation or execution consumes,
  // so they are deliberately excluded from the checksum: an unrelated Failsafe /
  // IT / docker-plugin config change must not bust the unit-test cache. This is
  // correct for unit-test selection (cull:select). It would be a real gap only
  // for IT selection (cull:select-it), which would need the IT phases included;
  // revisit this set if/when the Failsafe selection path is exercised.
  private static Set<String> phaseSetAtOrBeforeTest() {
    Set<String> s = new LinkedHashSet<>();
    s.add("validate");
    s.add("initialize");
    s.add("generate-sources");
    s.add("process-sources");
    s.add("generate-resources");
    s.add("process-resources");
    s.add("compile");
    s.add("process-classes");
    s.add("generate-test-sources");
    s.add("process-test-sources");
    s.add("generate-test-resources");
    s.add("process-test-resources");
    s.add("test-compile");
    s.add("process-test-classes");
    s.add("test");
    return s;
  }
}
