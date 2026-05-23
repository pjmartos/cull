package io.pjmartos.cull.core;

import java.util.Objects;

/**
 * A reference from a test to a class owned by another reactor module: the module's {@code
 * groupId:artifactId} (version-independent — version changes are caught by the structural checksum)
 * plus the fully-qualified class name.
 */
public final class CrossRef implements Comparable<CrossRef> {

  private final String moduleId;
  private final String className;

  public CrossRef(String moduleId, String className) {
    this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
    this.className = Objects.requireNonNull(className, "className");
  }

  public String moduleId() {
    return moduleId;
  }

  public String className() {
    return className;
  }

  @Override
  public int compareTo(CrossRef o) {
    int c = moduleId.compareTo(o.moduleId);
    return c != 0 ? c : className.compareTo(o.className);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CrossRef)) {
      return false;
    }
    CrossRef other = (CrossRef) o;
    return moduleId.equals(other.moduleId) && className.equals(other.className);
  }

  @Override
  public int hashCode() {
    return moduleId.hashCode() * 31 + className.hashCode();
  }

  @Override
  public String toString() {
    return moduleId + "/" + className;
  }
}
