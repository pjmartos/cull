package io.pjmartos.cull.extension.crossfixture;

/**
 * Stands in for an upstream (reactor-sibling) class. Referenced by {@link StaticRefW} only through
 * the constant pool, so the cross-module dependency can be exercised via static closure without any
 * runtime class load of this type.
 */
public class StaticRefV {

  public int v() {
    return 1;
  }
}
