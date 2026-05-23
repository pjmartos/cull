package io.pjmartos.cull.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public final class RelPath implements Comparable<RelPath> {

  private final String value;

  private RelPath(String value) {
    this.value = value;
  }

  public static RelPath of(String canonical) {
    Objects.requireNonNull(canonical, "canonical");
    if (canonical.isEmpty()) {
      throw new IllegalArgumentException("empty path");
    }
    return new RelPath(canonical);
  }

  public static RelPath relativize(Path base, Path child) {
    Path rel = base.toAbsolutePath().normalize().relativize(child.toAbsolutePath().normalize());
    String s = rel.toString().replace('\\', '/');
    return of(s);
  }

  public String value() {
    return value;
  }

  public Path resolveAgainst(Path base) {
    return base.resolve(Paths.get(value.replace('/', java.io.File.separatorChar))).normalize();
  }

  @Override
  public int compareTo(RelPath o) {
    return value.compareTo(o.value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RelPath && ((RelPath) o).value.equals(value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
