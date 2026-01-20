package com.jessintegration.run;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JarFinder {

  private JarFinder() {}

  /**
   * Finds the main artifact JAR, optionally checking classpathJars first.
   *
   * @param projectDir Project root directory
   * @param classpathJars Optional list of classpath JAR paths (relative to projectDir or absolute)
   * @param debug Enable debug output
   * @return Path to the main JAR
   * @throws IOException if JAR not found
   */
  public static Path findMainArtifactJar(Path projectDir, List<String> classpathJars, boolean debug)
      throws IOException {
    // First, check if any classpathJars exist (these are the explicitly specified JARs)
    if (classpathJars != null && !classpathJars.isEmpty()) {
      for (String jarPath : classpathJars) {
        Path jarFile = Path.of(jarPath);
        if (!jarFile.isAbsolute()) {
          jarFile = projectDir.resolve(jarPath);
        }
        if (Files.exists(jarFile) && Files.isRegularFile(jarFile)) {
          if (debug) {
            System.out.println("[jarfinder] Found JAR from classpathJars: " + jarFile);
          }
          return jarFile;
        }
        // If it's a directory (like "target/classes"), check for JARs inside
        if (Files.isDirectory(jarFile)) {
          try {
            List<Path> jarsInDir =
                Files.list(jarFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(
                        p -> {
                          String n = p.getFileName().toString().toLowerCase();
                          return !n.contains("-tests")
                              && !n.contains("-test")
                              && !n.contains("-sources")
                              && !n.contains("-javadoc");
                        })
                    .collect(java.util.stream.Collectors.toList());
            if (!jarsInDir.isEmpty()) {
              // Return the largest JAR
              Path pick =
                  jarsInDir.stream()
                      .max(
                          java.util.Comparator.comparingLong(
                              p -> {
                                try {
                                  return Files.size(p);
                                } catch (IOException e) {
                                  return -1L;
                                }
                              }))
                      .orElse(jarsInDir.get(0));
              if (debug) {
                System.out.println("[jarfinder] Found JAR in classpathJars directory: " + pick);
              }
              return pick;
            }
          } catch (IOException e) {
            // Continue to standard locations
          }
        }
      }
    }

    // Try Maven location first, then Gradle
    Path mavenTarget = projectDir.resolve("target");
    Path gradleBuild = projectDir.resolve("build").resolve("libs");

    List<Path> searchDirs = new ArrayList<>();
    if (Files.isDirectory(mavenTarget)) {
      searchDirs.add(mavenTarget);
    }
    if (Files.isDirectory(gradleBuild)) {
      searchDirs.add(gradleBuild);
    }

    // If not found in root, check common subdirectories (for multi-module projects)
    if (searchDirs.isEmpty()) {
      try (Stream<Path> subdirs = Files.list(projectDir)) {
        for (Path subdir : subdirs.collect(Collectors.toList())) {
          if (!Files.isDirectory(subdir)) continue;
          Path subMavenTarget = subdir.resolve("target");
          Path subGradleBuild = subdir.resolve("build").resolve("libs");
          if (Files.isDirectory(subMavenTarget)) {
            searchDirs.add(subMavenTarget);
          }
          if (Files.isDirectory(subGradleBuild)) {
            searchDirs.add(subGradleBuild);
          }
        }
      } catch (IOException e) {
        // Ignore, will throw below if no dirs found
      }
    }

    if (searchDirs.isEmpty()) {
      throw new IOException(
          "No JAR directory found (neither target/ nor build/libs/ found) under " + projectDir);
    }

    // Collect candidate jars from all search directories
    List<Path> jars = new ArrayList<>();
    for (Path searchDir : searchDirs) {
      if (debug) System.out.println("[jarfinder] scanning " + searchDir);
      try (Stream<Path> s = Files.list(searchDir)) {
        List<Path> found =
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).collect(Collectors.toList());
        jars.addAll(found);
      }
    }

    if (jars.isEmpty()) {
      throw new IOException("No JARs found in " + searchDirs);
    }

    // Filter out non-main classifiers
    List<String> ban =
        List.of(
            "-tests",
            "-test",
            "-test-sources",
            "-sources",
            "-javadoc",
            "-original",
            "-sources.jar");
    List<Path> mainish =
        jars.stream()
            .filter(
                p -> {
                  String n = p.getFileName().toString();
                  if (n.equalsIgnoreCase("classes.jar")) return false; // Android/odd
                  String lower = n.toLowerCase();
                  return ban.stream().noneMatch(lower::contains);
                })
            .collect(Collectors.toList());

    List<Path> pool = mainish.isEmpty() ? jars : mainish;

    // Prefer the largest as a heuristic for "main"
    Path pick =
        pool.stream()
            .max(
                Comparator.comparingLong(
                    p -> {
                      try {
                        return Files.size(p);
                      } catch (IOException e) {
                        return -1L;
                      }
                    }))
            .orElse(pool.get(0));

    if (debug) {
      System.out.println("[jarfinder] candidates:");
      for (Path j : jars) {
        long sz = -1L;
        try {
          sz = Files.size(j);
        } catch (IOException ignored) {
        }
        System.out.println("  - " + j.getFileName() + " (" + sz + " bytes)");
      }
      System.out.println("[jarfinder] chosen: " + pick);
    }

    return pick;
  }

  /**
   * Legacy method for backward compatibility.
   *
   * @deprecated Use {@link #findMainArtifactJar(Path, List, boolean)} instead
   */
  @Deprecated
  public static Path findMainArtifactJar(Path projectDir, boolean debug) throws IOException {
    return findMainArtifactJar(projectDir, null, debug);
  }
}
