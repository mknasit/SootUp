package com.jessintegration.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.jessintegration.config.ProjectEntry;
import com.jessintegration.config.ProjectListConfig;
import com.jessintegration.jess.JessApi;
import com.jessintegration.jess.JessEmbeddedAdapter;
import com.jessintegration.model.JessOptions;
import com.jessintegration.run.JarFinder;
import com.jessintegration.run.ReferenceBuilder;
import com.jessintegration.run.RepoRunner;
import com.jessintegration.run.StatisticsAggregator;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that mimics the CLI command and produces CSV and summary files.
 *
 * <p>This test does the same thing as running: java -jar ...jar --pcfg project.json --strict=false
 * --dump=true --debug=true
 *
 * <p>Run with: mvn test -Dtest=EndToEndTest
 */
public class EndToEndTest {

  // Path to your project.json file
  private static final String PROJECT_JSON_PATH =
      System.getenv()
          .getOrDefault(
              "PROJECT_JSON_PATH", "/Users/mitul/Documents/study/Thesis/SootUp/project.json");

  // Fixed results directory
  private static final Path RESULTS_DIR =
      Paths.get(
          "/Users/mitul/Documents/study/Thesis/SootUp/sootup.jess.integration/src/main/java/com/jessintegration/result");

  @Test
  void testEndToEndWorkflow() throws Exception {
    Path projectJson = Paths.get(PROJECT_JSON_PATH);

    // Skip if project.json doesn't exist
    if (!Files.exists(projectJson)) {
      System.out.println("Skipping test: project.json not found at " + PROJECT_JSON_PATH);
      System.out.println(
          "Set PROJECT_JSON_PATH environment variable to point to your project.json");
      return;
    }

    System.out.println("Loading projects from: " + projectJson);
    List<ProjectEntry> projects = ProjectListConfig.load(projectJson);

    assertFalse(projects.isEmpty(), "Should load at least one project from project.json");

    // Create base test results directory (fixed location)
    Files.createDirectories(RESULTS_DIR);
    System.out.println("Test results will be saved to: " + RESULTS_DIR.toAbsolutePath());

    JessApi jess = new JessEmbeddedAdapter(true); // debug=true

    int successCount = 0;
    int failureCount = 0;

    for (ProjectEntry project : projects) {
      // Create project-specific output directory early (for error logs)
      String projectFolderName =
          project.name != null ? project.name : "project-" + System.currentTimeMillis();
      Path projectOutputDir = RESULTS_DIR.resolve(projectFolderName);
      Files.createDirectories(projectOutputDir);

      try {
        if (project.projectDir == null || !Files.exists(project.projectDir)) {
          String errorMsg = "Project directory not found: " + project.projectDir;
          System.err.println("[ERROR] " + errorMsg);
          writeErrorLog(projectOutputDir, project.name, errorMsg, null);
          failureCount++;
          continue;
        }

        System.out.println("\n========================================");
        System.out.println("Processing project: " + project.name);
        System.out.println("========================================\n");

        // 1. Build MAIN jar only if it doesn't exist
        System.out.println("[Step 1] Building project JAR (if needed)...");
        try {
          ReferenceBuilder.buildMainJarIfNeeded(project.projectDir, project.classpathJars, true);
        } catch (Exception e) {
          String errorMsg = "Failed to build project: " + e.getMessage();
          System.err.println("[ERROR] " + errorMsg);
          writeErrorLog(projectOutputDir, project.name, errorMsg, e);
          failureCount++;
          continue; // Skip this project and continue with next
        }

        // 2. Find the JAR
        System.out.println("[Step 2] Finding JAR...");
        Path jar;
        try {
          List<String> cpJars = (project.classpathJars == null) ? List.of() : project.classpathJars;
          jar = JarFinder.findMainArtifactJar(project.projectDir, cpJars, true);
        } catch (Exception e) {
          String errorMsg = "Failed to find JAR: " + e.getMessage();
          System.err.println("[ERROR] " + errorMsg);
          writeErrorLog(projectOutputDir, project.name, errorMsg, e);
          failureCount++;
          continue;
        }
        System.out.println("Found JAR: " + jar);

        // 3. Enumerate methods
        System.out.println("[Step 3] Enumerating methods...");
        var pkgOpt =
            (project.pkg == null || project.pkg.isBlank())
                ? Optional.<String>empty()
                : Optional.of(project.pkg.replace('.', '/'));
        int limit = project.limit == null ? 100 : project.limit;
        List<com.jessintegration.model.MethodId> methods;
        try {
          methods = com.jessintegration.discovery.MethodEnumerator.fromJar(jar, pkgOpt, limit);
        } catch (Exception e) {
          String errorMsg = "Failed to enumerate methods: " + e.getMessage();
          System.err.println("[ERROR] " + errorMsg);
          writeErrorLog(projectOutputDir, project.name, errorMsg, e);
          failureCount++;
          continue;
        }
        System.out.println("Found " + methods.size() + " methods");

        // 4. Setup options - use original workDir for Jess work, but output results to project
        // folder
        Path outRoot =
            (project.workDir != null) ? project.workDir : project.projectDir.resolve("pc-out");
        List<String> cpConf = (project.classpathJars == null) ? List.of() : project.classpathJars;
        List<String> absCp =
            cpConf.stream()
                .map(Path::of)
                .map(pp -> pp.isAbsolute() ? pp : project.projectDir.resolve(pp).normalize())
                .map(Path::toString)
                .collect(java.util.stream.Collectors.toList());

        List<String> cpPlusJar = new java.util.ArrayList<>(absCp);
        cpPlusJar.add(jar.toString());

        var options =
            new JessOptions(
                "none", // depMode
                "method", // sliceMode
                60, // timeout
                cpPlusJar, // classpath
                outRoot // workDir (for Jess temporary files)
                );

        // 5. Source roots
        List<String> srcs =
            (project.sourceRoots == null || project.sourceRoots.isEmpty())
                ? List.of("src/main/java")
                : project.sourceRoots;

        System.out.println(
            "[Step 4] Results will be saved to: " + projectOutputDir.toAbsolutePath());

        // 6. Run RepoRunner (this produces CSV and summary)
        System.out.println("[Step 5] Running analysis...");
        try {
          new RepoRunner(jess, false, true, true) // strict=false, dump=true, debug=true
              .run(project.projectDir, jar, srcs, methods, options, projectOutputDir);
        } catch (Exception e) {
          String errorMsg = "Failed during analysis: " + e.getMessage();
          System.err.println("[ERROR] " + errorMsg);
          writeErrorLog(projectOutputDir, project.name, errorMsg, e);
          failureCount++;
          continue;
        }

        // 7. Verify output files were created
        Path csvFile = projectOutputDir.resolve("results.csv");
        Path summaryFile = projectOutputDir.resolve("summary.txt");

        System.out.println("\n[Step 6] Verifying output files...");
        if (!Files.exists(csvFile)) {
          String errorMsg = "CSV file was not created: " + csvFile;
          System.err.println("[ERROR] " + errorMsg);
          writeErrorLog(projectOutputDir, project.name, errorMsg, null);
          failureCount++;
          continue;
        }
        if (!Files.exists(summaryFile)) {
          String errorMsg = "Summary file was not created: " + summaryFile;
          System.err.println("[ERROR] " + errorMsg);
          writeErrorLog(projectOutputDir, project.name, errorMsg, null);
          failureCount++;
          continue;
        }

        // 8. Print summary
        if (Files.exists(summaryFile)) {
          System.out.println("\n=== SUMMARY for " + project.name + " ===");
          Files.lines(summaryFile).forEach(System.out::println);
          System.out.println("========================================\n");
        }

        System.out.println("✓ Project " + project.name + " completed successfully!");
        System.out.println("  Results folder: " + projectOutputDir.toAbsolutePath());
        System.out.println("  CSV: " + csvFile.getFileName());
        System.out.println("  Summary: " + summaryFile.getFileName());
        successCount++;

      } catch (Exception e) {
        // Catch any unexpected errors
        String errorMsg = "Unexpected error processing project: " + e.getMessage();
        System.err.println("[ERROR] " + errorMsg);
        writeErrorLog(projectOutputDir, project.name, errorMsg, e);
        failureCount++;
        // Continue to next project
      }
    }

    System.out.println("\n========================================");
    System.out.println("Test Run Summary");
    System.out.println("========================================");
    System.out.println("Total projects: " + projects.size());
    System.out.println("Successful: " + successCount);
    System.out.println("Failed: " + failureCount);
    System.out.println("All results saved to: " + RESULTS_DIR.toAbsolutePath());
    if (failureCount > 0) {
      System.out.println(
          "\n⚠️  Some projects failed. Check error.log files in result directories.");
    } else {
      System.out.println("\n✅ All projects processed successfully!");
    }

    // Generate combined statistics JSON
    System.out.println("\n[Step 7] Generating combined statistics...");
    try {
      Path combinedJson = RESULTS_DIR.resolve("combined_statistics.json");
      StatisticsAggregator.aggregateAndWrite(RESULTS_DIR, combinedJson);
      System.out.println("✓ Combined statistics written to: " + combinedJson.toAbsolutePath());
    } catch (Exception e) {
      System.err.println("[WARN] Failed to generate combined statistics: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Writes an error log file to the project's result directory.
   *
   * @param projectOutputDir The project's output directory
   * @param projectName The project name
   * @param errorMessage The error message
   * @param exception The exception (if any)
   */
  private static void writeErrorLog(
      Path projectOutputDir, String projectName, String errorMessage, Exception exception) {
    try {
      Path errorLogFile = projectOutputDir.resolve("error.log");
      StringBuilder logContent = new StringBuilder();
      logContent.append("========================================\n");
      logContent.append("ERROR LOG for Project: ").append(projectName).append("\n");
      logContent
          .append("Timestamp: ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
          .append("\n");
      logContent.append("========================================\n\n");
      logContent.append("Error Message:\n").append(errorMessage).append("\n\n");

      if (exception != null) {
        logContent.append("Exception Type: ").append(exception.getClass().getName()).append("\n\n");
        logContent.append("Stack Trace:\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        logContent.append(sw.toString());
      }

      Files.writeString(errorLogFile, logContent.toString(), StandardCharsets.UTF_8);
      System.err.println("[ERROR] Error log written to: " + errorLogFile.toAbsolutePath());
    } catch (IOException e) {
      System.err.println("[ERROR] Failed to write error log: " + e.getMessage());
    }
  }
}
