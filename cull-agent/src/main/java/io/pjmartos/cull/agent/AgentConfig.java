package io.pjmartos.cull.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public final class AgentConfig {

  private final Path observationFile;
  private final boolean ioHooksDisabled;
  private final String forkId;

  private AgentConfig(Path observationFile, boolean ioHooksDisabled, String forkId) {
    this.observationFile = observationFile;
    this.ioHooksDisabled = ioHooksDisabled;
    this.forkId = forkId;
  }

  public static AgentConfig parse(String agentArgs) {
    Map<String, String> args = parseArgs(agentArgs);
    String dir = args.getOrDefault("observations", System.getProperty("cull.observations.dir"));
    if (dir == null) {
      dir = System.getProperty("java.io.tmpdir") + "/cull-observations";
    }
    long pid;
    try {
      pid = ProcessHandle.current().pid();
    } catch (Throwable t) {
      pid = System.currentTimeMillis();
    }
    String forkId = args.getOrDefault("forkId", String.valueOf(pid));
    String fileName = "observations-" + forkId + "-" + Long.toHexString(System.nanoTime()) + ".bin";
    Path file = Paths.get(dir).resolve(fileName);
    boolean ioHooksDisabled =
        "true".equals(args.get("iohooksDisabled"))
            || "true".equals(System.getProperty("cull.iohooks.disabled"));
    return new AgentConfig(file, ioHooksDisabled, forkId);
  }

  public Path observationFile() {
    return observationFile;
  }

  public boolean ioHooksDisabled() {
    return ioHooksDisabled;
  }

  public String forkId() {
    return forkId;
  }

  private static Map<String, String> parseArgs(String s) {
    Map<String, String> out = new HashMap<>();
    if (s == null || s.isEmpty()) {
      return out;
    }
    for (String part : s.split(",")) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        out.put(part.trim(), "true");
      } else {
        out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
      }
    }
    return out;
  }
}
