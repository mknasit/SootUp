package com.jessintegration.discovery;

import com.jessintegration.model.MethodId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Enumerates "real" methods from the reference JAR: - excludes synthetic/bridge - excludes
 * lambda$/access$ helpers - excludes abstract/native (no Code) - excludes enum boilerplate
 * (values/valueOf) on enum classes - optional: requires source file to exist under provided source
 * roots
 */
public final class MethodEnumerator {

  private final Path refJar;
  private final List<String> sourceRoots; // relative to repo root, e.g. ["src/main/java"]
  private final boolean requireSourceFile;

  public MethodEnumerator(Path refJar, List<String> sourceRoots, boolean requireSourceFile) {
    this.refJar = Objects.requireNonNull(refJar, "refJar");
    this.sourceRoots = (sourceRoots == null) ? List.of() : List.copyOf(sourceRoots);
    this.requireSourceFile = requireSourceFile;
  }

  /** Back-compat: matches old call sites. Does NOT require a source file. */
  public static List<MethodId> fromJar(Path refJar, Optional<String> pkgPrefix, int limit)
      throws IOException {
    MethodEnumerator me = new MethodEnumerator(refJar, List.of(), false);
    // repoRoot is irrelevant when requireSourceFile=false
    return me.enumerate(Path.of("."), pkgPrefix.orElse(null), limit);
  }

  /** Preferred: lets you require the source file to exist under given roots. */
  public static List<MethodId> fromJar(
      Path refJar,
      Optional<String> pkgPrefix,
      int limit,
      Path repoRoot,
      List<String> sourceRoots,
      boolean requireSourceFile)
      throws IOException {
    MethodEnumerator me = new MethodEnumerator(refJar, sourceRoots, requireSourceFile);
    return me.enumerate(repoRoot, pkgPrefix.orElse(null), limit);
  }

  /**
   * Enumerate methods from the ref JAR, applying filters. If requireSourceFile=true, only classes
   * whose .java exists under one of sourceRoots (relative to repoRoot) are considered.
   */
  public List<MethodId> enumerate(Path repoRoot, String pkgPrefix, int limit) throws IOException {
    List<MethodId> out = new ArrayList<>();
    try (ZipFile zip = new ZipFile(refJar.toFile())) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        if (e.isDirectory()) continue;
        String name = e.getName().replace('\\', '/');
        if (!name.endsWith(".class")) continue;

        if (pkgPrefix != null && !pkgPrefix.isBlank()) {
          String wanted = pkgPrefix.replace('.', '/') + "/";
          if (!name.startsWith(wanted)) continue;
        }

        try (InputStream in = zip.getInputStream(e)) {
          ClassReader cr = new ClassReader(in);
          ClassNode cn = new ClassNode();
          cr.accept(cn, 0);

          boolean isEnum = (cn.access & Opcodes.ACC_ENUM) != 0;

          // optional: verify source file exists in repo under any source root
          if (requireSourceFile && !sourceExists(repoRoot, cn.name)) {
            continue;
          }

          @SuppressWarnings("unchecked")
          List<MethodNode> methods = (List<MethodNode>) (List<?>) cn.methods;
          for (MethodNode mn : methods) {
            if (!isRealMethod(cn, mn, isEnum)) continue;

            out.add(new MethodId(cn.name, mn.name, mn.desc));
          }
        }
      }
    }

    // Shuffle for random selection when limit is applied
    if (limit > 0 && out.size() > limit) {
      Collections.shuffle(out);
      return out.subList(0, limit);
    }
    return out;
  }

  private boolean sourceExists(Path repoRoot, String internalClassName) {
    if (sourceRoots.isEmpty()) return false; // if required, but none provided -> treat as missing
    for (String root : sourceRoots) {
      Path p = repoRoot.resolve(root).resolve(internalClassName + ".java");
      if (Files.isRegularFile(p)) return true;
      // Also allow outer class .java for inners
      int dollar = internalClassName.indexOf('$');
      if (dollar > 0) {
        String outer = internalClassName.substring(0, dollar);
        Path pOuter = repoRoot.resolve(root).resolve(outer + ".java");
        if (Files.isRegularFile(pOuter)) return true;
      }
    }
    return false;
  }

  private static boolean isRealMethod(ClassNode cn, MethodNode mn, boolean isEnum) {
    final int acc = mn.access;

    // synthetic / bridge
    if ((acc & Opcodes.ACC_SYNTHETIC) != 0) return false;
    if ((acc & Opcodes.ACC_BRIDGE) != 0) return false;

    // abstract / native have no Code
    if ((acc & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;

    // helpers / generated
    if (mn.name != null) {
      if (mn.name.startsWith("lambda$")) return false;
      if (mn.name.startsWith("access$")) return false;
      // enum boilerplate on enum classes
      if (isEnum) {
        if (mn.name.equals("values") && mn.desc.equals("()[L" + cn.name + ";")) return false;
        if (mn.name.equals("valueOf") && mn.desc.equals("(Ljava/lang/String;)L" + cn.name + ";"))
          return false;
      }
    }

    // must have bytecode instructions
    return mn.instructions != null && mn.instructions.size() > 0;
  }
}
