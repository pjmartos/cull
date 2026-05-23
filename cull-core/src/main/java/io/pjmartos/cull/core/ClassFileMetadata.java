package io.pjmartos.cull.core;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ClassFileMetadata {

  private final String thisClass;
  private final String superClass;
  private final List<String> interfaces;
  private final Set<String> referencedClasses;
  private final int classFileVersion;

  public ClassFileMetadata(
      String thisClass,
      String superClass,
      List<String> interfaces,
      Set<String> referencedClasses,
      int classFileVersion) {
    this.thisClass = thisClass;
    this.superClass = superClass;
    this.interfaces = Collections.unmodifiableList(interfaces);
    this.referencedClasses = Collections.unmodifiableSet(referencedClasses);
    this.classFileVersion = classFileVersion;
  }

  public String thisClass() {
    return thisClass;
  }

  public String superClass() {
    return superClass;
  }

  public List<String> interfaces() {
    return interfaces;
  }

  public Set<String> referencedClasses() {
    return referencedClasses;
  }

  public int classFileVersion() {
    return classFileVersion;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ClassFileMetadata)) return false;
    ClassFileMetadata m = (ClassFileMetadata) o;
    return classFileVersion == m.classFileVersion
        && Objects.equals(thisClass, m.thisClass)
        && Objects.equals(superClass, m.superClass)
        && interfaces.equals(m.interfaces)
        && referencedClasses.equals(m.referencedClasses);
  }

  @Override
  public int hashCode() {
    return Objects.hash(thisClass, superClass, interfaces, referencedClasses, classFileVersion);
  }
}
