package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Tests for {@link ClassFileReader}, including constant-pool slot handling and failure modes. */
class ClassFileReaderTest {

  @Test
  void parsesItself() throws IOException {
    byte[] bytes = readResource("/io/pjmartos/cull/core/ClassFileReader.class");
    ClassFileMetadata m = ClassFileReader.parse(bytes);
    assertEquals("io/pjmartos/cull/core/ClassFileReader", m.thisClass());
    assertEquals("java/lang/Object", m.superClass());
    assertTrue(m.referencedClasses().contains("java/io/DataInputStream"));
    assertTrue(m.referencedClasses().contains("java/io/InputStream"));
    assertTrue(m.referencedClasses().contains("java/util/LinkedHashSet"));
  }

  @Test
  void parsesObjectFromJdk() throws IOException {
    byte[] bytes = readJdkClass("java.lang.String");
    ClassFileMetadata m = ClassFileReader.parse(bytes);
    assertEquals("java/lang/String", m.thisClass());
    assertNotNull(m.referencedClasses());
  }

  @Test
  void rejectsBadMagic() {
    byte[] bogus = new byte[64];
    assertThrows(
        IOException.class, () -> ClassFileReader.parse(new java.io.ByteArrayInputStream(bogus)));
  }

  @Test
  void descriptorParser() {
    Set<String> ref = new HashSet<>();
    ClassFileReader.extractTypesFromDescriptor(
        "(Ljava/lang/String;I[Ljava/lang/Object;)Ljava/util/List;", ref);
    assertTrue(ref.contains("java/lang/String"));
    assertTrue(ref.contains("java/lang/Object"));
    assertTrue(ref.contains("java/util/List"));
  }

  @Test
  void parsesAllOwnClasses() throws IOException {
    Path classesRoot = Paths.get("target", "classes");
    if (!Files.isDirectory(classesRoot)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(classesRoot)) {
      walk.filter(p -> p.toString().endsWith(".class"))
          .forEach(
              p -> {
                try {
                  ClassFileMetadata m = ClassFileReader.parse(Files.readAllBytes(p));
                  assertNotNull(m.thisClass());
                } catch (IOException e) {
                  throw new RuntimeException("failed to parse " + p, e);
                }
              });
    }
  }

  @Test
  void outOfRangeThisClassResolvesToNullNotException() throws IOException {
    // Structurally complete class file whose this_class index points past the
    // (empty) constant pool. The unguarded cast used to throw an unchecked
    // ArrayIndexOutOfBoundsException; it must now resolve gracefully to null.
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.writeInt(0xCAFEBABE);
    d.writeShort(0); // minor
    d.writeShort(0x34); // major
    d.writeShort(1); // constant_pool_count = 1 (no entries)
    d.writeShort(0); // access_flags
    d.writeShort(1); // this_class = 1 (out of range)
    d.writeShort(0); // super_class
    d.writeShort(0); // interfaces_count
    d.writeShort(0); // fields_count
    d.writeShort(0); // methods_count
    d.writeShort(0); // attributes_count

    ClassFileMetadata m =
        assertDoesNotThrow(() -> ClassFileReader.parse(new ByteArrayInputStream(b.toByteArray())));
    assertNull(m.thisClass());
    assertNull(m.superClass());
    assertTrue(m.interfaces().isEmpty());
  }

  @Test
  void truncatedCodeOperandSurfacesAsCheckedFailureNotUnchecked() throws IOException {
    byte[] malformed = classWithTruncatedInvokevirtual();

    // The checked overload must report the documented IOException so callers
    // can skip the file and continue — never an unchecked exception escaping
    // the parse loop.
    assertThrows(
        IOException.class, () -> ClassFileReader.parse(new ByteArrayInputStream(malformed)));

    // The byte[] overload (no checked exception by signature) must surface a
    // well-defined IllegalStateException, not a raw AIOOBE/ClassCastException.
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> ClassFileReader.parse(malformed));
    assertInstanceOf(IOException.class, ex.getCause());
  }

  @Test
  void parserHandlesLongCpEntryTakingTwoSlots() throws IOException {
    byte[] cls = makeClass(true, false);
    ClassFileMetadata m = ClassFileReader.parse(cls);
    assertNotNull(m.thisClass());
    assertTrue(m.thisClass().endsWith("X"));
  }

  @Test
  void parserHandlesDoubleCpEntryTakingTwoSlots() throws IOException {
    byte[] cls = makeClass(false, true);
    ClassFileMetadata m = ClassFileReader.parse(cls);
    assertNotNull(m.thisClass());
  }

  @Test
  void parserHandlesBothLongAndDouble() throws IOException {
    byte[] cls = makeClass(true, true);
    ClassFileMetadata m = ClassFileReader.parse(cls);
    assertNotNull(m.thisClass());
  }

  @Test
  void invokedynamicNameAndTypeDescriptorIsScannedForTypeRefs() throws IOException {
    // "cull/indy/Probe" appears ONLY in the invokedynamic's NameAndType
    // descriptor (no Class/Methodref/method-descriptor reference to it). A
    // lambda's functional-interface type reaches the static graph exactly this
    // way; dropping the InvokeDynamic CP entry would silently lose the edge.
    byte[] cls = classWithInvokeDynamic();
    ClassFileMetadata m = ClassFileReader.parse(cls);
    assertEquals("X", m.thisClass());
    assertEquals("java/lang/Object", m.superClass());
    assertTrue(
        m.referencedClasses().contains("cull/indy/Probe"),
        "invokedynamic descriptor type must be captured, got " + m.referencedClasses());
  }

  private static byte[] classWithInvokeDynamic() throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.writeInt(0xCAFEBABE);
    d.writeShort(0); // minor
    d.writeShort(52); // major (invokedynamic since 51)
    d.writeShort(11); // constant_pool_count -> entries 1..10
    writeUtf8(d, "Code"); // #1
    writeUtf8(d, "()Lcull/indy/Probe;"); // #2 indy NameAndType descriptor
    writeUtf8(d, "m"); // #3 indy NameAndType name
    d.writeByte(12); // #4 NameAndType
    d.writeShort(3); // name_index
    d.writeShort(2); // descriptor_index
    d.writeByte(18); // #5 InvokeDynamic
    d.writeShort(0); // bootstrap_method_attr_index (table index, not a CP slot)
    d.writeShort(4); // name_and_type_index -> #4
    writeUtf8(d, "X"); // #6
    d.writeByte(7); // #7 Class
    d.writeShort(6);
    writeUtf8(d, "java/lang/Object"); // #8
    d.writeByte(7); // #9 Class
    d.writeShort(8);
    writeUtf8(d, "()V"); // #10 method descriptor
    d.writeShort(0x0001); // access_flags
    d.writeShort(7); // this_class -> #7 (X)
    d.writeShort(9); // super_class -> #9 (Object)
    d.writeShort(0); // interfaces_count
    d.writeShort(0); // fields_count
    d.writeShort(1); // methods_count
    d.writeShort(0); // method access_flags
    d.writeShort(0); // method name_index
    d.writeShort(10); // method descriptor_index -> "()V"
    d.writeShort(1); // method attributes_count
    d.writeShort(1); // attribute_name_index -> "Code"
    d.writeInt(0); // attribute_length (ignored for Code)
    d.writeShort(0); // max_stack
    d.writeShort(0); // max_locals
    d.writeInt(5); // code_length
    d.writeByte(0xBA); // invokedynamic
    d.writeShort(5); // CP index of the InvokeDynamic entry (#5)
    d.writeShort(0); // invokedynamic trailing zero bytes
    d.writeShort(0); // exception_table_length
    d.writeShort(0); // code attributes_count
    d.writeShort(0); // class attributes_count
    return b.toByteArray();
  }

  private static void writeUtf8(DataOutputStream d, String s) throws IOException {
    byte[] u = s.getBytes(StandardCharsets.UTF_8);
    d.writeByte(1);
    d.writeShort(u.length);
    d.write(u);
  }

  private static byte[] makeClass(boolean withLong, boolean withDouble) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeInt(0xCAFEBABE);
    d.writeShort(0);
    d.writeShort(55);
    int slotsUsed = 2 + (withLong ? 2 : 0) + (withDouble ? 2 : 0);
    d.writeShort(slotsUsed + 1);
    d.writeByte(7);
    d.writeShort(2);
    byte[] name = "X".getBytes(StandardCharsets.UTF_8);
    d.writeByte(1);
    d.writeShort(name.length);
    d.write(name);
    if (withLong) {
      d.writeByte(5);
      d.writeLong(0xDEADBEEFCAFEBABEL);
    }
    if (withDouble) {
      d.writeByte(6);
      d.writeDouble(3.141592653589793);
    }
    d.writeShort(0x0001);
    d.writeShort(1);
    d.writeShort(0);
    d.writeShort(0);
    d.writeShort(0);
    d.writeShort(0);
    d.writeShort(0);
    return out.toByteArray();
  }

  private static byte[] classWithTruncatedInvokevirtual() throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.writeInt(0xCAFEBABE);
    d.writeShort(0); // minor
    d.writeShort(0x34); // major
    d.writeShort(2); // constant_pool_count = 2 (one entry)
    d.writeByte(1); // CP#1 tag = Utf8
    byte[] code = "Code".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    d.writeShort(code.length);
    d.write(code);
    d.writeShort(0); // access_flags
    d.writeShort(0); // this_class
    d.writeShort(0); // super_class
    d.writeShort(0); // interfaces_count
    d.writeShort(0); // fields_count
    d.writeShort(1); // methods_count = 1
    d.writeShort(0); // method access_flags
    d.writeShort(0); // method name_index
    d.writeShort(0); // method descriptor_index
    d.writeShort(1); // method attributes_count = 1
    d.writeShort(1); // attribute_name_index -> "Code"
    d.writeInt(13); // attribute_length (unused for Code)
    d.writeShort(0); // max_stack
    d.writeShort(0); // max_locals
    d.writeInt(1); // code_length = 1
    d.writeByte(0xB6); // invokevirtual opcode with no operand bytes
    d.writeShort(0); // exception_table_length
    d.writeShort(0); // code attributes_count
    d.writeShort(0); // class attributes_count
    return b.toByteArray();
  }

  private byte[] readResource(String name) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(name)) {
      assertNotNull(in, "missing resource " + name);
      return in.readAllBytes();
    }
  }

  private byte[] readJdkClass(String fqcn) throws IOException {
    String resource = "/" + fqcn.replace('.', '/') + ".class";
    try (InputStream in = String.class.getResourceAsStream(resource)) {
      if (in == null) {
        return new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 0x37, 0, 1};
      }
      return in.readAllBytes();
    }
  }
}
