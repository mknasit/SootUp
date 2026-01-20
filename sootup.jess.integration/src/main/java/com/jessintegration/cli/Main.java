package com.jessintegration.cli;

import com.jessintegration.config.ProjectEntry;
import com.jessintegration.config.ProjectListConfig;
import com.jessintegration.discovery.MethodEnumerator;
import com.jessintegration.jess.JessApi;
import com.jessintegration.jess.JessEmbeddedAdapter;
import com.jessintegration.model.JessOptions;
import com.jessintegration.model.MethodId;
import com.jessintegration.run.JarFinder;
import com.jessintegration.run.ReferenceBuilder;
import com.jessintegration.run.RepoRunner;
import com.jessintegration.run.StatisticsAggregator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine;

@CommandLine.Command(name = "sootup-jess-integration")
public final class Main implements Runnable {

  @CommandLine.Option(names = "--pcfg", description = "Path to JSON array config (projects)")
  Path pcfg;

  @CommandLine.Option(names = "--repo")
  Path repo;

  @CommandLine.Option(names = "--ref-jar")
  Path refJar;

  @CommandLine.Option(names = "--pkg", defaultValue = "")
  String pkgPrefix;

  @CommandLine.Option(names = "--limit", defaultValue = "100")
  int limit;

  @CommandLine.Option(names = "--work")
  Path workDir;

  @CommandLine.Option(names = "--strict", defaultValue = "false")
  boolean strict;

  @CommandLine.Option(names = "--dump", defaultValue = "false")
  boolean dump;

  @CommandLine.Option(names = "--debug", defaultValue = "false")
  boolean debug;

  @CommandLine.Option(
      names = "--result-base-dir",
      description = "Base directory containing project result subdirectories for aggregation")
  Path resultBaseDir;

  public static void main(String[] args) {
    new CommandLine(new Main()).execute(args);
  }

  @Override
  public void run() {
    try {
      if (pcfg != null) runProjects(pcfg);
      else runSingle();
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private void runProjects(Path cfg) throws Exception {
    List<ProjectEntry> items = ProjectListConfig.load(cfg);
    JessApi jess = new JessEmbeddedAdapter(debug);
    List<Path> resultDirs = new ArrayList<>();

    for (ProjectEntry p : items) {
      if (p.projectDir == null) {
        System.err.println("Skipping (projectDir missing): " + p.name);
        continue;
      }

      // 1) Build MAIN jar only if it doesn't already exist
      // If sourceRoots point to a subdirectory, try building that subdirectory first
      Path buildDir = p.projectDir;
      if (p.sourceRoots != null && !p.sourceRoots.isEmpty()) {
        String firstSourceRoot = p.sourceRoots.get(0);
        // If sourceRoot is like "byte-buddy/src/main/java", extract "byte-buddy"
        if (firstSourceRoot.contains("/")) {
          String subdir = firstSourceRoot.split("/")[0];
          Path subdirPath = p.projectDir.resolve(subdir);
          if (Files.exists(subdirPath) && Files.isDirectory(subdirPath)) {
            // Check if this subdirectory has a build file
            if (Files.exists(subdirPath.resolve("pom.xml"))
                || Files.exists(subdirPath.resolve("build.gradle"))
                || Files.exists(subdirPath.resolve("build.gradle.kts"))) {
              buildDir = subdirPath;
              if (debug) {
                System.out.println("[build] Using subdirectory for build: " + buildDir);
              }
            }
          }
        }
      }
      try {
        ReferenceBuilder.buildMainJarIfNeeded(buildDir, p.classpathJars, debug);
      } catch (IOException e) {
        System.err.println("[WARN] Failed to build project " + p.name + ": " + e.getMessage());
        if (debug) {
          e.printStackTrace();
        }
        System.err.println("[WARN] Skipping project " + p.name);
        continue; // Skip this project and continue with next
      }

      // 2) Pick MAIN jar (not tests/sources/javadoc)
      List<String> cpJars = (p.classpathJars == null) ? List.of() : p.classpathJars;
      Path jar = JarFinder.findMainArtifactJar(p.projectDir, cpJars, debug);

      // 3) Enumerate methods from the main jar
      var pkgOpt =
          (p.pkg == null || p.pkg.isBlank())
              ? Optional.<String>empty()
              : Optional.of(p.pkg.replace('.', '/'));
      int lim = p.limit == null ? 100 : p.limit;
      List<MethodId> methods = MethodEnumerator.fromJar(jar, pkgOpt, lim);

      // 4) Options: classpath (abs), plus the chosen jar
      Path outRoot = (p.workDir != null) ? p.workDir : p.projectDir.resolve("pc-out");
      List<String> cpConf = (p.classpathJars == null) ? List.of() : p.classpathJars;
      List<String> absCp =
          cpConf.stream()
              .map(Path::of)
              .map(pp -> pp.isAbsolute() ? pp : p.projectDir.resolve(pp).normalize())
              .map(Path::toString)
              .collect(Collectors.toList());

      List<String> cpPlusJar = new ArrayList<>(absCp);
      cpPlusJar.add(jar.toString()); // ensure the ref jar is available to javac / jarsOnly

      var opts =
          new JessOptions(
              "none", // depMode
              "method", // sliceMode
              60, // timeout
              cpPlusJar, // javac CP (jars + dirs) + the main jar
              outRoot // work dir
              );

      // 5) Source roots (prefer main root only; do NOT pass package segments)
      List<String> srcs =
          (p.sourceRoots == null || p.sourceRoots.isEmpty())
              ? List.of("src/main/java")
              : p.sourceRoots;

      Path resultDir = outRoot.resolve("out");
      new RepoRunner(jess, this.strict, this.dump, this.debug)
          .run(p.projectDir, jar, srcs, methods, opts, resultDir);

      resultDirs.add(resultDir);
      System.out.println("[OK] " + (p.name != null ? p.name : p.projectDir) + " → " + resultDir);
    }

    // Aggregate statistics from all projects
    if (resultBaseDir != null) {
      try {
        Path combinedJson = resultBaseDir.resolve("combined_statistics.json");
        StatisticsAggregator.aggregateAndWrite(resultBaseDir, combinedJson);
        System.out.println("[OK] Combined statistics written to: " + combinedJson);
      } catch (Exception e) {
        System.err.println("[WARN] Failed to aggregate statistics: " + e.getMessage());
        if (debug) e.printStackTrace();
      }
    } else if (!resultDirs.isEmpty()) {
      // Try to find a common parent directory
      Path commonParent = findCommonParent(resultDirs);
      if (commonParent != null && java.nio.file.Files.exists(commonParent)) {
        try {
          Path combinedJson = commonParent.resolve("combined_statistics.json");
          StatisticsAggregator.aggregateAndWrite(commonParent, combinedJson);
          System.out.println("[OK] Combined statistics written to: " + combinedJson);
        } catch (Exception e) {
          System.err.println("[WARN] Failed to aggregate statistics: " + e.getMessage());
          if (debug) e.printStackTrace();
        }
      }
    }

    System.out.println("All projects done.");
  }

  private static Path findCommonParent(List<Path> paths) {
    if (paths.isEmpty()) return null;
    Path common = paths.get(0).getParent();
    for (int i = 1; i < paths.size(); i++) {
      Path current = paths.get(i).getParent();
      while (common != null && !common.equals(current) && !current.startsWith(common)) {
        common = common.getParent();
      }
      if (common == null) return null;
    }
    return common;
  }

  private void runSingle() throws Exception {
    if (repo == null || refJar == null || workDir == null) {
      throw new IllegalArgumentException("Provide --repo, --ref-jar, --work OR use --pcfg");
    }
    JessApi jess = new JessEmbeddedAdapter(debug);
    var opts = new JessOptions("none", "method", 60, List.of(refJar.toString()), workDir);
    var methods = MethodEnumerator.fromJar(refJar, opt(pkgPrefix), limit);
    new RepoRunner(jess, strict, dump, debug)
        .run(repo, refJar, List.of("src/main/java"), methods, opts, workDir.resolve("out"));
    System.out.println("Done: " + workDir.resolve("out"));
  }

  private static Optional<String> opt(String s) {
    return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.replace('.', '/'));
  }
}
