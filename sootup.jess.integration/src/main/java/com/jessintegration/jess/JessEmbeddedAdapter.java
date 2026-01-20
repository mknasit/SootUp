package com.jessintegration.jess;

import com.jessintegration.model.JessOptions;
import com.jessintegration.model.JessResult;
import com.jessintegration.model.MethodId;
import de.upb.sse.jess.Jess;
import de.upb.sse.jess.api.PublicApi;
import de.upb.sse.jess.configuration.JessConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class JessEmbeddedAdapter implements JessApi {

  private final boolean debug;

  public JessEmbeddedAdapter() {
    this(false);
  }

  public JessEmbeddedAdapter(boolean debug) {
    this.debug = debug;
  }

  @Override
  public JessResult compileMethod(Path repoRoot, MethodId method, JessOptions options) {
    return compileMethod(repoRoot, "src/main/java", method, options);
  }

  @Override
  public JessResult compileMethod(
      Path repoRoot, String sourceRoot, MethodId method, JessOptions options) {
    try {
      // --- sanitize sourceRoot if it accidentally contains the package path ---
      final String binaryClass = method.binaryClassName(); // e.g. org/apache/commons/io/ByteBuffers
      final int slash = binaryClass.lastIndexOf('/');
      final String pkgPath = (slash >= 0) ? binaryClass.substring(0, slash) : "";
      String sanitizedRoot = sourceRoot;

      if (!pkgPath.isEmpty() && sourceRoot.replace('\\', '/').endsWith(pkgPath)) {
        sanitizedRoot = sourceRoot.substring(0, sourceRoot.length() - pkgPath.length());
        while (sanitizedRoot.endsWith("/") || sanitizedRoot.endsWith("\\")) {
          sanitizedRoot = sanitizedRoot.substring(0, sanitizedRoot.length() - 1);
        }
        if (debug) {
          System.out.println(
              "[jess] sourceRoot looked too deep; trimming package suffix '" + pkgPath + "'");
          System.out.println("[jess] sourceRoot: '" + sourceRoot + "' -> '" + sanitizedRoot + "'");
        }
      }

      final Path srcRootAbs = repoRoot.resolve(sanitizedRoot).normalize();
      final List<String> packageRoots = List.of(srcRootAbs.toString());

      // ---------- Build/collect compile classpath ----------
      // 1) Start from options.extraClasspath() if provided
      final List<String> cpAll =
          options.extraClasspath() == null ? List.of() : options.extraClasspath();

      final List<String> cpAllAbs;
      if (cpAll.isEmpty()) {
        // 2) Auto-build with Maven for generic projects (compile-scope jars)
        if (debug)
          System.out.println(
              "[jess] extraClasspath empty -> building with mvn dependency:build-classpath");
        List<String> jars = buildMavenCompileClasspath(repoRoot);
        // Always add target/classes if present
        Path classesDir = repoRoot.resolve("target").resolve("classes");
        List<String> tmp = new ArrayList<>(jars);
        if (Files.isDirectory(classesDir)) tmp.add(classesDir.toString());
        cpAllAbs = tmp;
      } else {
        // Make provided CP absolute + normalized
        cpAllAbs =
            cpAll.stream()
                .map(Path::of)
                .map(p -> p.isAbsolute() ? p : repoRoot.resolve(p).normalize())
                .map(Path::toString)
                .collect(Collectors.toList());
      }

      // JESS TypeSolver jar list: ONLY .jar
      final List<String> jarsOnly =
          cpAllAbs.stream().filter(s -> s.endsWith(".jar")).collect(Collectors.toList());

      if (debug) {
        System.out.println("[jess] ==== compileSingleMethod ====");
        System.out.println("[jess] repoRoot      = " + repoRoot);
        System.out.println("[jess] sourceRoot    = " + sanitizedRoot + " -> " + srcRootAbs);
        System.out.println(
            "[jess] target method = "
                + method.binaryClassName()
                + "#"
                + method.name()
                + method.jvmDescriptor());
        System.out.println("[jess] packageRoots  = " + packageRoots);
        System.out.println("[jess] jarsOnly      = " + jarsOnly);
        System.out.println("[jess] javac CP      = " + cpAllAbs);
        System.out.println("[jess] workDir       = " + options.workDir());
      }

      // Quick source existence probe (top-level class)
      Path probableSource = srcRootAbs.resolve(method.binaryClassName() + ".java");
      if (debug) {
        System.out.println(
            "[jess] probe source: "
                + probableSource
                + " exists="
                + Files.isRegularFile(probableSource));
      }

      // Construct and call JESS
      JessConfiguration cfg = new JessConfiguration();
      Jess jess = new Jess(cfg, packageRoots, jarsOnly);

      PublicApi.MethodId mid =
          new PublicApi.MethodId(method.binaryClassName(), method.name(), method.jvmDescriptor());

      PublicApi.Options opts =
          new PublicApi.Options(
              options.depMode(), // "none" | "provided" | "fetched"
              options.sliceMode(), // "method" | "class"
              options.timeoutSec(),
              cpAllAbs.stream().map(Path::of).collect(Collectors.toList()), // javac CP: jars + dirs
              options.workDir()); // JESS writes to workDir/classes

      if (debug) System.out.println("[jess] calling compileSingleMethod...");
      PublicApi.Result r = jess.compileSingleMethod(repoRoot, sanitizedRoot, mid, opts);
      if (debug) {
        System.out.println("[jess] RESULT.status          = " + r.status);
        System.out.println("[jess] RESULT.notes           = " + r.notes);
        System.out.println("[jess] RESULT.classesOutDir   = " + r.classesOutDir);
        System.out.println("[jess] RESULT.targetClass     = " + r.targetClass);
        System.out.println(
            "[jess] RESULT.emittedClasses  = "
                + (r.emittedClasses == null ? "null" : r.emittedClasses));
        System.out.println("[jess] RESULT.targetClassFile = " + r.targetClassFile);
        System.out.println("[jess] RESULT.targetHasCode   = " + r.targetHasCode);
        System.out.println("[jess] RESULT.depsResolved    = " + r.depsResolved);
        System.out.println("[jess] RESULT.usedStubs       = " + r.usedStubs);
        System.out.println("[jess] RESULT.elapsedMs       = " + r.elapsedMs);
      }

      return toJessResult(r);
    } catch (Throwable t) {
      if (debug) {
        System.out.println("[jess] EXCEPTION: " + t.getClass().getName() + " - " + t.getMessage());
        t.printStackTrace(System.out);
      }
      return new JessResult(
          JessResult.Status.INTERNAL_ERROR,
          null,
          null,
          List.of(),
          false,
          false,
          0L,
          "Adapter exception: " + t.getMessage());
    }
  }

  private JessResult toJessResult(PublicApi.Result r) {
    JessResult.Status status;
    switch (Objects.requireNonNull(r.status)) {
      case OK -> status = JessResult.Status.OK;
      case FAILED_PARSE -> status = JessResult.Status.FAILED_PARSE;
      case FAILED_RESOLVE -> status = JessResult.Status.FAILED_RESOLVE;
      case FAILED_COMPILE -> status = JessResult.Status.FAILED_COMPILE;
      case TARGET_METHOD_NOT_EMITTED ->
          status = JessResult.Status.FAILED_COMPILE; // map to compile-fail in our model
      default -> status = JessResult.Status.INTERNAL_ERROR; // e.g., TIMEOUT, MISSING_DEP
    }

    boolean depsResolvedBool = r.depsResolved != null && !r.depsResolved.equalsIgnoreCase("none");

    // Always carry targetHasCode/targetClassFile into notes so CSV gets it even if JessResult model
    // has no fields for them
    String extra =
        "targetHasCode="
            + r.targetHasCode
            + (r.targetClassFile != null ? " targetClassFile=" + r.targetClassFile : "");
    String notes = (r.notes == null || r.notes.isEmpty()) ? extra : (r.notes + " | " + extra);

    return new JessResult(
        status,
        r.classesOutDir,
        r.targetClass,
        r.emittedClasses == null ? List.of() : r.emittedClasses,
        r.usedStubs,
        depsResolvedBool,
        r.elapsedMs,
        notes);
  }

  // Build compile-scope classpath via Maven; returns absolute jar paths
  private List<String> buildMavenCompileClasspath(Path repoRoot) {
    List<String> jars = new ArrayList<>();
    try {
      Process p =
          new ProcessBuilder(
                  "mvn",
                  "-q",
                  "-DincludeScope=compile",
                  "dependency:build-classpath",
                  "-Dmdep.outputFile=cp.txt")
              .directory(repoRoot.toFile())
              .inheritIO()
              .start();
      int code = p.waitFor();
      if (code != 0 && debug) {
        System.out.println("[jess] mvn dependency:build-classpath exit=" + code);
      }
      Path cpFile = repoRoot.resolve("cp.txt");
      if (Files.isRegularFile(cpFile)) {
        String raw = Files.readString(cpFile);
        String sep = System.getProperty("path.separator");
        for (String tok : raw.split(Pattern.quote(sep))) {
          tok = tok.trim();
          if (!tok.isEmpty() && tok.endsWith(".jar")) {
            Path pth = Path.of(tok);
            jars.add(
                pth.isAbsolute() ? pth.toString() : repoRoot.resolve(pth).normalize().toString());
          }
        }
      } else if (debug) {
        System.out.println("[jess] cp.txt not found after dependency:build-classpath");
      }
    } catch (Throwable t) {
      if (debug) System.out.println("[jess] could not build classpath: " + t);
    }
    // De-dup while preserving order
    return new ArrayList<>(new LinkedHashSet<>(jars));
  }
}
