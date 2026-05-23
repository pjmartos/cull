package io.pjmartos.cull.it;

public class Consumer {

  public int doubled() {
    return new Shared().magic() * 2;
  }
}
