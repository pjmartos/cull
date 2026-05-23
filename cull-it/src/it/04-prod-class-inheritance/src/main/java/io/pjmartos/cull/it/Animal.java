package io.pjmartos.cull.it;

public abstract class Animal {

  public abstract String voice();

  public String describe() {
    return "an animal that says " + voice();
  }
}
