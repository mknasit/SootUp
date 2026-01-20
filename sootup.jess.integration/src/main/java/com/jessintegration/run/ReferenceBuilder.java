package com.jessintegration.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ReferenceBuilder {

  private ReferenceBuilder() {}

  /** Builds the main JAR if missing, detects Maven or Gradle build system. */
  public static boolean buildMainJarIfNeeded(
      Path projectDir, List<String> classpathJars, boolean debug)
      throws IOException, InterruptedException {
    // First, check if any of the classpath JARs already exist
    if (classpathJars != null && !classpathJars.isEmpty()) {
      for (String jarPath : classpathJars) {
        Path jarFile = Path.of(jarPath);
        if (!jarFile.isAbsolute()) {
          jarFile = projectDir.resolve(jarPath);
        }
        if (Files.exists(jarFile) && Files.isRegularFile(jarFile)) {
          if (debug) {
            System.out.println("[build] JAR already exists: " + jarFile + ", skipping build");
          }
          return true; // JAR found, no build needed
        }
      }
    }

    // JAR not found, try to find it in standard locations
    if (jarExistsInStandardLocations(projectDir, debug)) {
      return true; // JAR found in standard location
    }

    // JAR not found, need to build
    if (debug) {
      System.out.println("[build] JAR not found, building project...");
    }

    // Detect build system
    boolean isGradle = isGradleProject(projectDir);
    boolean isMaven = isMavenProject(projectDir);

    if (isGradle) {
      buildWithGradle(projectDir, debug);
    } else if (isMaven) {
      buildWithMaven(projectDir, debug);
    } else {
      throw new IOException(
          "No build system detected (no pom.xml, build.gradle, or build.gradle.kts found) in "
              + projectDir);
    }
    return false; // Build was attempted
  }

  /** @deprecated Use buildMainJarIfNeeded instead */
  @Deprecated
  public static void buildMainJarWithMaven(Path projectDir, boolean debug)
      throws IOException, InterruptedException {
    buildMainJarIfNeeded(projectDir, null, debug);
  }

  private static boolean jarExistsInStandardLocations(Path projectDir, boolean debug) {
    // Check Maven target directory
    Path mavenTarget = projectDir.resolve("target");
    if (Files.isDirectory(mavenTarget)) {
      try {
        if (Files.list(mavenTarget)
            .anyMatch(
                p ->
                    p.getFileName().toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-tests")
                        && !p.getFileName().toString().contains("-test")
                        && !p.getFileName().toString().contains("-sources")
                        && !p.getFileName().toString().contains("-javadoc"))) {
          if (debug) {
            System.out.println("[build] JAR found in target/ directory, skipping build");
          }
          return true;
        }
      } catch (IOException e) {
        // Ignore, will try to build
      }
    }

    // Check Gradle build/libs directory
    Path gradleBuild = projectDir.resolve("build").resolve("libs");
    if (Files.isDirectory(gradleBuild)) {
      try {
        if (Files.list(gradleBuild)
            .anyMatch(
                p ->
                    p.getFileName().toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-tests")
                        && !p.getFileName().toString().contains("-test")
                        && !p.getFileName().toString().contains("-sources")
                        && !p.getFileName().toString().contains("-javadoc"))) {
          if (debug) {
            System.out.println("[build] JAR found in build/libs/ directory, skipping build");
          }
          return true;
        }
      } catch (IOException e) {
        // Ignore, will try to build
      }
    }

    return false;
  }

  private static boolean isMavenProject(Path projectDir) {
    return Files.exists(projectDir.resolve("pom.xml"));
  }

  private static boolean isGradleProject(Path projectDir) {
    return Files.exists(projectDir.resolve("build.gradle"))
        || Files.exists(projectDir.resolve("build.gradle.kts"))
        || Files.exists(projectDir.resolve("settings.gradle"))
        || Files.exists(projectDir.resolve("settings.gradle.kts"));
  }

  private static void buildWithMaven(Path projectDir, boolean debug)
      throws IOException, InterruptedException {
    // Skip running tests and javadoc generation; just build the main artifact.
    // Also skip toolchain plugin to avoid toolchain configuration issues
    List<String> cmd =
        List.of(
            "mvn",
            "-q",
            "-DskipTests",
            "-DskipITs",
            "-Dmaven.javadoc.skip=true",
            "-Dmaven.toolchain.skip=true",
            "clean",
            "package");
    if (debug) {
      System.out.println("[build] " + String.join(" ", cmd));
      System.out.println("[build] cwd=" + projectDir);
    }
    Process p = new ProcessBuilder(cmd).directory(projectDir.toFile()).inheritIO().start();
    boolean ok = p.waitFor(20, TimeUnit.MINUTES);
    if (!ok) {
      p.destroyForcibly();
      throw new IOException("Maven build timed out for " + projectDir);
    }
    if (p.exitValue() != 0) {
      // Check if it's a toolchain error - if so, try without clean
      if (debug) {
        System.out.println(
            "[build] Maven build failed, trying without clean (may be toolchain issue)...");
      }
      List<String> cmdNoClean =
          List.of(
              "mvn",
              "-q",
              "-DskipTests",
              "-DskipITs",
              "-Dmaven.javadoc.skip=true",
              "-Dmaven.toolchain.skip=true",
              "package");
      Process p2 =
          new ProcessBuilder(cmdNoClean).directory(projectDir.toFile()).inheritIO().start();
      boolean ok2 = p2.waitFor(20, TimeUnit.MINUTES);
      if (!ok2) {
        p2.destroyForcibly();
        throw new IOException("Maven build timed out for " + projectDir);
      }
      if (p2.exitValue() != 0) {
        throw new IOException("Maven build failed (exit " + p2.exitValue() + ") for " + projectDir);
      }
    }
    if (debug) System.out.println("[build] done.");
  }

  private static void buildWithGradle(Path projectDir, boolean debug)
      throws IOException, InterruptedException {
    // Use Gradle wrapper if available, otherwise use system gradle
    String gradleCmd = "gradle";
    Path gradlew = projectDir.resolve("gradlew");
    if (Files.exists(gradlew)) {
      gradleCmd = gradlew.toAbsolutePath().toString();
    }

    // Skip tests and build the main artifact
    List<String> cmd =
        List.of(gradleCmd, "--quiet", "clean", "build", "-x", "test"); // Exclude tests
    if (debug) {
      System.out.println("[build] " + String.join(" ", cmd));
      System.out.println("[build] cwd=" + projectDir);
    }
    Process p = new ProcessBuilder(cmd).directory(projectDir.toFile()).inheritIO().start();
    boolean ok = p.waitFor(20, TimeUnit.MINUTES);
    if (!ok) {
      p.destroyForcibly();
      throw new IOException("Gradle build timed out for " + projectDir);
    }
    if (p.exitValue() != 0) {
      throw new IOException("Gradle build failed (exit " + p.exitValue() + ") for " + projectDir);
    }
    if (debug) System.out.println("[build] done.");
  }
}
