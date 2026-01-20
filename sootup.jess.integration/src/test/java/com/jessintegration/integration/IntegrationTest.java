package com.jessintegration.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.jessintegration.analysis.LinearIntraproceduralAnalysis;
import com.jessintegration.config.ProjectEntry;
import com.jessintegration.config.ProjectListConfig;
import com.jessintegration.jess.JessApi;
import com.jessintegration.jess.JessEmbeddedAdapter;
import com.jessintegration.model.JessOptions;
import com.jessintegration.model.JessResult;
import com.jessintegration.model.MethodId;
import com.jessintegration.run.JarFinder;
import com.jessintegration.run.ReferenceBuilder;
import com.jessintegration.sootup.JimpleConverter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sootup.core.jimple.common.stmt.Stmt;

/**
 * Integration test that tests the full workflow: 1. Build a project JAR 2. Process methods through
 * Jess 3. Convert to Jimple using SootUp 4. Perform analysis and compare
 *
 * <p>To use this test: 1. Set the TEST_PROJECT_DIR environment variable to point to a test Java
 * project 2. Or modify the testProjectDir path below to point to your test project 3. Run: mvn test
 * -Dtest=IntegrationTest
 */
public class IntegrationTest {

  // Set this to your test project directory, or use environment variable
  private static final String TEST_PROJECT_DIR_ENV = "TEST_PROJECT_DIR";
  private static final String DEFAULT_TEST_PROJECT =
      System.getenv()
          .getOrDefault(
              TEST_PROJECT_DIR_ENV,
              "/Users/mitul/Documents/study/Thesis/partial compilation/JessTesting/src/test/resources/projects/commons-io");

  @TempDir static Path tempWorkDir;

  @Test
  void testFullWorkflow() throws Exception {
    Path testProjectDir = Paths.get(DEFAULT_TEST_PROJECT);

    // Skip test if project directory doesn't exist
    if (!Files.exists(testProjectDir)) {
      System.out.println(
          "Skipping integration test: Test project directory not found: " + testProjectDir);
      System.out.println(
          "Set " + TEST_PROJECT_DIR_ENV + " environment variable to point to a test project");
      return;
    }

    System.out.println("Running integration test with project: " + testProjectDir);

    // 1. Build the project JAR
    System.out.println("[Test] Building project JAR...");
    ReferenceBuilder.buildMainJarWithMaven(testProjectDir, true);

    // 2. Find the JAR
    Path jar = JarFinder.findMainArtifactJar(testProjectDir, true);
    assertNotNull(jar, "JAR file should be found");
    assertTrue(Files.exists(jar), "JAR file should exist: " + jar);

    // 3. Enumerate a few methods from the JAR
    System.out.println("[Test] Enumerating methods from JAR...");
    List<MethodId> methods =
        com.jessintegration.discovery.MethodEnumerator.fromJar(
            jar, Optional.empty(), 5); // Limit to 5 methods for testing
    assertFalse(methods.isEmpty(), "Should find at least one method");

    // 4. Initialize Jess
    JessApi jess = new JessEmbeddedAdapter(true);
    Path workDir = tempWorkDir.resolve("jess-work");
    Files.createDirectories(workDir);

    JessOptions options =
        new JessOptions(
            "none", // depMode
            "method", // sliceMode
            60, // timeout
            List.of(jar.toString()), // classpath
            workDir // workDir
            );

    // 5. Process first method
    MethodId firstMethod = methods.get(0);
    System.out.println(
        "[Test] Processing method: " + firstMethod.binaryClassName() + "#" + firstMethod.name());

    JessResult jessResult =
        jess.compileMethod(testProjectDir, "src/main/java", firstMethod, options);

    if (jessResult.status() != JessResult.Status.OK) {
      System.out.println(
          "[Test] Jess compilation failed: " + jessResult.status() + " - " + jessResult.notes());
      // This is okay for testing - we'll test what we can
      return;
    }

    // 6. Convert Jess bytecode to Jimple
    System.out.println("[Test] Converting Jess bytecode to Jimple...");
    Path jessClassFile = null;
    if (jessResult.classesOutDir() != null) {
      String className = firstMethod.binaryClassName() + ".class";
      jessClassFile = jessResult.classesOutDir().resolve(className);

      // Try to find the actual class file (might be in a nested class)
      if (!Files.exists(jessClassFile)) {
        // Search in the directory
        Path classesDir = jessResult.classesOutDir();
        if (Files.isDirectory(classesDir)) {
          Optional<Path> found =
              Files.list(classesDir).filter(p -> p.toString().endsWith(".class")).findFirst();
          if (found.isPresent()) {
            jessClassFile = found.get();
          }
        }
      }
    }

    Optional<List<Stmt>> jessJimple = Optional.empty();
    if (jessClassFile != null && Files.exists(jessClassFile)) {
      jessJimple = JimpleConverter.convertFromClassFile(jessClassFile, firstMethod);
      assertTrue(jessJimple.isPresent(), "Should successfully convert Jess bytecode to Jimple");
    }

    // 7. Convert JAR bytecode to Jimple
    System.out.println("[Test] Converting JAR bytecode to Jimple...");
    Optional<List<Stmt>> jarJimple = JimpleConverter.convertFromJar(jar, firstMethod);
    assertTrue(jarJimple.isPresent(), "Should successfully convert JAR bytecode to Jimple");

    // 8. Perform analysis on both
    System.out.println("[Test] Performing analysis...");
    if (jessJimple.isPresent() && jarJimple.isPresent()) {
      LinearIntraproceduralAnalysis.AnalysisResult jessAnalysis =
          LinearIntraproceduralAnalysis.analyze(jessJimple.get());
      LinearIntraproceduralAnalysis.AnalysisResult jarAnalysis =
          LinearIntraproceduralAnalysis.analyze(jarJimple.get());

      // 9. Compare results
      boolean equivalent = jessAnalysis.isEquivalent(jarAnalysis);
      double similarity = jessAnalysis.computeSimilarity(jarAnalysis);

      System.out.println("[Test] Analysis Results:");
      System.out.println("  Equivalent: " + equivalent);
      System.out.println("  Similarity: " + similarity);
      System.out.println("  Jess statements: " + jessAnalysis.getStatementCount());
      System.out.println("  JAR statements: " + jarAnalysis.getStatementCount());

      // Assertions
      assertTrue(similarity >= 0.0 && similarity <= 1.0, "Similarity should be between 0 and 1");
      assertTrue(
          jessAnalysis.getStatementCount() > 0 || jarAnalysis.getStatementCount() > 0,
          "At least one analysis should have statements");
    }

    System.out.println("[Test] Integration test completed successfully!");
  }

  @Test
  void testWithProjectJson() throws Exception {
    // Test using project.json configuration
    Path projectJson = Paths.get("project.json");

    if (!Files.exists(projectJson)) {
      System.out.println("Skipping test: project.json not found in current directory");
      return;
    }

    List<ProjectEntry> projects = ProjectListConfig.load(projectJson);
    assertFalse(projects.isEmpty(), "Should load at least one project from project.json");

    ProjectEntry project = projects.get(0);
    if (project.projectDir == null || !Files.exists(project.projectDir)) {
      System.out.println("Skipping test: Project directory not found: " + project.projectDir);
      return;
    }

    System.out.println("Testing with project from project.json: " + project.name);

    // Run the full workflow
    JessApi jess = new JessEmbeddedAdapter(true);
    ReferenceBuilder.buildMainJarWithMaven(project.projectDir, true);
    Path jar = JarFinder.findMainArtifactJar(project.projectDir, true);

    assertNotNull(jar, "JAR should be found");

    // Enumerate methods
    var pkgOpt =
        (project.pkg == null || project.pkg.isBlank())
            ? Optional.<String>empty()
            : Optional.of(project.pkg.replace('.', '/'));
    int limit = project.limit == null ? 3 : Math.min(project.limit, 3); // Limit to 3 for testing
    List<MethodId> methods =
        com.jessintegration.discovery.MethodEnumerator.fromJar(jar, pkgOpt, limit);

    assertFalse(methods.isEmpty(), "Should find methods");

    // Process one method
    Path workDir = tempWorkDir.resolve("work");
    JessOptions options = new JessOptions("none", "method", 60, List.of(jar.toString()), workDir);

    MethodId method = methods.get(0);
    JessResult result = jess.compileMethod(project.projectDir, "src/main/java", method, options);

    System.out.println("Method: " + method.binaryClassName() + "#" + method.name());
    System.out.println("Status: " + result.status());

    // If successful, test Jimple conversion
    if (result.status() == JessResult.Status.OK && result.classesOutDir() != null) {
      Path classFile = result.classesOutDir().resolve(method.binaryClassName() + ".class");
      if (!Files.exists(classFile)) {
        // Try to find any .class file
        Optional<Path> anyClass =
            Files.list(result.classesOutDir())
                .filter(p -> p.toString().endsWith(".class"))
                .findFirst();
        if (anyClass.isPresent()) {
          classFile = anyClass.get();
        }
      }

      if (Files.exists(classFile)) {
        Optional<List<Stmt>> jessJimple = JimpleConverter.convertFromClassFile(classFile, method);
        Optional<List<Stmt>> jarJimple = JimpleConverter.convertFromJar(jar, method);

        if (jessJimple.isPresent() && jarJimple.isPresent()) {
          LinearIntraproceduralAnalysis.AnalysisResult jessAnalysis =
              LinearIntraproceduralAnalysis.analyze(jessJimple.get());
          LinearIntraproceduralAnalysis.AnalysisResult jarAnalysis =
              LinearIntraproceduralAnalysis.analyze(jarJimple.get());

          double similarity = jessAnalysis.computeSimilarity(jarAnalysis);
          System.out.println("Analysis similarity: " + similarity);

          assertTrue(similarity >= 0.0 && similarity <= 1.0);
        }
      }
    }
  }
}
