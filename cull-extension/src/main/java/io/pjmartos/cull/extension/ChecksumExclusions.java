package io.pjmartos.cull.extension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Removes sources of build-to-build variability from a plugin's configuration before it feeds the
 * project checksum, so test-impact-analysis stays effective across builds that changed nothing.
 *
 * <p>Two layers apply. First, an always-on surgical removal of the segments cull itself injects
 * into Surefire/Failsafe (its {@code -javaagent} argLine fragment, its listener classpath entry,
 * its {@code cull.*} system properties and selected-test placeholder) so cull does not rotate its
 * own cache key with machine- and version-specific paths. Second, user-declared exclusions read
 * from a {@code .cullignore} file at the reactor root, each line {@code
 * groupId:artifactId#dotted.path}, where a path element may be {@code *} (one level) or {@code **}
 * (any nesting); a matched element and its whole subtree are dropped. Lines that are blank or start
 * with {@code #} are ignored.
 *
 * <p>The normalized exclusion policy is folded into the checksum (see {@link #fingerprint()}) so a
 * change to the policy forces exactly one cold rebuild and is stable thereafter.
 */
final class ChecksumExclusions {

  private static final String POLICY_VERSION = "v1";
  private static final String CULL_AGENT_MARKER = "cull-agent";
  private static final String CULL_LISTENER_MARKER = "cull-agent-listener";
  private static final String CULL_LISTENER_PACKAGE = "io.pjmartos.cull.agent.listeners.";

  private final List<Pattern> patterns;
  private final List<String> normalizedLines;

  private ChecksumExclusions(List<Pattern> patterns, List<String> normalizedLines) {
    this.patterns = patterns;
    this.normalizedLines = normalizedLines;
  }

  static ChecksumExclusions load(MavenProject project, MavenSession session) {
    Path ignoreFile = locate(project, session);
    List<Pattern> patterns = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    if (ignoreFile != null && Files.isRegularFile(ignoreFile)) {
      List<String> raw;
      try {
        raw = Files.readAllLines(ignoreFile, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("failed to read " + ignoreFile, e);
      }
      for (String line : raw) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
          continue;
        }
        Pattern p = Pattern.parse(trimmed);
        if (p != null) {
          patterns.add(p);
          lines.add(trimmed);
        }
      }
    }
    Collections.sort(lines);
    return new ChecksumExclusions(patterns, lines);
  }

  private static Path locate(MavenProject project, MavenSession session) {
    Path root = null;
    if (session != null && session.getRequest() != null) {
      java.io.File mm = session.getRequest().getMultiModuleProjectDirectory();
      if (mm != null) {
        root = mm.toPath();
      }
    }
    if (root == null && project != null && project.getBasedir() != null) {
      root = project.getBasedir().toPath();
    }
    return root == null ? null : root.resolve(".cullignore");
  }

  byte[] fingerprint() {
    StringBuilder sb =
        new StringBuilder("cull-checksum-policy:").append(POLICY_VERSION).append('\n');
    for (String line : normalizedLines) {
      sb.append(line).append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  void prune(String pluginKey, Xpp3Dom config) {
    if (config == null) {
      return;
    }
    if (ConfigInjector.SUREFIRE_GAV.equals(pluginKey)
        || ConfigInjector.FAILSAFE_GAV.equals(pluginKey)) {
      stripSelfInjected(config);
    }
    List<Pattern> applicable = new ArrayList<>();
    for (Pattern p : patterns) {
      if (p.matchesPlugin(pluginKey)) {
        applicable.add(p);
      }
    }
    if (!applicable.isEmpty()) {
      pruneMatching(config, new ArrayList<>(), applicable);
    }
  }

  private static void pruneMatching(Xpp3Dom node, List<String> path, List<Pattern> ps) {
    for (int i = node.getChildCount() - 1; i >= 0; i--) {
      Xpp3Dom child = node.getChild(i);
      List<String> childPath = new ArrayList<>(path);
      childPath.add(child.getName());
      if (anyMatch(ps, childPath)) {
        node.removeChild(i);
      } else {
        pruneMatching(child, childPath, ps);
      }
    }
  }

  private static boolean anyMatch(List<Pattern> ps, List<String> path) {
    for (Pattern p : ps) {
      if (p.matchesPath(path)) {
        return true;
      }
    }
    return false;
  }

  private static void stripSelfInjected(Xpp3Dom config) {
    Xpp3Dom argLine = config.getChild("argLine");
    if (argLine != null) {
      String stripped = withoutCullSegment(argLine.getValue());
      if (stripped == null || stripped.trim().isEmpty()) {
        removeNamed(config, "argLine");
      } else {
        argLine.setValue(stripped);
      }
    }

    Xpp3Dom acp = config.getChild("additionalClasspathElements");
    if (acp != null) {
      for (int i = acp.getChildCount() - 1; i >= 0; i--) {
        String v = acp.getChild(i).getValue();
        if (v != null && v.contains(CULL_LISTENER_MARKER)) {
          acp.removeChild(i);
        }
      }
      if (acp.getChildCount() == 0) {
        removeNamed(config, "additionalClasspathElements");
      }
    }

    Xpp3Dom sysProps = config.getChild("systemPropertyVariables");
    if (sysProps != null) {
      for (int i = sysProps.getChildCount() - 1; i >= 0; i--) {
        if (sysProps.getChild(i).getName().startsWith("cull.")) {
          sysProps.removeChild(i);
        }
      }
      if (sysProps.getChildCount() == 0) {
        removeNamed(config, "systemPropertyVariables");
      }
    }

    Xpp3Dom props = config.getChild("properties");
    if (props != null) {
      for (int i = props.getChildCount() - 1; i >= 0; i--) {
        Xpp3Dom prop = props.getChild(i);
        Xpp3Dom name = prop.getChild("name");
        Xpp3Dom value = prop.getChild("value");
        if (name != null
            && "listener".equals(name.getValue())
            && value != null
            && isOnlyCullListeners(value.getValue())) {
          props.removeChild(i);
        }
      }
      if (props.getChildCount() == 0) {
        removeNamed(config, "properties");
      }
    }

    Xpp3Dom test = config.getChild("test");
    if (test != null) {
      String v = test.getValue();
      if ("${cull.selected.tests}".equals(v) || "${cull.selected.tests.it}".equals(v)) {
        removeNamed(config, "test");
      }
    }

    removeIfFalse(config, "failIfNoTests");
    removeIfFalse(config, "failIfNoSpecifiedTests");
  }

  private static void removeIfFalse(Xpp3Dom config, String name) {
    Xpp3Dom child = config.getChild(name);
    if (child != null && child.getValue() != null && "false".equals(child.getValue().trim())) {
      removeNamed(config, name);
    }
  }

  private static boolean isOnlyCullListeners(String value) {
    if (value == null || value.trim().isEmpty()) {
      return false;
    }
    for (String fqcn : value.split(",")) {
      if (!fqcn.trim().startsWith(CULL_LISTENER_PACKAGE)) {
        return false;
      }
    }
    return true;
  }

  private static String withoutCullSegment(String argLine) {
    if (argLine == null) {
      return null;
    }
    String t = argLine.trim();
    if (!t.startsWith("-javaagent:")) {
      return argLine;
    }
    // The injected segment is "-javaagent:<agentJarPath>[=<agentArgs>]" prepended
    // ahead of any user content. The agent jar path can contain spaces (e.g. a
    // Windows user home under "C:\Users\First Last"), so the segment boundary
    // cannot be the first space; it is the agent jar's ".jar" plus the optional
    // space-free "=<agentArgs>". Cutting on the first space would leave cull's
    // machine-specific path in the checksum and rotate cull's own cache key.
    int marker = t.indexOf(CULL_AGENT_MARKER);
    if (marker < 0) {
      return argLine;
    }
    int jarEnd = t.indexOf(".jar", marker);
    if (jarEnd < 0) {
      return argLine;
    }
    int segEnd = jarEnd + 4;
    if (segEnd < t.length() && t.charAt(segEnd) == '=') {
      int sp = t.indexOf(' ', segEnd);
      segEnd = sp < 0 ? t.length() : sp;
    }
    String remainder = segEnd >= t.length() ? "" : t.substring(segEnd).trim();
    return stripLeadingArgLineDelegation(remainder);
  }

  // cull appends "@{argLine}" right after its own agent segment so that a
  // late-bound argLine property (the JaCoCo prepare-agent idiom) is still
  // honored at surefire execution time. That delegation marker is part of
  // cull's injection, so it is removed here as well — otherwise merely
  // installing cull would add "@{argLine}" where a project had no argLine and
  // rotate the checksum once, and the strip would not fully reverse cull's
  // mutation. The delegation marker is selection-neutral anyway (it resolves
  // to a coverage agent's -javaagent string, which does not change which tests
  // should run), so dropping it from the checksum is sound whether it was
  // cull's or the user's.
  private static String stripLeadingArgLineDelegation(String s) {
    for (String ref : new String[] {"@{argLine}", "${argLine}"}) {
      if (s.equals(ref)) {
        return "";
      }
      if (s.startsWith(ref + " ")) {
        return s.substring(ref.length() + 1).trim();
      }
    }
    return s;
  }

  private static void removeNamed(Xpp3Dom parent, String name) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      if (name.equals(parent.getChild(i).getName())) {
        parent.removeChild(i);
        return;
      }
    }
  }

  static final class Pattern {
    private final String group;
    private final String artifact;
    private final String[] tokens;

    private Pattern(String group, String artifact, String... tokens) {
      this.group = group;
      this.artifact = artifact;
      this.tokens = tokens;
    }

    static Pattern parse(String line) {
      int hash = line.indexOf('#');
      if (hash <= 0 || hash == line.length() - 1) {
        return null;
      }
      String gav = line.substring(0, hash).trim();
      String pathExpr = line.substring(hash + 1).trim();
      String[] gavParts = gav.split(":");
      if (gavParts.length < 2) {
        return null;
      }
      String[] tokens = pathExpr.split("\\.");
      if (tokens.length == 0) {
        return null;
      }
      return new Pattern(gavParts[0].trim(), gavParts[1].trim(), tokens);
    }

    boolean matchesPlugin(String pluginKey) {
      int colon = pluginKey.indexOf(':');
      String g = colon < 0 ? pluginKey : pluginKey.substring(0, colon);
      String a = colon < 0 ? "" : pluginKey.substring(colon + 1);
      return segMatch(group, g) && segMatch(artifact, a);
    }

    private static boolean segMatch(String pat, String actual) {
      return "*".equals(pat) || pat.equals(actual);
    }

    boolean matchesPath(List<String> path) {
      return wild(0, path, 0);
    }

    private boolean wild(int ti, List<String> path, int pi) {
      if (ti == tokens.length) {
        return pi == path.size();
      }
      String tok = tokens[ti];
      if ("**".equals(tok)) {
        for (int k = pi; k <= path.size(); k++) {
          if (wild(ti + 1, path, k)) {
            return true;
          }
        }
        return false;
      }
      if (pi < path.size() && ("*".equals(tok) || tok.equals(path.get(pi)))) {
        return wild(ti + 1, path, pi + 1);
      }
      return false;
    }
  }
}
