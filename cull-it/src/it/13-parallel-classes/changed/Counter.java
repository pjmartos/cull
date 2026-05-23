package io.pjmartos.cull.it;

public class Counter {

  public int next(int n) {
    // semantically identical; spaces/comments differ so the file hash changes
    return n + 1;
  }

  public int previous(int n) {
    return n - 1;
  }
}
