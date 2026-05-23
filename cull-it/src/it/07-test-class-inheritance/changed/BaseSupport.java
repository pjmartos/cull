package io.pjmartos.cull.it;

abstract class BaseSupport {

  protected boolean shouldBeOpen() {
    return false;
  }

  protected String label() {
    return "base";
  }
}
