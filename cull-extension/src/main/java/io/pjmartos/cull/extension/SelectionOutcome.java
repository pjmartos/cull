package io.pjmartos.cull.extension;

import java.nio.file.Path;
import java.util.Set;

public final class SelectionOutcome {

  public final Set<String> selected;
  public final boolean wildcard;
  public final String sessionId;
  public final Path stagingDir;
  public final String projectChecksum;
  public final String reasonForWildcard;

  public SelectionOutcome(
      Set<String> selected,
      boolean wildcard,
      String sessionId,
      Path stagingDir,
      String projectChecksum,
      String reasonForWildcard) {
    this.selected = selected;
    this.wildcard = wildcard;
    this.sessionId = sessionId;
    this.stagingDir = stagingDir;
    this.projectChecksum = projectChecksum;
    this.reasonForWildcard = reasonForWildcard;
  }

  public static SelectionOutcome wildcard(String reason) {
    return new SelectionOutcome(Set.of(), true, null, null, null, reason);
  }

  public static final String NO_MATCH_PATTERN = "cull.NoSuchTest__";

  public String selectedTestsString() {
    if (wildcard) {
      return "*";
    }
    if (selected.isEmpty()) {
      return NO_MATCH_PATTERN;
    }
    return String.join(",", selected);
  }
}
