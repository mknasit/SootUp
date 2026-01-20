package com.jessintegration.bytecode;

import com.jessintegration.model.MethodId;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Stable textual dump of a method body (+ helpers to load from dir/jar/classfile path). */
public final class Canonicalizer {

  private final Textifier textifier = new Textifier();
  private final TraceMethodVisitor tracer = new TraceMethodVisitor(textifier);

  public MethodVisitor visitor() {
    return tracer;
  }

  public String dump() {
    StringWriter sw = new StringWriter();
    try (PrintWriter pw = new PrintWriter(sw)) {
      textifier.print(pw);
    }
    return sw.toString();
  }

  public static String canonicalize(MethodNode mn, boolean strict) {
    if (!strict) {
      mn.localVariables = null; // best-effort: reduce debug noise
    }
    Canonicalizer c = new Canonicalizer();
    mn.accept(c.visitor());
    return c.dump();
  }

  /** Dump from an exact classes directory root (expects owner under that root). */
  public static Optional<String> dumpFromDir(Path classesDir, MethodId m, boolean strict) {
    Path classFile = classesDir.resolve(m.binaryClassName() + ".class");
    if (!Files.exists(classFile)) return Optional.empty();
    try (InputStream in = Files.newInputStream(classFile)) {
      return dumpFromClassStream(in, m, strict);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Dump directly from an exact .class file path (no assumptions about root layout). */
  public static Optional<String> dumpFromClassFile(Path classFile, MethodId m, boolean strict) {
    if (classFile == null || !Files.isRegularFile(classFile)) return Optional.empty();
    try (InputStream in = Files.newInputStream(classFile)) {
      return dumpFromClassStream(in, m, strict);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Dump from a (possibly multi-release) jar. Prefers the highest META-INF/versions/* match. */
  public static Optional<String> dumpFromJar(Path jarPath, MethodId m, boolean strict) {
    String entryName = m.binaryClassName() + ".class";
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      JarEntry ent = jar.getJarEntry(entryName);
      if (ent == null) {
        ent = findMrEntry(jar, entryName).orElse(null);
      }
      if (ent == null) return Optional.empty();
      try (InputStream in = jar.getInputStream(ent)) {
        return dumpFromClassStream(in, m, strict);
      }
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Checks if a method exists and whether it has Code in a .class file path. */
  public static MethodPresence methodPresenceInClassFile(Path classFile, MethodId m) {
    if (classFile == null || !Files.isRegularFile(classFile)) return MethodPresence.CLASS_NOT_FOUND;
    try (InputStream in = Files.newInputStream(classFile)) {
      return methodPresenceFromClassStream(in, m);
    } catch (IOException e) {
      return MethodPresence.ERROR;
    }
  }

  /** Checks if a method exists/has Code in a (possibly multi-release) jar. */
  public static MethodPresence methodPresenceInJar(Path jarPath, MethodId m) {
    String entryName = m.binaryClassName() + ".class";
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      JarEntry ent = jar.getJarEntry(entryName);
      if (ent == null) ent = findMrEntry(jar, entryName).orElse(null);
      if (ent == null) return MethodPresence.CLASS_NOT_FOUND;
      try (InputStream in = jar.getInputStream(ent)) {
        return methodPresenceFromClassStream(in, m);
      }
    } catch (IOException e) {
      return MethodPresence.ERROR;
    }
  }

  public enum MethodPresence {
    CLASS_NOT_FOUND,
    METHOD_NOT_FOUND,
    NO_CODE, // method present but no Code attribute (abstract/interface/native)
    HAS_CODE,
    ERROR
  }

  // ===== internals =====

  private static Optional<String> dumpFromClassStream(InputStream in, MethodId m, boolean strict)
      throws IOException {
    ClassReader cr = new ClassReader(in);
    ClassNode cn = new ClassNode();
    cr.accept(cn, 0);
    MethodNode target = findMethodNode(cn, m);
    return target == null ? Optional.empty() : Optional.of(canonicalize(target, strict));
  }

  private static MethodPresence methodPresenceFromClassStream(InputStream in, MethodId m)
      throws IOException {
    ClassReader cr = new ClassReader(in);
    ClassNode cn = new ClassNode();
    cr.accept(cn, 0);
    MethodNode target = findMethodNode(cn, m);
    if (target == null) return MethodPresence.METHOD_NOT_FOUND;
    // target.instructions may be empty for abstract/interface/native
    boolean hasCode = target.instructions != null && target.instructions.size() > 0;
    return hasCode ? MethodPresence.HAS_CODE : MethodPresence.NO_CODE;
  }

  private static MethodNode findMethodNode(ClassNode cn, MethodId m) {
    String name = m.name();
    String desc = m.jvmDescriptor();
    for (Object o : cn.methods) {
      MethodNode mn = (MethodNode) o;
      if (Objects.equals(mn.name, name) && Objects.equals(mn.desc, desc)) {
        return mn;
      }
    }
    return null;
  }

  /** Find a multi-release entry for the given base entry, preferring the highest version. */
  private static Optional<JarEntry> findMrEntry(JarFile jar, String baseEntry) {
    // Look for META-INF/versions/<n>/<baseEntry>, pick highest <n>
    return jar.stream()
        .filter(e -> !e.isDirectory())
        .filter(e -> e.getName().endsWith("/" + baseEntry))
        .filter(e -> e.getName().startsWith("META-INF/versions/"))
        .max(Comparator.comparingInt(Canonicalizer::mrVersion));
  }

  private static int mrVersion(JarEntry e) {
    // META-INF/versions/<n>/... -> extract <n>
    String name = e.getName();
    String[] parts = name.split("/");
    for (int i = 0; i < parts.length; i++) {
      if (i + 1 < parts.length && "versions".equals(parts[i]) && "META-INF".equals(parts[i - 1])) {
        try {
          return Integer.parseInt(parts[i + 1]);
        } catch (Exception ignored) {
          return -1;
        }
      }
    }
    // More robust: find "versions" token then take next token as int
    for (int i = 0; i < parts.length; i++) {
      if ("versions".equals(parts[i]) && i + 1 < parts.length) {
        try {
          return Integer.parseInt(parts[i + 1]);
        } catch (Exception ignored) {
          return -1;
        }
      }
    }
    return -1;
  }
}
