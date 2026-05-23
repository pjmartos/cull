package io.pjmartos.cull.agent.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConstantPoolBuilderTest {

  private static final int CP_UTF8 = 1;
  private static final int CP_CLASS = 7;
  private static final int CP_FIELDREF = 9;
  private static final int CP_METHODREF = 10;
  private static final int CP_IFACE_METHODREF = 11;
  private static final int CP_NAME_AND_TYPE = 12;

  private static final class Pool {
    final ByteArrayOutputStream b = new ByteArrayOutputStream();
    int count = 1;

    int utf8(String s) {
      byte[] d = s.getBytes(StandardCharsets.UTF_8);
      b.write(CP_UTF8);
      u2(d.length);
      b.write(d, 0, d.length);
      return count++;
    }

    int ref(int tag, int a, int c) {
      b.write(tag);
      u2(a);
      u2(c);
      return count++;
    }

    int classEntry(int nameIdx) {
      b.write(CP_CLASS);
      u2(nameIdx);
      return count++;
    }

    private void u2(int v) {
      b.write((v >>> 8) & 0xFF);
      b.write(v & 0xFF);
    }
  }

  private static ConstantPoolBuilder builderWithCollidingRef(int refTag) {
    Pool p = new Pool();
    int owner = p.utf8("Owner");
    int classIdx = p.classEntry(owner);
    int name = p.utf8("m");
    int desc = p.utf8("()V");
    int nat = p.ref(CP_NAME_AND_TYPE, name, desc);
    p.ref(refTag, classIdx, nat);
    return new ConstantPoolBuilder(p.b.toByteArray(), p.count);
  }

  @Test
  void fieldrefSharingClassAndNatDoesNotPoisonMethodRef() throws IOException {
    ConstantPoolBuilder cp = builderWithCollidingRef(CP_FIELDREF);
    int fieldrefIndex = 6;

    int ref = cp.methodRef("Owner", "m", "()V");

    assertNotEquals(fieldrefIndex, ref, "methodRef must not resolve to a colliding Fieldref index");
    assertEquals(7, ref, "a fresh CP_Methodref entry is appended");
    byte[] out = cp.serialize();
    assertEquals(
        CP_METHODREF,
        out[originalLength()] & 0xFF,
        "the appended entry must carry the Methodref tag");
    assertEquals(8, cp.count());
  }

  @Test
  void interfaceMethodrefSharingClassAndNatDoesNotPoisonMethodRef() throws IOException {
    ConstantPoolBuilder cp = builderWithCollidingRef(CP_IFACE_METHODREF);

    int ref = cp.methodRef("Owner", "m", "()V");

    assertNotEquals(6, ref, "methodRef must not resolve to a colliding InterfaceMethodref index");
    assertEquals(7, ref);
    byte[] out = cp.serialize();
    assertEquals(CP_METHODREF, out[originalLength()] & 0xFF);
  }

  @Test
  void existingMethodrefIsStillDeduplicated() throws IOException {
    ConstantPoolBuilder cp = builderWithCollidingRef(CP_METHODREF);
    int existingMethodrefIndex = 6;

    int ref = cp.methodRef("Owner", "m", "()V");

    assertEquals(existingMethodrefIndex, ref, "a genuine Methodref with the same shape is reused");
    assertEquals(7, cp.count(), "no new entry appended when an equivalent Methodref exists");
    assertEquals(0, cp.serialize().length - originalLength(), "no extra bytes emitted");
  }

  @Test
  void distinctMethodRefsGetDistinctIndices() {
    ConstantPoolBuilder cp = builderWithCollidingRef(CP_FIELDREF);
    int a = cp.methodRef("Owner", "m", "()V");
    int b = cp.methodRef("Owner", "other", "()V");
    assertNotEquals(a, b);
    assertTrue(b > a);
  }

  // The constant-pool entries built by builderWithCollidingRef are, in order:
  // utf8 "Owner" (1+2+5), Class (1+2), utf8 "m" (1+2+1), utf8 "()V" (1+2+3),
  // NameAndType (1+4), then the colliding ref (1+4). Every ref kind here is 5
  // bytes, so the original region length is constant regardless of ref tag.
  private static int originalLength() {
    return (1 + 2 + 5) + (1 + 2) + (1 + 2 + 1) + (1 + 2 + 3) + (1 + 4) + (1 + 4);
  }
}
