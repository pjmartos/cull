package io.pjmartos.cull.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Decides which compiled classes count as test classes. Resolution order: an explicit {@code
 * cull.test.includes}/{@code cull.it.includes} property; otherwise the project's Surefire/Failsafe
 * {@code <includes>}/{@code <excludes>}; otherwise the built-in name heuristic. The built-in path
 * is byte-for-byte the historical behavior so projects that configure nothing are unaffected.
 */
final class TestNameFilter {

  private final boolean integration;
  private final List<Pattern> includes;
  private final List<Pattern> excludes;

  private TestNameFilter(boolean integration, List<Pattern> includes, List<Pattern> excludes) {
    this.integration = integration;
    this.includes = includes;
    this.excludes = excludes;
  }

  static TestNameFilter forProject(
      MavenProject project, MavenSession session, boolean integration) {
    String prop = CullProperties.testIncludes(session, integration);
    if (prop != null && !prop.trim().isEmpty()) {
      return new TestNameFilter(integration, compile(splitCsv(prop)), List.of());
    }
    String gav = integration ? ConfigInjector.FAILSAFE_GAV : ConfigInjector.SUREFIRE_GAV;
    Plugin plugin =
        project.getBuild() == null ? null : project.getBuild().getPluginsAsMap().get(gav);
    if (plugin != null && plugin.getConfiguration() instanceof Xpp3Dom) {
      Xpp3Dom cfg = (Xpp3Dom) plugin.getConfiguration();
      List<String> inc = childValues(cfg, "includes", "include");
      List<String> exc = childValues(cfg, "excludes", "exclude");
      if (!inc.isEmpty() || !exc.isEmpty()) {
        return new TestNameFilter(integration, compile(inc), compile(exc));
      }
    }
    return new TestNameFilter(integration, null, null);
  }

  boolean matches(String fqcn, String relativePathNoExtension) {
    if (includes == null) {
      return matchesBuiltinHeuristic(fqcn);
    }
    for (Pattern e : excludes) {
      if (e.matcher(relativePathNoExtension).matches()) {
        return false;
      }
    }
    for (Pattern i : includes) {
      if (i.matcher(relativePathNoExtension).matches()) {
        return true;
      }
    }
    return false;
  }

  private boolean matchesBuiltinHeuristic(String fqcn) {
    if (integration) {
      return fqcn.endsWith("IT") || fqcn.endsWith("ITCase") || fqcn.startsWith("IT");
    }
    return fqcn.endsWith("Test") || fqcn.endsWith("Tests") || fqcn.startsWith("Test");
  }

  private static List<String> childValues(Xpp3Dom cfg, String container, String item) {
    Xpp3Dom c = cfg.getChild(container);
    if (c == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Xpp3Dom child : c.getChildren(item)) {
      String v = child.getValue();
      if (v != null && !v.trim().isEmpty()) {
        out.add(v.trim());
      }
    }
    return out;
  }

  private static List<String> splitCsv(String s) {
    List<String> out = new ArrayList<>();
    for (String part : s.split(",")) {
      String t = part.trim();
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return out;
  }

  private static List<Pattern> compile(List<String> globs) {
    List<Pattern> out = new ArrayList<>();
    for (String g : globs) {
      out.add(Pattern.compile(globToRegex(g)));
    }
    return out;
  }

  // Surefire/Failsafe selector semantics over the slash-separated class path with the
  // source/class extension stripped: '**' spans directories, '*' stays within one segment,
  // '?' is a single non-separator char, and %regex[...] passes through verbatim.
  static String globToRegex(String glob) {
    String g = glob.replace('\\', '/').trim();
    if (g.startsWith("%regex[") && g.endsWith("]")) {
      return stripExtension(g.substring("%regex[".length(), g.length() - 1));
    }
    g = stripExtension(g);
    StringBuilder re = new StringBuilder("^");
    int i = 0;
    while (i < g.length()) {
      char ch = g.charAt(i);
      if (ch == '*' && i + 2 < g.length() && g.charAt(i + 1) == '*' && g.charAt(i + 2) == '/') {
        re.append("(?:.*/)?");
        i += 3;
      } else if (ch == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
        re.append(".*");
        i += 2;
      } else if (ch == '*') {
        re.append("[^/]*");
        i++;
      } else if (ch == '?') {
        re.append("[^/]");
        i++;
      } else {
        if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
          re.append('\\');
        }
        re.append(ch);
        i++;
      }
    }
    return re.append('$').toString();
  }

  private static String stripExtension(String s) {
    if (s.endsWith(".java")) {
      return s.substring(0, s.length() - ".java".length());
    }
    if (s.endsWith(".class")) {
      return s.substring(0, s.length() - ".class".length());
    }
    return s;
  }
}
