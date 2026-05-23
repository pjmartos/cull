package io.pjmartos.cull.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ClassFileReader {

  private static final int MAGIC = 0xCAFEBABE;

  private static final int CP_UTF8 = 1;
  private static final int CP_INTEGER = 3;
  private static final int CP_FLOAT = 4;
  private static final int CP_LONG = 5;
  private static final int CP_DOUBLE = 6;
  private static final int CP_CLASS = 7;
  private static final int CP_STRING = 8;
  private static final int CP_FIELDREF = 9;
  private static final int CP_METHODREF = 10;
  private static final int CP_IFACE_METHODREF = 11;
  private static final int CP_NAME_AND_TYPE = 12;
  private static final int CP_METHOD_HANDLE = 15;
  private static final int CP_METHOD_TYPE = 16;
  private static final int CP_DYNAMIC = 17;
  private static final int CP_INVOKE_DYNAMIC = 18;
  private static final int CP_MODULE = 19;
  private static final int CP_PACKAGE = 20;

  private static final int[] OPCODE_LEN = new int[256];

  static {
    Arrays.fill(OPCODE_LEN, 1);
    OPCODE_LEN[0x10] = 2;
    OPCODE_LEN[0x11] = 3;
    OPCODE_LEN[0x12] = 2;
    OPCODE_LEN[0x13] = 3;
    OPCODE_LEN[0x14] = 3;
    OPCODE_LEN[0x15] = 2;
    OPCODE_LEN[0x16] = 2;
    OPCODE_LEN[0x17] = 2;
    OPCODE_LEN[0x18] = 2;
    OPCODE_LEN[0x19] = 2;
    OPCODE_LEN[0x36] = 2;
    OPCODE_LEN[0x37] = 2;
    OPCODE_LEN[0x38] = 2;
    OPCODE_LEN[0x39] = 2;
    OPCODE_LEN[0x3A] = 2;
    OPCODE_LEN[0x84] = 3;
    for (int op = 0x99; op <= 0xA8; op++) {
      OPCODE_LEN[op] = 3;
    }
    OPCODE_LEN[0xA9] = 2;
    OPCODE_LEN[0xB2] = 3;
    OPCODE_LEN[0xB3] = 3;
    OPCODE_LEN[0xB4] = 3;
    OPCODE_LEN[0xB5] = 3;
    OPCODE_LEN[0xB6] = 3;
    OPCODE_LEN[0xB7] = 3;
    OPCODE_LEN[0xB8] = 3;
    OPCODE_LEN[0xB9] = 5;
    OPCODE_LEN[0xBA] = 5;
    OPCODE_LEN[0xBB] = 3;
    OPCODE_LEN[0xBC] = 2;
    OPCODE_LEN[0xBD] = 3;
    OPCODE_LEN[0xC0] = 3;
    OPCODE_LEN[0xC1] = 3;
    OPCODE_LEN[0xC5] = 4;
    OPCODE_LEN[0xC6] = 3;
    OPCODE_LEN[0xC7] = 3;
    OPCODE_LEN[0xC8] = 5;
    OPCODE_LEN[0xC9] = 5;
  }

  private ClassFileReader() {}

  public static ClassFileMetadata parse(byte[] bytes) {
    try {
      return parse(new java.io.ByteArrayInputStream(bytes));
    } catch (IOException e) {
      throw new IllegalStateException("malformed class file", e);
    }
  }

  public static ClassFileMetadata parse(InputStream in) throws IOException {
    try {
      return doParse(in);
    } catch (RuntimeException e) {
      // A corrupt or adversarial class file must surface as the documented
      // IOException so callers can skip the file and continue, never as an
      // unchecked exception that escapes the parse loop.
      throw new IOException("malformed class file", e);
    }
  }

  private static ClassFileMetadata doParse(InputStream in) throws IOException {
    DataInputStream d = new DataInputStream(in);
    int magic = d.readInt();
    if (magic != MAGIC) {
      throw new IOException("not a class file: 0x" + Integer.toHexString(magic));
    }
    int minor = d.readUnsignedShort();
    int major = d.readUnsignedShort();
    int classFileVersion = (major << 16) | minor;

    Object[] cp = readConstantPool(d);

    d.readUnsignedShort(); // access_flags
    int thisClassIdx = d.readUnsignedShort();
    int superClassIdx = d.readUnsignedShort();

    int interfaceCount = d.readUnsignedShort();
    int[] interfaceIdx = new int[interfaceCount];
    for (int i = 0; i < interfaceCount; i++) {
      interfaceIdx[i] = d.readUnsignedShort();
    }

    Set<String> referenced = new LinkedHashSet<>();

    for (int i = 1; i < cp.length; i++) {
      Object e = cp[i];
      if (e instanceof CpClass) {
        int nameIdx = ((CpClass) e).nameIdx;
        String name = utf8(cp, nameIdx);
        addClassRef(referenced, name);
      }
    }

    int fieldCount = d.readUnsignedShort();
    for (int i = 0; i < fieldCount; i++) {
      d.readUnsignedShort(); // access
      d.readUnsignedShort(); // name
      int descIdx = d.readUnsignedShort();
      extractTypesFromDescriptor(utf8(cp, descIdx), referenced);
      skipAttributes(d);
    }

    int methodCount = d.readUnsignedShort();
    for (int i = 0; i < methodCount; i++) {
      d.readUnsignedShort();
      d.readUnsignedShort();
      int descIdx = d.readUnsignedShort();
      extractTypesFromDescriptor(utf8(cp, descIdx), referenced);
      int attrCount = d.readUnsignedShort();
      for (int a = 0; a < attrCount; a++) {
        int attrNameIdx = d.readUnsignedShort();
        long attrLen = d.readInt() & 0xFFFFFFFFL;
        String attrName = utf8(cp, attrNameIdx);
        if ("Code".equals(attrName)) {
          parseCodeAttribute(d, cp, referenced);
        } else {
          skipFully(d, attrLen);
        }
      }
    }

    skipAttributes(d);

    String thisClass = thisClassIdx == 0 ? null : classNameAt(cp, thisClassIdx);
    String superClass = superClassIdx == 0 ? null : classNameAt(cp, superClassIdx);
    List<String> interfaces = new ArrayList<>(interfaceCount);
    for (int idx : interfaceIdx) {
      String n = classNameAt(cp, idx);
      if (n != null) {
        interfaces.add(n);
      }
    }
    if (thisClass != null) {
      referenced.remove(thisClass);
    }
    return new ClassFileMetadata(thisClass, superClass, interfaces, referenced, classFileVersion);
  }

  private static Object[] readConstantPool(DataInputStream d) throws IOException {
    int count = d.readUnsignedShort();
    Object[] cp = new Object[count];
    int i = 1;
    while (i < count) {
      int tag = d.readUnsignedByte();
      switch (tag) {
        case CP_UTF8:
          {
            int len = d.readUnsignedShort();
            byte[] data = new byte[len];
            d.readFully(data);
            cp[i] = decodeModifiedUtf8(data);
            i++;
            break;
          }
        case CP_INTEGER:
        case CP_FLOAT:
          d.readInt();
          i++;
          break;
        case CP_LONG:
        case CP_DOUBLE:
          d.readLong();
          i += 2;
          break;
        case CP_CLASS:
          {
            int nameIdx = d.readUnsignedShort();
            cp[i] = new CpClass(nameIdx);
            i++;
            break;
          }
        case CP_STRING:
        case CP_METHOD_TYPE:
        case CP_MODULE:
        case CP_PACKAGE:
          d.readUnsignedShort();
          i++;
          break;
        case CP_FIELDREF:
        case CP_METHODREF:
        case CP_IFACE_METHODREF:
        case CP_NAME_AND_TYPE:
          {
            int a = d.readUnsignedShort();
            int b = d.readUnsignedShort();
            cp[i] = new CpRef(a, b);
            i++;
            break;
          }
        case CP_METHOD_HANDLE:
          d.readUnsignedByte();
          d.readUnsignedShort();
          i++;
          break;
        case CP_DYNAMIC:
        case CP_INVOKE_DYNAMIC:
          {
            d.readUnsignedShort(); // bootstrap_method_attr_index (not a CP index)
            int natIdx = d.readUnsignedShort();
            // Keep the NameAndType link so the invoked/constant descriptor (e.g. a
            // lambda's functional-interface type) is scanned. The first u2 is a
            // bootstrap-methods table index, not a CP slot, so it is not stored.
            cp[i] = new CpRef(0, natIdx);
            i++;
            break;
          }
        default:
          throw new IOException("unknown CP tag: " + tag + " at " + i);
      }
    }
    return cp;
  }

  private static void parseCodeAttribute(DataInputStream d, Object[] cp, Set<String> referenced)
      throws IOException {
    d.readUnsignedShort(); // max_stack
    d.readUnsignedShort(); // max_locals
    long codeLen = d.readInt() & 0xFFFFFFFFL;
    if (codeLen > Integer.MAX_VALUE) {
      throw new IOException("code length too large");
    }
    byte[] code = new byte[(int) codeLen];
    d.readFully(code);
    scanCodeForCpRefs(code, cp, referenced);

    int exTableLen = d.readUnsignedShort();
    for (int i = 0; i < exTableLen; i++) {
      d.readUnsignedShort();
      d.readUnsignedShort();
      d.readUnsignedShort();
      int catchType = d.readUnsignedShort();
      if (catchType != 0 && catchType < cp.length) {
        Object e = cp[catchType];
        if (e instanceof CpClass) {
          addClassRef(referenced, utf8(cp, ((CpClass) e).nameIdx));
        }
      }
    }
    skipAttributes(d);
  }

  private static void scanCodeForCpRefs(byte[] code, Object[] cp, Set<String> referenced) {
    int pc = 0;
    while (pc < code.length) {
      int opcode = code[pc] & 0xFF;
      int len;
      switch (opcode) {
        case 0xAA:
          {
            int next = pc + 1;
            int padded = (next + 3) & ~3;
            if (padded + 12 > code.length) {
              return;
            }
            int low = readInt(code, padded + 4);
            int high = readInt(code, padded + 8);
            long n = (long) high - low + 1L;
            long total = (padded - pc) + 12L + n * 4L;
            len = (int) total;
            break;
          }
        case 0xAB:
          {
            int next = pc + 1;
            int padded = (next + 3) & ~3;
            if (padded + 8 > code.length) {
              return;
            }
            int npairs = readInt(code, padded + 4);
            long total = (padded - pc) + 8L + (long) npairs * 8L;
            len = (int) total;
            break;
          }
        case 0xC4:
          {
            if (pc + 1 >= code.length) {
              return;
            }
            int next = code[pc + 1] & 0xFF;
            if (next == 0x84) {
              len = 6;
            } else {
              len = 4;
            }
            break;
          }
        default:
          {
            len = OPCODE_LEN[opcode];
            break;
          }
      }
      switch (opcode) {
        case 0x12:
          {
            int idx = code[pc + 1] & 0xFF;
            handleLdc(cp, idx, referenced);
            break;
          }
        case 0x13:
        case 0x14:
          {
            int idx = readU2(code, pc + 1);
            handleLdc(cp, idx, referenced);
            break;
          }
        case 0xB2:
        case 0xB3:
        case 0xB4:
        case 0xB5:
        case 0xB6:
        case 0xB7:
        case 0xB8:
        case 0xB9:
        case 0xBA:
          {
            int idx = readU2(code, pc + 1);
            handleMemberRef(cp, idx, referenced);
            break;
          }
        case 0xBB:
        case 0xBD:
        case 0xC0:
        case 0xC1:
        case 0xC5:
          {
            int idx = readU2(code, pc + 1);
            handleClassRef(cp, idx, referenced);
            break;
          }
        default:
          break;
      }
      if (len <= 0) {
        return;
      }
      pc += len;
    }
  }

  private static void handleLdc(Object[] cp, int idx, Set<String> referenced) {
    if (idx <= 0 || idx >= cp.length) {
      return;
    }
    Object e = cp[idx];
    if (e instanceof CpClass) {
      addClassRef(referenced, utf8(cp, ((CpClass) e).nameIdx));
    }
  }

  private static void handleClassRef(Object[] cp, int idx, Set<String> referenced) {
    if (idx <= 0 || idx >= cp.length) {
      return;
    }
    Object e = cp[idx];
    if (e instanceof CpClass) {
      addClassRef(referenced, utf8(cp, ((CpClass) e).nameIdx));
    }
  }

  private static void handleMemberRef(Object[] cp, int idx, Set<String> referenced) {
    if (idx <= 0 || idx >= cp.length) {
      return;
    }
    Object e = cp[idx];
    if (!(e instanceof CpRef)) {
      return;
    }
    CpRef r = (CpRef) e;
    if (r.a > 0 && r.a < cp.length) {
      Object cls = cp[r.a];
      if (cls instanceof CpClass) {
        addClassRef(referenced, utf8(cp, ((CpClass) cls).nameIdx));
      }
    }
    if (r.b > 0 && r.b < cp.length) {
      Object nat = cp[r.b];
      if (nat instanceof CpRef) {
        int descIdx = ((CpRef) nat).b;
        if (descIdx > 0 && descIdx < cp.length) {
          Object u = cp[descIdx];
          if (u instanceof String) {
            extractTypesFromDescriptor((String) u, referenced);
          }
        }
      }
    }
  }

  private static void addClassRef(Set<String> referenced, String name) {
    if (name == null || name.isEmpty()) {
      return;
    }
    if (name.charAt(0) == '[') {
      extractTypesFromDescriptor(name, referenced);
      return;
    }
    referenced.add(name);
  }

  static void extractTypesFromDescriptor(String desc, Set<String> referenced) {
    if (desc == null) {
      return;
    }
    int i = 0;
    int n = desc.length();
    while (i < n) {
      char c = desc.charAt(i);
      if (c == 'L') {
        int end = desc.indexOf(';', i);
        if (end < 0) {
          return;
        }
        referenced.add(desc.substring(i + 1, end));
        i = end + 1;
      } else {
        i++;
      }
    }
  }

  private static void skipAttributes(DataInputStream d) throws IOException {
    int n = d.readUnsignedShort();
    for (int i = 0; i < n; i++) {
      d.readUnsignedShort();
      long len = d.readInt() & 0xFFFFFFFFL;
      skipFully(d, len);
    }
  }

  private static void skipFully(DataInputStream d, long n) throws IOException {
    long remaining = n;
    while (remaining > 0) {
      long skipped = d.skip(remaining);
      if (skipped <= 0) {
        if (d.read() < 0) {
          throw new IOException("unexpected EOF skipping " + n);
        }
        remaining--;
      } else {
        remaining -= skipped;
      }
    }
  }

  private static String utf8(Object[] cp, int idx) {
    if (idx <= 0 || idx >= cp.length) {
      return null;
    }
    Object e = cp[idx];
    return e instanceof String ? (String) e : null;
  }

  private static String classNameAt(Object[] cp, int idx) {
    if (idx <= 0 || idx >= cp.length) {
      return null;
    }
    Object e = cp[idx];
    if (!(e instanceof CpClass)) {
      return null;
    }
    return utf8(cp, ((CpClass) e).nameIdx);
  }

  private static int readU2(byte[] code, int off) {
    return ((code[off] & 0xFF) << 8) | (code[off + 1] & 0xFF);
  }

  private static int readInt(byte[] code, int off) {
    return ((code[off] & 0xFF) << 24)
        | ((code[off + 1] & 0xFF) << 16)
        | ((code[off + 2] & 0xFF) << 8)
        | (code[off + 3] & 0xFF);
  }

  private static String decodeModifiedUtf8(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 2);
    out.write((data.length >>> 8) & 0xFF);
    out.write(data.length & 0xFF);
    out.write(data, 0, data.length);
    DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(out.toByteArray()));
    return d.readUTF();
  }

  private static final class CpClass {
    final int nameIdx;

    CpClass(int nameIdx) {
      this.nameIdx = nameIdx;
    }
  }

  private static final class CpRef {
    final int a;
    final int b;

    CpRef(int a, int b) {
      this.a = a;
      this.b = b;
    }
  }
}
