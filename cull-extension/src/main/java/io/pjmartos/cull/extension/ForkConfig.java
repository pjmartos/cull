package io.pjmartos.cull.extension;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Single source of truth for "will Surefire/Failsafe fork a JVM for this project?".
 *
 * <p>cull's per-test attribution depends on tests running in a forked JVM that the agent
 * instruments. When forking is disabled — {@code forkCount} resolves to {@code 0}, or the
 * deprecated {@code forkMode} is {@code never}/{@code none} — the selection engine must degrade to
 * running every test and the config injector must not bother wiring the agent. Those two call sites
 * used to re-derive this independently and had drifted: {@link SelectionEngine} treated {@code
 * forkMode=none} as disabled while {@link ConfigInjector} did not, and <em>both</em> inspected only
 * the plugin-level {@code <configuration>}, silently missing a {@code forkCount} /{@code forkMode}
 * set inside an {@code <execution>}.
 *
 * <p>This helper closes those gaps:
 *
 * <ul>
 *   <li>It evaluates the plugin-level configuration <em>and</em> each execution's configuration
 *       merged over it, using Maven's own {@link Xpp3Dom#mergeXpp3Dom} precedence (execution wins).
 *   <li>It interpolates {@code ${...}} placeholders. POM {@code <properties>} and {@code
 *       ${project.*}} are already resolved by the model builder before the DOM reaches us; what is
 *       <em>not</em> resolved there are {@code -D}/user/settings properties, so those are looked up
 *       against the session (then project) properties here.
 *   <li>When the POM is silent it falls back to Surefire's own {@code forkCount}/{@code forkMode}
 *       user properties (e.g. {@code -DforkCount=0}), which never appear in the config DOM at all.
 * </ul>
 *
 * <p>The bias is deliberately conservative: any candidate configuration that disables forking marks
 * the whole project as non-forking, because the safe failure direction is "run all tests".
 */
final class ForkConfig {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^${}]+)\\}");
  private static final int MAX_INTERPOLATION_PASSES = 5;

  private ForkConfig() {}

  static boolean forkDisabled(MavenProject project, MavenSession session, boolean integration) {
    return forkDisabled(
        project, session, integration ? ConfigInjector.FAILSAFE_GAV : ConfigInjector.SUREFIRE_GAV);
  }

  static boolean forkDisabled(MavenProject project, MavenSession session, String gav) {
    Build build = project == null ? null : project.getBuild();
    if (build == null) return false;
    Plugin plugin = findPlugin(build, gav);
    if (plugin == null) return false;

    Xpp3Dom pluginCfg = asDom(plugin.getConfiguration());
    Accumulator acc = new Accumulator();
    acc.scan(pluginCfg, project, session);
    for (PluginExecution e : plugin.getExecutions()) {
      Xpp3Dom execCfg = asDom(e.getConfiguration());
      if (execCfg == null) {
        continue; // identical to the plugin-level config, already scanned
      }
      acc.scan(effective(pluginCfg, execCfg), project, session);
    }
    if (acc.disabled) {
      return true;
    }

    // The POM said nothing about forkCount/forkMode -> honour Surefire's own
    // user properties (e.g. -DforkCount=0), which never appear in the config DOM.
    if (!acc.sawForkCount && parseForkCountAsZero(property(session, "forkCount"))) {
      return true;
    }
    if (!acc.sawForkMode) {
      String fm = trimToEmpty(property(session, "forkMode"));
      return "never".equalsIgnoreCase(fm) || "none".equalsIgnoreCase(fm);
    }
    return false;
  }

  static boolean parseForkCountAsZero(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    String v = value.trim();
    if ("0".equals(v)) {
      return true;
    }
    if (v.endsWith("C") || v.endsWith("c")) {
      String num = v.substring(0, v.length() - 1);
      try {
        return Double.parseDouble(num) == 0.0;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    try {
      return Double.parseDouble(v) == 0.0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Mutable scan state shared across the plugin-level and per-execution configurations. */
  private static final class Accumulator {
    private boolean disabled;
    private boolean sawForkCount;
    private boolean sawForkMode;

    void scan(Xpp3Dom dom, MavenProject project, MavenSession session) {
      if (dom == null) {
        return;
      }
      String fc = childValue(dom, "forkCount");
      if (fc != null && !fc.trim().isEmpty()) {
        sawForkCount = true;
        if (parseForkCountAsZero(resolve(fc, project, session))) {
          disabled = true;
        }
      }
      String fm = childValue(dom, "forkMode");
      if (fm != null && !fm.trim().isEmpty()) {
        sawForkMode = true;
        String r = trimToEmpty(resolve(fm, project, session));
        if ("never".equalsIgnoreCase(r) || "none".equalsIgnoreCase(r)) {
          disabled = true;
        }
      }
    }
  }

  private static Xpp3Dom effective(Xpp3Dom pluginCfg, Xpp3Dom execCfg) {
    if (pluginCfg == null) {
      return execCfg;
    }
    // mergeXpp3Dom mutates its dominant argument; copy both so the live model is
    // never touched and the execution config keeps precedence over the plugin one.
    return Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(execCfg), new Xpp3Dom(pluginCfg));
  }

  private static Plugin findPlugin(Build build, String gav) {
    Plugin p = build.getPluginsAsMap().get(gav);
    if (p != null) {
      return p;
    }
    for (Plugin candidate : build.getPlugins()) {
      if (gav.equals(candidate.getKey())) {
        return candidate;
      }
    }
    return null;
  }

  private static Xpp3Dom asDom(Object cfg) {
    return cfg instanceof Xpp3Dom ? (Xpp3Dom) cfg : null;
  }

  private static String childValue(Xpp3Dom dom, String name) {
    Xpp3Dom child = dom.getChild(name);
    return child == null ? null : child.getValue();
  }

  private static String resolve(String raw, MavenProject project, MavenSession session) {
    if (raw == null) {
      return null;
    }
    String v = raw.trim();
    for (int pass = 0; pass < MAX_INTERPOLATION_PASSES && v.indexOf("${") >= 0; pass++) {
      Matcher m = PLACEHOLDER.matcher(v);
      StringBuffer sb = new StringBuffer();
      boolean replaced = false;
      while (m.find()) {
        String resolved = lookup(m.group(1), project, session);
        if (resolved == null) {
          m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
        } else {
          replaced = true;
          m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
      }
      m.appendTail(sb);
      v = sb.toString();
      if (!replaced) {
        break; // only unresolvable placeholders remain; do not spin on literals
      }
    }
    return v.trim();
  }

  private static String lookup(String name, MavenProject project, MavenSession session) {
    if (session != null) {
      String v = session.getUserProperties().getProperty(name);
      if (v != null) {
        return v;
      }
      v = session.getSystemProperties().getProperty(name);
      if (v != null) {
        return v;
      }
    }
    if (project != null) {
      String v = project.getProperties().getProperty(name);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  private static String property(MavenSession session, String name) {
    return session == null ? null : lookup(name, null, session);
  }

  private static String trimToEmpty(String s) {
    return s == null ? "" : s.trim();
  }
}
