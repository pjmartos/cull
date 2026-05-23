package io.pjmartos.cull.extension.crossfixture;

/**
 * A downstream-local class whose compiled bytecode statically references {@link StaticRefV} (via
 * the constant pool) without the test ever loading {@code StaticRefV} at runtime — the input for
 * the static-closure-into-siblings coverage.
 */
public class StaticRefW {

  public StaticRefV make() {
    return new StaticRefV();
  }
}
