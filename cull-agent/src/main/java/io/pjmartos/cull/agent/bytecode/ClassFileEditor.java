package io.pjmartos.cull.agent.bytecode;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public final class ClassFileEditor {

  public interface MethodRewriter {
    byte[] rewriteCode(
        String name,
        String descriptor,
        int accessFlags,
        int maxStack,
        int maxLocals,
        byte[] code,
        ConstantPoolBuilder cp,
        RewriteOutcome out);
  }

  public static final class RewriteOutcome {
    public boolean rewrote;
    public int newMaxStack;
  }

  public static final class MethodTarget {
    final String name;
    final String descriptor;

    public MethodTarget(String name, String descriptor) {
      this.name = name;
      this.descriptor = descriptor;
    }
  }

  private ClassFileEditor() {}

  public static byte[] instrument(
      byte[] classBytes, List<MethodTarget> targets, MethodRewriter rewriter) throws IOException {
    DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(classBytes));
    if (in.readInt() != 0xCAFEBABE) {
      throw new IOException("not a class file");
    }
    int minor = in.readUnsignedShort();
    int major = in.readUnsignedShort();
    int cpCount = in.readUnsignedShort();

    int cpStart = 10;
    int cpEnd = scanCpEnd(classBytes, cpStart, cpCount);
    byte[] cpBytes = new byte[cpEnd - cpStart];
    System.arraycopy(classBytes, cpStart, cpBytes, 0, cpBytes.length);

    ConstantPoolBuilder cp = new ConstantPoolBuilder(cpBytes, cpCount);

    int skipped = in.skipBytes(cpBytes.length);
    if (skipped != cpBytes.length) {
      throw new IOException("short skip during CP advance");
    }

    int accessFlags = in.readUnsignedShort();
    int thisClass = in.readUnsignedShort();
    int superClass = in.readUnsignedShort();
    int ifaceCount = in.readUnsignedShort();
    int[] interfaces = new int[ifaceCount];
    for (int i = 0; i < ifaceCount; i++) {
      interfaces[i] = in.readUnsignedShort();
    }

    FieldsOrAttributesBlob fields =
        readFieldsOrMethods(in, /*isMethod*/ false, targets, rewriter, cp);
    FieldsOrAttributesBlob methods =
        readFieldsOrMethods(in, /*isMethod*/ true, targets, rewriter, cp);
    byte[] classAttributes = readAttributes(in);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeInt(0xCAFEBABE);
    d.writeShort(minor);
    d.writeShort(major);
    d.writeShort(cp.count());
    d.write(cp.serialize());
    d.writeShort(accessFlags);
    d.writeShort(thisClass);
    d.writeShort(superClass);
    d.writeShort(ifaceCount);
    for (int iface : interfaces) {
      d.writeShort(iface);
    }
    d.writeShort(fields.count);
    d.write(fields.bytes);
    d.writeShort(methods.count);
    d.write(methods.bytes);
    d.write(classAttributes);
    return out.toByteArray();
  }

  private static FieldsOrAttributesBlob readFieldsOrMethods(
      DataInputStream in,
      boolean isMethod,
      List<MethodTarget> targets,
      MethodRewriter rewriter,
      ConstantPoolBuilder cp)
      throws IOException {
    int count = in.readUnsignedShort();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    for (int i = 0; i < count; i++) {
      int access = in.readUnsignedShort();
      int nameIdx = in.readUnsignedShort();
      int descIdx = in.readUnsignedShort();
      d.writeShort(access);
      d.writeShort(nameIdx);
      d.writeShort(descIdx);

      String name = isMethod ? cp.utf8(nameIdx) : null;
      String desc = isMethod ? cp.utf8(descIdx) : null;
      boolean wantRewrite = false;
      if (isMethod) {
        for (MethodTarget t : targets) {
          if (t.name.equals(name) && t.descriptor.equals(desc)) {
            wantRewrite = true;
            break;
          }
        }
      }

      int attrCount = in.readUnsignedShort();
      d.writeShort(attrCount);
      for (int a = 0; a < attrCount; a++) {
        int aNameIdx = in.readUnsignedShort();
        int aLen = in.readInt();
        String aName = cp.utf8(aNameIdx);
        byte[] body = new byte[aLen];
        in.readFully(body);
        if (wantRewrite && "Code".equals(aName)) {
          byte[] newBody = rewriteCodeAttr(body, name, desc, access, cp, rewriter);
          d.writeShort(aNameIdx);
          d.writeInt(newBody.length);
          d.write(newBody);
        } else {
          d.writeShort(aNameIdx);
          d.writeInt(aLen);
          d.write(body);
        }
      }
    }
    FieldsOrAttributesBlob blob = new FieldsOrAttributesBlob();
    blob.count = count;
    blob.bytes = out.toByteArray();
    return blob;
  }

  private static byte[] rewriteCodeAttr(
      byte[] body,
      String mname,
      String mdesc,
      int access,
      ConstantPoolBuilder cp,
      MethodRewriter rewriter)
      throws IOException {
    DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(body));
    int maxStack = d.readUnsignedShort();
    int maxLocals = d.readUnsignedShort();
    long codeLen = d.readInt() & 0xFFFFFFFFL;
    byte[] code = new byte[(int) codeLen];
    d.readFully(code);

    RewriteOutcome out = new RewriteOutcome();
    byte[] newCode = rewriter.rewriteCode(mname, mdesc, access, maxStack, maxLocals, code, cp, out);
    if (!out.rewrote) {
      return body;
    }
    int delta = newCode.length - code.length;
    int newMaxStack = Math.max(maxStack, out.newMaxStack);

    ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
    DataOutputStream rd = new DataOutputStream(rebuilt);
    rd.writeShort(newMaxStack);
    rd.writeShort(maxLocals);
    rd.writeInt(newCode.length);
    rd.write(newCode);

    int exTableLen = d.readUnsignedShort();
    rd.writeShort(exTableLen);
    for (int i = 0; i < exTableLen; i++) {
      int s = d.readUnsignedShort();
      int e = d.readUnsignedShort();
      int h = d.readUnsignedShort();
      int t = d.readUnsignedShort();
      rd.writeShort(s + delta);
      rd.writeShort(e + delta);
      rd.writeShort(h + delta);
      rd.writeShort(t);
    }

    int innerAttrCount = d.readUnsignedShort();
    ByteArrayOutputStream innerBuf = new ByteArrayOutputStream();
    DataOutputStream iout = new DataOutputStream(innerBuf);
    int kept = 0;
    for (int i = 0; i < innerAttrCount; i++) {
      int aNameIdx = d.readUnsignedShort();
      int aLen = d.readInt();
      byte[] ab = new byte[aLen];
      d.readFully(ab);
      String aName = cp.utf8(aNameIdx);
      if (isStripped(aName)) {
        continue;
      }
      if ("StackMapTable".equals(aName)) {
        byte[] adjusted = adjustStackMapTable(ab, delta);
        iout.writeShort(aNameIdx);
        iout.writeInt(adjusted.length);
        iout.write(adjusted);
      } else {
        iout.writeShort(aNameIdx);
        iout.writeInt(aLen);
        iout.write(ab);
      }
      kept++;
    }
    rd.writeShort(kept);
    rd.write(innerBuf.toByteArray());
    return rebuilt.toByteArray();
  }

  private static boolean isStripped(String aName) {
    return "LineNumberTable".equals(aName)
        || "LocalVariableTable".equals(aName)
        || "LocalVariableTypeTable".equals(aName)
        || "RuntimeVisibleTypeAnnotations".equals(aName)
        || "RuntimeInvisibleTypeAnnotations".equals(aName);
  }

  static byte[] adjustStackMapTable(byte[] body, int delta) throws IOException {
    DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(body));
    int numEntries = in.readUnsignedShort();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeShort(numEntries);

    for (int i = 0; i < numEntries; i++) {
      int frameType = in.readUnsignedByte();
      boolean shiftOd = i == 0;
      if (frameType <= 63) {
        int newOd = shiftOd ? frameType + delta : frameType;
        if (newOd <= 63) {
          d.writeByte(newOd);
        } else {
          d.writeByte(251);
          d.writeShort(newOd);
        }
      } else if (frameType <= 127) {
        int oldOd = frameType - 64;
        int newOd = shiftOd ? oldOd + delta : oldOd;
        if (newOd <= 63) {
          d.writeByte(64 + newOd);
        } else {
          d.writeByte(247);
          d.writeShort(newOd);
        }
        copyVerificationTypeInfo(in, d, delta);
      } else if (frameType <= 246) {
        throw new IOException("reserved StackMapTable frame_type " + frameType);
      } else if (frameType == 247) {
        int oldOd = in.readUnsignedShort();
        int newOd = shiftOd ? oldOd + delta : oldOd;
        d.writeByte(247);
        d.writeShort(newOd);
        copyVerificationTypeInfo(in, d, delta);
      } else if (frameType <= 250) {
        int oldOd = in.readUnsignedShort();
        int newOd = shiftOd ? oldOd + delta : oldOd;
        d.writeByte(frameType);
        d.writeShort(newOd);
      } else if (frameType == 251) {
        int oldOd = in.readUnsignedShort();
        int newOd = shiftOd ? oldOd + delta : oldOd;
        d.writeByte(251);
        d.writeShort(newOd);
      } else if (frameType <= 254) {
        int oldOd = in.readUnsignedShort();
        int newOd = shiftOd ? oldOd + delta : oldOd;
        d.writeByte(frameType);
        d.writeShort(newOd);
        int locals = frameType - 251;
        for (int j = 0; j < locals; j++) {
          copyVerificationTypeInfo(in, d, delta);
        }
      } else {
        int oldOd = in.readUnsignedShort();
        int newOd = shiftOd ? oldOd + delta : oldOd;
        d.writeByte(255);
        d.writeShort(newOd);
        int numLocals = in.readUnsignedShort();
        d.writeShort(numLocals);
        for (int j = 0; j < numLocals; j++) {
          copyVerificationTypeInfo(in, d, delta);
        }
        int numStack = in.readUnsignedShort();
        d.writeShort(numStack);
        for (int j = 0; j < numStack; j++) {
          copyVerificationTypeInfo(in, d, delta);
        }
      }
    }
    return out.toByteArray();
  }

  private static void copyVerificationTypeInfo(DataInputStream in, DataOutputStream out, int delta)
      throws IOException {
    int tag = in.readUnsignedByte();
    out.writeByte(tag);
    if (tag == 7) {
      out.writeShort(in.readUnsignedShort());
    } else if (tag == 8) {
      int oldOffset = in.readUnsignedShort();
      out.writeShort(oldOffset + delta);
    } else if (tag > 8) {
      throw new IOException("unknown verification_type_info tag " + tag);
    }
  }

  private static byte[] readAttributes(DataInputStream in) throws IOException {
    int n = in.readUnsignedShort();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeShort(n);
    for (int i = 0; i < n; i++) {
      int nameIdx = in.readUnsignedShort();
      int len = in.readInt();
      byte[] body = new byte[len];
      in.readFully(body);
      d.writeShort(nameIdx);
      d.writeInt(len);
      d.write(body);
    }
    return out.toByteArray();
  }

  private static int scanCpEnd(byte[] b, int start, int count) {
    int pos = start;
    int i = 1;
    while (i < count) {
      int tag = b[pos] & 0xFF;
      pos++;
      switch (tag) {
        case 1:
          pos += 2 + (((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF));
          i++;
          break;
        case 3:
        case 4:
        case 9:
        case 10:
        case 11:
        case 12:
        case 17:
        case 18:
          pos += 4;
          i++;
          break;
        case 5:
        case 6:
          pos += 8;
          i += 2;
          break;
        case 7:
        case 8:
        case 16:
        case 19:
        case 20:
          pos += 2;
          i++;
          break;
        case 15:
          pos += 3;
          i++;
          break;
        default:
          throw new IllegalStateException("bad CP tag " + tag);
      }
    }
    return pos;
  }

  private static final class FieldsOrAttributesBlob {
    int count;
    byte[] bytes;
  }
}
