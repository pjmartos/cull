package io.pjmartos.cull.agent.bytecode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class ConstantPoolBuilder {

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

  /** Maximum constant-pool entries the JVM class-file format allows (u2 count field). */
  private static final int MAX_CP_COUNT = 65535;

  private final byte[] originalBytes;
  private final int originalCount;
  private final ByteArrayOutputStream extra = new ByteArrayOutputStream();
  private int nextIndex;
  private final Map<String, Integer> utf8Index = new HashMap<>();
  private final Map<Integer, Integer> classIndex = new HashMap<>();
  private final Map<Long, Integer> nameAndTypeIndex = new HashMap<>();
  private final Map<Long, Integer> methodRefIndex = new HashMap<>();

  public ConstantPoolBuilder(byte[] originalBytes, int originalCount) {
    this.originalBytes = originalBytes.clone();
    this.originalCount = originalCount;
    this.nextIndex = originalCount;
    indexExisting();
  }

  private void indexExisting() {
    int pos = 0;
    int i = 1;
    while (i < originalCount) {
      int tag = originalBytes[pos] & 0xFF;
      pos++;
      switch (tag) {
        case CP_UTF8:
          {
            int len = readU2(originalBytes, pos);
            pos += 2;
            String s = new String(originalBytes, pos, len, StandardCharsets.UTF_8);
            utf8Index.putIfAbsent(s, i);
            pos += len;
            i++;
            break;
          }
        case CP_INTEGER:
        case CP_FLOAT:
        case CP_DYNAMIC:
        case CP_INVOKE_DYNAMIC:
          pos += 4;
          i++;
          break;
        case CP_LONG:
        case CP_DOUBLE:
          pos += 8;
          i += 2;
          break;
        case CP_CLASS:
          {
            int nameIdx = readU2(originalBytes, pos);
            classIndex.putIfAbsent(nameIdx, i);
            pos += 2;
            i++;
            break;
          }
        case CP_STRING:
        case CP_METHOD_TYPE:
        case CP_MODULE:
        case CP_PACKAGE:
          pos += 2;
          i++;
          break;
        case CP_METHODREF:
          {
            int classIdx = readU2(originalBytes, pos);
            int natIdx = readU2(originalBytes, pos + 2);
            long key = (((long) classIdx) << 16) | (long) natIdx;
            methodRefIndex.putIfAbsent(key, i);
            pos += 4;
            i++;
            break;
          }
        case CP_FIELDREF:
        case CP_IFACE_METHODREF:
          // Deliberately not added to methodRefIndex. This builder only ever
          // emits CP_METHODREF; a Fieldref/InterfaceMethodref shares the
          // (classIdx, natIdx) shape, so deduping them together would let
          // methodRef() return a non-methodref index and emit a malformed
          // INVOKESTATIC operand.
          pos += 4;
          i++;
          break;
        case CP_NAME_AND_TYPE:
          {
            int nameIdx = readU2(originalBytes, pos);
            int descIdx = readU2(originalBytes, pos + 2);
            long key = (((long) nameIdx) << 16) | (long) descIdx;
            nameAndTypeIndex.putIfAbsent(key, i);
            pos += 4;
            i++;
            break;
          }
        case CP_METHOD_HANDLE:
          pos += 3;
          i++;
          break;
        default:
          throw new IllegalStateException("bad CP tag " + tag);
      }
    }
  }

  public int utf8(String s) {
    Integer existing = utf8Index.get(s);
    if (existing != null) {
      return existing;
    }
    try {
      byte[] data = s.getBytes(StandardCharsets.UTF_8);
      extra.write(CP_UTF8);
      extra.write((data.length >>> 8) & 0xFF);
      extra.write(data.length & 0xFF);
      extra.write(data);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    int idx = nextIndex++;
    utf8Index.put(s, idx);
    return idx;
  }

  public String utf8(int index) {
    if (index <= 0 || index >= originalCount) {
      return null;
    }
    int pos = 0;
    int i = 1;
    while (i < originalCount) {
      int tag = originalBytes[pos] & 0xFF;
      pos++;
      switch (tag) {
        case CP_UTF8:
          {
            int len = readU2(originalBytes, pos);
            pos += 2;
            if (i == index) {
              return new String(originalBytes, pos, len, StandardCharsets.UTF_8);
            }
            pos += len;
            i++;
            break;
          }
        case CP_INTEGER:
        case CP_FLOAT:
        case CP_FIELDREF:
        case CP_METHODREF:
        case CP_IFACE_METHODREF:
        case CP_NAME_AND_TYPE:
        case CP_DYNAMIC:
        case CP_INVOKE_DYNAMIC:
          if (i == index) return null;
          pos += 4;
          i++;
          break;
        case CP_LONG:
        case CP_DOUBLE:
          if (i == index) return null;
          pos += 8;
          i += 2;
          break;
        case CP_CLASS:
        case CP_STRING:
        case CP_METHOD_TYPE:
        case CP_MODULE:
        case CP_PACKAGE:
          if (i == index) return null;
          pos += 2;
          i++;
          break;
        case CP_METHOD_HANDLE:
          if (i == index) return null;
          pos += 3;
          i++;
          break;
        default:
          return null;
      }
    }
    return null;
  }

  public int classRef(String internalName) {
    int nameIdx = utf8(internalName);
    Integer existing = classIndex.get(nameIdx);
    if (existing != null) {
      return existing;
    }
    try {
      extra.write(CP_CLASS);
      extra.write((nameIdx >>> 8) & 0xFF);
      extra.write(nameIdx & 0xFF);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    int idx = nextIndex++;
    classIndex.put(nameIdx, idx);
    return idx;
  }

  public int nameAndType(String name, String descriptor) {
    int nameIdx = utf8(name);
    int descIdx = utf8(descriptor);
    long key = (((long) nameIdx) << 16) | (long) descIdx;
    Integer existing = nameAndTypeIndex.get(key);
    if (existing != null) {
      return existing;
    }
    extra.write(CP_NAME_AND_TYPE);
    extra.write((nameIdx >>> 8) & 0xFF);
    extra.write(nameIdx & 0xFF);
    extra.write((descIdx >>> 8) & 0xFF);
    extra.write(descIdx & 0xFF);
    int idx = nextIndex++;
    nameAndTypeIndex.put(key, idx);
    return idx;
  }

  public int methodRef(String ownerInternal, String name, String descriptor) {
    int classIdx = classRef(ownerInternal);
    int natIdx = nameAndType(name, descriptor);
    long key = (((long) classIdx) << 16) | (long) natIdx;
    Integer existing = methodRefIndex.get(key);
    if (existing != null) {
      return existing;
    }
    extra.write(CP_METHODREF);
    extra.write((classIdx >>> 8) & 0xFF);
    extra.write(classIdx & 0xFF);
    extra.write((natIdx >>> 8) & 0xFF);
    extra.write(natIdx & 0xFF);
    int idx = nextIndex++;
    methodRefIndex.put(key, idx);
    return idx;
  }

  public byte[] serialize() throws IOException {
    if (nextIndex > MAX_CP_COUNT) {
      throw new IOException(
          "Constant pool count " + nextIndex + " exceeds JVM u2 limit of " + MAX_CP_COUNT);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(originalBytes.length + extra.size());
    out.write(originalBytes);
    out.write(extra.toByteArray());
    return out.toByteArray();
  }

  public int count() {
    return nextIndex;
  }

  private static int readU2(byte[] b, int off) {
    return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
  }
}
