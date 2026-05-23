package io.pjmartos.cull.extension;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.maven.project.MavenProject;

public final class CullSessionRegistry {

  private static final ConcurrentMap<String, CullSession> SESSIONS = new ConcurrentHashMap<>();

  private CullSessionRegistry() {}

  public static void put(MavenProject p, CullSession session, boolean integration) {
    SESSIONS.put(key(p, integration), session);
  }

  public static CullSession get(MavenProject p, boolean integration) {
    return SESSIONS.get(key(p, integration));
  }

  public static CullSession remove(MavenProject p, boolean integration) {
    return SESSIONS.remove(key(p, integration));
  }

  public static String key(MavenProject p, boolean integration) {
    return p.getGroupId()
        + ":"
        + p.getArtifactId()
        + ":"
        + p.getVersion()
        + (integration ? ":it" : ":ut");
  }
}
