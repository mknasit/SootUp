package com.jessintegration.run;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

import com.jessintegration.analysis.ConstantPropagationAnalysis;
import com.jessintegration.analysis.ValueSimilarityComparator;
import com.jessintegration.bytecode.Canonicalizer;
import com.jessintegration.bytecode.Canonicalizer.MethodPresence;
import com.jessintegration.compare.Similarity;
import com.jessintegration.jess.JessApi;
import com.jessintegration.model.JessOptions;
import com.jessintegration.model.JessResult;
import com.jessintegration.model.MethodId;
import com.jessintegration.sootup.JimpleConverter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RepoRunner {

  private static final Locale CSV_LOCALE = Locale.US;

  private final JessApi jess;
  private final boolean strict;
  private final boolean dump; // not used (no dumps)
  private final boolean debug;

  public RepoRunner(JessApi jess, boolean strict, boolean dump) {
    this(jess, strict, dump, false);
  }

  public RepoRunner(JessApi jess, boolean strict, boolean dump, boolean debug) {
    this.jess = jess;
    this.strict = strict;
    this.dump = dump;
    this.debug = debug;
  }

  public void run(
      Path repoRoot,
      Path refJar,
      List<String> sourceRoots,
      List<MethodId> methods,
      JessOptions options,
      Path outDir)
      throws IOException {

    Files.createDirectories(outDir);
    final Path csvOut = outDir.resolve("results.csv");
    final Path summaryOut = outDir.resolve("summary.txt");

    final List<String> csv = new ArrayList<>();
    csv.add(
        String.join(
            ",",
            "binaryClass",
            "name",
            "desc",
            "status",
            "levenshteinDistance",
            "constPropValueSimilarity",
            "jessConstPropSimple",
            "jarConstPropSimple",
            "constPropReason",
            "jessConstPropOutput",
            "jarConstPropOutput",
            "jessJimpleCode",
            "jarJimpleCode",
            "notes",
            "elapsedMs",
            "srcRootTried"));

    long total = 0, ok = 0, comparable = 0, levenshteinEqualCount = 0;
    double levenshteinSum = 0.0;
    long levenshteinCount = 0; // Count of non-equal Levenshtein distances
    long constPropAnalysisPerformed = 0; // Count of cases where constant propagation was performed
    double constPropValueSimilaritySum = 0.0;
    long constPropValueSimilarityCount = 0;

    final RefJarIndex refIndex = new RefJarIndex(refJar);

    for (MethodId m : methods) {
      total++;

      JessResult jr = null;
      String pickedSrc = null;

      for (String src : sourceRoots) {
        if (debug)
          System.out.println("[run] TRY " + m.binaryClassName() + "#" + m.name() + " in " + src);
        jr = jess.compileMethod(repoRoot, src, m, options);
        pickedSrc = src;
        if (debug) {
          System.out.println(
              "[run]  -> status="
                  + jr.status()
                  + " usedStubs="
                  + jr.usedStubs()
                  + " depsResolved="
                  + jr.depsResolved()
                  + " notes="
                  + jr.notes()
                  + " classesOutDir="
                  + jr.classesOutDir()
                  + " emittedClasses="
                  + jr.emittedClasses());
        }
        String notes = jr.notes() == null ? "" : jr.notes();
        if (jr.status() == JessResult.Status.FAILED_PARSE
            && notes.contains("Source file not found")) {
          continue; // try next root
        }
        break; // stop trying roots
      }

      if (jr == null) {
        jr =
            new JessResult(
                JessResult.Status.INTERNAL_ERROR,
                null,
                null,
                List.of(),
                false,
                false,
                0L,
                "No result produced");
      }

      if (jr.status() == JessResult.Status.OK) ok++;

      boolean isComparable = false;
      double levenshteinDistance = 0.0;
      boolean levenshteinEqual = false;
      double constPropValueSimilarity = 0.0; // Used for statistics calculation
      String constPropValueSimilarityStr = ""; // Used for CSV output (empty if not performed)
      String constPropReason = "";
      boolean jessJimpleConverted = false;
      boolean jarJimpleConverted = false;
      // Jimple code strings
      String jessJimpleCode = "";
      String jarJimpleCode = "";
      // Constant propagation outputs (only for unequal Levenshtein)
      String jessConstPropOutput = ""; // Rich format
      String jarConstPropOutput = ""; // Rich format
      String jessConstPropSimple = ""; // Simple format (human-readable)
      String jarConstPropSimple = ""; // Simple format (human-readable)

      // collect reasons while probing; we'll only emit them if not comparable
      StringBuilder reason = new StringBuilder();

      if (jr.status() != JessResult.Status.OK) {
        appendReason(reason, "jessStatus=" + jr.status().name());
      } else {
        // add diagnostic hints (won't be emitted if we succeed)
        if (jr.usedStubs()) appendReason(reason, "jessUsedStubs");
        if (!jr.depsResolved()) appendReason(reason, "jessUnresolvedDeps");

        final String ownerInternal = m.binaryClassName();
        final String ownerRelClass = ownerInternal + ".class";

        // ===== REF (multi-release aware) =====
        boolean refHasClass = refIndex.containsAnyVersion(ownerRelClass);
        MethodPresence refPresence =
            refHasClass
                ? Canonicalizer.methodPresenceInJar(refJar, m)
                : MethodPresence.CLASS_NOT_FOUND;

        if (!refHasClass) {
          appendReason(reason, "refBytecodeMissing");
        } else if (refPresence == MethodPresence.METHOD_NOT_FOUND) {
          appendReason(reason, "refMethodMissing");
        } else if (refPresence == MethodPresence.NO_CODE) {
          appendReason(reason, "refNoCode");
        } else if (refPresence == MethodPresence.ERROR) {
          appendReason(reason, "refReadError");
        } else {
          // ===== JESS side: candidates in same package / family =====
          final Path jessOut = jr.classesOutDir();
          final Path workDir = options.workDir();
          final List<String> emitted =
              jr.emittedClasses() == null ? List.of() : jr.emittedClasses();

          List<Path> candidates = new ArrayList<>();
          if (jessOut != null) {
            // emitted classes
            for (String e : emitted) {
              Path p = jessOut.resolve(e + ".class");
              if (Files.isRegularFile(p)) candidates.add(p);
            }
            // owner
            Path ownerPath = jessOut.resolve(ownerRelClass);
            if (Files.isRegularFile(ownerPath)) candidates.add(ownerPath);
            // family in same package (owner + owner$*)
            candidates.addAll(findOwnerFamilyInSamePackage(jessOut, ownerInternal));
            // lambda carriers: same-package prefix
            if (isLambdaName(m.name())) {
              candidates.addAll(findSamePackagePrefix(jessOut, ownerInternal));
            }
          }
          // last resort: search under workDir too
          if (candidates.isEmpty()
              && jessOut != null
              && workDir != null
              && !jessOut.equals(workDir)) {
            candidates.addAll(findOwnerFamilyInSamePackage(workDir, ownerInternal));
            if (isLambdaName(m.name())) {
              candidates.addAll(findSamePackagePrefix(workDir, ownerInternal));
            }
          }

          // dedup
          LinkedHashSet<Path> unique = new LinkedHashSet<>(candidates);
          candidates = new ArrayList<>(unique);

          if (candidates.isEmpty()) {
            if (jessOut == null) appendReason(reason, "jessNoOutputDir");
            else if (!emitted.isEmpty()) appendReason(reason, "jessEmittedNotFound");
            else appendReason(reason, "jessClassNotInOutDir");
          } else {
            // probe candidates for presence/code
            Path pickedClass = null;
            boolean foundNoCode = false;
            for (Path c : candidates) {
              MethodPresence jp = Canonicalizer.methodPresenceInClassFile(c, m);
              if (jp == MethodPresence.HAS_CODE) {
                pickedClass = c;
                break;
              }
              if (jp == MethodPresence.NO_CODE) {
                foundNoCode = true;
              }
            }

            if (pickedClass == null) {
              if (foundNoCode) appendReason(reason, "jessNoCode");
              else appendReason(reason, "jessMethodMissing");
            } else {
              // ===== Convert to Jimple and Analyze =====
              try {
                // Convert Jess bytecode to Jimple with detailed error information
                JimpleConverter.ConversionResult jessResult;
                if (jessOut != null) {
                  jessResult = JimpleConverter.convertFromClassesDirectoryDetailed(jessOut, m);
                } else {
                  // Fallback: try to convert from class file (less reliable)
                  Path classesDir = pickedClass.getParent();
                  jessResult = JimpleConverter.convertFromClassesDirectoryDetailed(classesDir, m);
                }

                // Convert JAR bytecode to Jimple with detailed error information
                JimpleConverter.ConversionResult jarResult =
                    JimpleConverter.convertFromJarDetailed(refJar, m);

                Optional<java.util.List<sootup.core.jimple.common.stmt.Stmt>> jessJimpleOpt =
                    jessResult.statements();
                Optional<java.util.List<sootup.core.jimple.common.stmt.Stmt>> jarJimpleOpt =
                    jarResult.statements();

                if (jessJimpleOpt.isEmpty()) {
                  appendReason(
                      reason,
                      "jessJimpleConversionFailed:" + jessResult.errorStage().orElse("unknown"));
                  jessJimpleCode = "";
                } else {
                  jessJimpleConverted = true;
                  // Convert Jimple statements to string (print bytecode)
                  jessJimpleCode = JimpleConverter.statementsToString(jessJimpleOpt.get());
                }

                if (jarJimpleOpt.isEmpty()) {
                  appendReason(
                      reason,
                      "jarJimpleConversionFailed:" + jarResult.errorStage().orElse("unknown"));
                  jarJimpleCode = "";
                } else {
                  jarJimpleConverted = true;
                  // Convert Jimple statements to string (print bytecode)
                  jarJimpleCode = JimpleConverter.statementsToString(jarJimpleOpt.get());
                }

                // If both conversions succeeded, perform LevenshteinDistance comparison
                if (jessJimpleOpt.isPresent() && jarJimpleOpt.isPresent()) {
                  try {
                    // Normalize Jimple for comparison (sorts statements, renames locals)
                    String jessNormalized =
                        JimpleConverter.normalizeJimpleForComparison(jessJimpleOpt.get());
                    String jarNormalized =
                        JimpleConverter.normalizeJimpleForComparison(jarJimpleOpt.get());

                    // Perform token-level LevenshteinDistance on normalized Jimple
                    // Token-level is more semantic than character-level
                    double normalizedLevenshtein =
                        Similarity.tokenNld(jessNormalized, jarNormalized);

                    // Check if equal (distance = 0.0 means identical)
                    if (normalizedLevenshtein == 0.0) {
                      levenshteinEqual = true;
                      // For equal cases, set distance to 1.0 in CSV (but don't count in average)
                      levenshteinDistance = 1.0;
                      constPropReason = "levenshteinDistance=1.0;identical_code";
                      constPropValueSimilarityStr = ""; // Not performed - leave empty
                      // If equal, just record it - no analysis needed
                      isComparable = true;
                      comparable++;
                      // Note: levenshteinEqual counter is incremented below
                      // SUCCESS: blank out reason
                      reason.setLength(0);
                    } else {
                      // For unequal cases, use the actual distance
                      levenshteinDistance = normalizedLevenshtein;

                      // Check if method has constant-producing operations
                      boolean jessHasOps =
                          ConstantPropagationAnalysis.hasConstantProducingOperations(
                              jessJimpleOpt.get());
                      boolean jarHasOps =
                          ConstantPropagationAnalysis.hasConstantProducingOperations(
                              jarJimpleOpt.get());

                      if (!jessHasOps && !jarHasOps) {
                        // No arithmetic operations and no constant assignments - skip constant
                        // propagation
                        constPropReason = "no_arithmetic_operations_or_constants";
                        constPropValueSimilarityStr = ""; // Not performed - leave empty
                        // Keep the original distance value, but don't perform analysis
                        isComparable = true;
                        comparable++;
                        // Don't count in average (these cases always give similarity = 1.0)
                        // SUCCESS: blank out reason
                        reason.setLength(0);
                      } else {
                        // Has operations - perform intraprocedural constant propagation analysis
                        constPropAnalysisPerformed++;
                        ConstantPropagationAnalysis.ConstantPropagationResult jessConstProp =
                            ConstantPropagationAnalysis.analyze(jessJimpleOpt.get());
                        ConstantPropagationAnalysis.ConstantPropagationResult jarConstProp =
                            ConstantPropagationAnalysis.analyze(jarJimpleOpt.get());

                        // Record output statements for both (rich format for regex matching)
                        jessConstPropOutput = jessConstProp.getSimplifiedSequence();
                        jarConstPropOutput = jarConstProp.getSimplifiedSequence();
                        // Extract simple format from rich format (for human reading)
                        jessConstPropSimple = extractSimpleFormat(jessConstPropOutput);
                        jarConstPropSimple = extractSimpleFormat(jarConstPropOutput);

                        // Compare similarity of output variable values
                        constPropValueSimilarity =
                            ValueSimilarityComparator.computeValueSimilarity(
                                jessConstProp, jarConstProp);
                        // Format as string for CSV (only when actually performed)
                        // If similarity is -1.0, it means no constants were found - treat same as
                        // skipped
                        if (constPropValueSimilarity < 0.0) {
                          // Analysis ran but found no constants - treat same as if we had skipped
                          constPropReason = "no_constants_found_after_analysis";
                          constPropValueSimilarityStr = ""; // No constants to compare
                          // Don't count in average (don't add to sum/count)
                          // Also don't count as "analysis performed" since no useful results
                          constPropAnalysisPerformed--;
                        } else {
                          constPropValueSimilarityStr =
                              String.format(CSV_LOCALE, "%.6f", constPropValueSimilarity);
                          // Only count in average if we have actual constants to compare
                          constPropValueSimilaritySum += constPropValueSimilarity;
                          constPropValueSimilarityCount++;
                        }

                        isComparable = true;
                        comparable++;
                        levenshteinSum += normalizedLevenshtein;
                        levenshteinCount++;

                        // SUCCESS: blank out reason
                        reason.setLength(0);
                      }
                    }
                  } catch (Throwable t) {
                    if (debug) {
                      System.out.println("[run] Analysis failed: " + t.getMessage());
                      t.printStackTrace(System.out);
                    }
                    appendReason(reason, "analysisFailed:" + t.getClass().getSimpleName());
                  }
                }
              } catch (Throwable t) {
                if (debug) {
                  System.out.println("[run] Jimple conversion failed: " + t.getMessage());
                  t.printStackTrace(System.out);
                }
                appendReason(reason, "jimpleConversionFailed:" + t.getClass().getSimpleName());
              }
            }
          }
        }
      }

      // Update global counters
      if (isComparable && levenshteinEqual) {
        levenshteinEqualCount++;
      }

      // Record all comparable cases (both equal and unequal) in CSV
      if (isComparable) {
        csv.add(
            csvRow(
                m.binaryClassName(),
                m.name(),
                m.jvmDescriptor(),
                jr.status().name(),
                levenshteinDistance,
                constPropValueSimilarityStr, // Use string version (empty if not performed)
                jessConstPropSimple, // Simple format (human-readable)
                jarConstPropSimple, // Simple format (human-readable)
                constPropReason,
                jessConstPropOutput,
                jarConstPropOutput,
                jessJimpleCode,
                jarJimpleCode,
                sanitize(jr.notes()),
                jr.elapsedMs(),
                pickedSrc));
      }
    }

    // Write CSV
    try (BufferedWriter w =
        Files.newBufferedWriter(csvOut, StandardCharsets.UTF_8, CREATE, TRUNCATE_EXISTING)) {
      for (String line : csv) {
        w.write(line);
        w.newLine();
      }
    }

    // Summary
    double okRate = (total == 0) ? 0.0 : (100.0 * ok / total);
    double compRate = (total == 0) ? 0.0 : (100.0 * comparable / total);
    double avgLevenshtein = (levenshteinCount == 0) ? 0.0 : (levenshteinSum / levenshteinCount);
    double avgConstPropValueSimilarity =
        (constPropValueSimilarityCount == 0)
            ? 0.0
            : (constPropValueSimilaritySum / constPropValueSimilarityCount);
    double levenshteinEqualRate =
        (comparable == 0) ? 0.0 : (100.0 * levenshteinEqualCount / comparable);

    // Collect environment and version information for reproducibility
    String javaVersion = System.getProperty("java.version");
    String javaVendor = System.getProperty("java.vendor");
    String javaVmVersion = System.getProperty("java.vm.version");
    String osName = System.getProperty("os.name");
    String osVersion = System.getProperty("os.version");

    // Try to get SootUp version from package
    String sootupVersion = "unknown";
    try {
      Package sootupPackage = sootup.core.model.SootMethod.class.getPackage();
      if (sootupPackage != null && sootupPackage.getImplementationVersion() != null) {
        sootupVersion = sootupPackage.getImplementationVersion();
      }
    } catch (Exception e) {
      // Fallback: try to get from manifest or use default
      sootupVersion = "2.0.0-SNAPSHOT (estimated)";
    }

    // JESS version - try to get from JESS package
    String jessVersion = "unknown";
    try {
      Package jessPackage = de.upb.sse.jess.Jess.class.getPackage();
      if (jessPackage != null && jessPackage.getImplementationVersion() != null) {
        jessVersion = jessPackage.getImplementationVersion();
      }
    } catch (Exception e) {
      jessVersion = "unknown (embedded)";
    }

    List<String> lines =
        List.of(
            "=== Environment & Versions ===",
            "Java Version: " + javaVersion,
            "Java Vendor: " + javaVendor,
            "Java VM Version: " + javaVmVersion,
            "OS: " + osName + " " + osVersion,
            "SootUp Version: " + sootupVersion,
            "JESS Version: " + jessVersion,
            "",
            "=== Overall Statistics ===",
            "totalMethods: " + total,
            "okMethods (Jess compilation): " + ok + " out of " + total + " (" + pct(okRate) + ")",
            "",
            "=== Analysis ===",
            "comparable (both Jimple converted + LevenshteinDistance computed): "
                + comparable
                + " out of "
                + total
                + " ("
                + pct(compRate)
                + ")",
            "levenshteinEqual: " + levenshteinEqualCount + " (" + pct(levenshteinEqualRate) + ")",
            "avgLevenshteinDistance (excluding equal): "
                + String.format(CSV_LOCALE, "%.6f", avgLevenshtein),
            "constPropAnalysisPerformed: " + constPropAnalysisPerformed,
            "avgConstPropValueSimilarity (for unequal methods): "
                + String.format(CSV_LOCALE, "%.6f", avgConstPropValueSimilarity),
            "",
            "=== Files ===",
            "csv: " + csvOut.toAbsolutePath());
    Files.write(summaryOut, lines, StandardCharsets.UTF_8);

    if (debug) {
      System.out.println("[run] wrote " + csvOut);
      System.out.println("[run] wrote " + summaryOut);
    }
  }

  // ---------- helpers ----------

  private static void appendReason(StringBuilder b, String extra) {
    if (extra == null || extra.isBlank()) return;
    if (b.length() == 0) b.append(extra);
    else b.append('|').append(extra);
  }

  private static String csvRow(
      String binaryClass,
      String name,
      String desc,
      String status,
      double levenshteinDistance,
      String constPropValueSimilarity, // Now a string (empty if not performed, value if performed)
      String jessConstPropSimple, // Simple format (human-readable)
      String jarConstPropSimple, // Simple format (human-readable)
      String constPropReason,
      String jessConstPropOutput,
      String jarConstPropOutput,
      String jessJimpleCode,
      String jarJimpleCode,
      String notes,
      long elapsedMs,
      String srcRootTried) {
    // Handle Jimple code - replace newlines with semicolons for CSV compatibility
    String jessJimpleCsv =
        (jessJimpleCode == null || jessJimpleCode.isEmpty())
            ? ""
            : jessJimpleCode.replace("\n", "; ").replace("\r", "");
    String jarJimpleCsv =
        (jarJimpleCode == null || jarJimpleCode.isEmpty())
            ? ""
            : jarJimpleCode.replace("\n", "; ").replace("\r", "");
    // Handle constant propagation outputs
    String jessConstPropCsv =
        (jessConstPropOutput == null || jessConstPropOutput.isEmpty())
            ? ""
            : jessConstPropOutput.replace("\n", "; ").replace("\r", "");
    String jarConstPropCsv =
        (jarConstPropOutput == null || jarConstPropOutput.isEmpty())
            ? ""
            : jarConstPropOutput.replace("\n", "; ").replace("\r", "");

    return String.join(
        ",",
        csvField(binaryClass),
        csvField(name),
        csvField(desc),
        csvField(status),
        String.format(CSV_LOCALE, "%.6f", levenshteinDistance),
        csvField(
            constPropValueSimilarity == null
                ? ""
                : constPropValueSimilarity), // Already formatted or empty
        csvField(
            jessConstPropSimple == null
                ? ""
                : jessConstPropSimple), // Simple format (already uses semicolons)
        csvField(
            jarConstPropSimple == null
                ? ""
                : jarConstPropSimple), // Simple format (already uses semicolons)
        csvField(constPropReason == null ? "" : constPropReason),
        csvField(jessConstPropCsv),
        csvField(jarConstPropCsv),
        csvField(jessJimpleCsv),
        csvField(jarJimpleCsv),
        csvField(notes == null ? "" : notes),
        Long.toString(elapsedMs),
        csvField(srcRootTried == null ? "" : srcRootTried));
  }

  private static String csvField(String s) {
    if (s == null) return "";
    String t = s.replace('"', '\'').replace("\n", " ").replace("\r", " ");
    if (t.contains(",") || t.contains("\"")) return "\"" + t + "\"";
    return t;
  }

  private static String sanitize(String s) {
    return s == null ? "" : s;
  }

  /**
   * Extracts simple format from rich constant propagation output. Converts:
   * JAssign:CONSTANT:var=value:INDEXN|... to: CONSTANT:var=value (one per line) Only includes
   * CONSTANT, PROPAGATED, and FOLDED tags (numeric/boolean constants). Normalizes variable names so
   * constants with same value at same position get same variable name.
   *
   * @param richFormat The rich format string (JAssign:CONSTANT:var=value:INDEXN|...)
   * @return Simple format string (CONSTANT:var=value, one per line, separated by semicolons for
   *     CSV)
   */
  private static String extractSimpleFormat(String richFormat) {
    if (richFormat == null || richFormat.isEmpty()) {
      return "";
    }

    // First pass: extract all constants with their (statementIndex, value) pairs
    List<ConstantInfo> constants = new ArrayList<>();
    String[] parts = richFormat.split("\\|");

    for (String part : parts) {
      part = part.trim();
      if (part.isEmpty()) continue;

      // Pattern: JAssign:CONSTANT:var=value:INDEXN or JAssign:PROPAGATED:var=value:INDEXN or
      // JAssign:FOLDED:var=value:INDEXN
      // Also handle: JReturn:CONSTANT:return=value:INDEXN
      if (part.contains(":CONSTANT:")
          || part.contains(":PROPAGATED:")
          || part.contains(":FOLDED:")) {
        String tag = null;
        if (part.contains(":CONSTANT:")) {
          tag = "CONSTANT:";
        } else if (part.contains(":PROPAGATED:")) {
          tag = "PROPAGATED:";
        } else if (part.contains(":FOLDED:")) {
          tag = "FOLDED:";
        }

        if (tag != null) {
          int tagStart = part.indexOf(tag);
          if (tagStart >= 0) {
            String afterTag = part.substring(tagStart + tag.length());
            // Extract statement index
            int indexPos = afterTag.indexOf(":INDEX");
            int stmtIndex = -1;
            String varValuePart = afterTag;
            if (indexPos >= 0) {
              varValuePart = afterTag.substring(0, indexPos);
              try {
                String indexStr = afterTag.substring(indexPos + 6); // Skip ":INDEX"
                // Extract just the number part
                int endIndex = indexStr.length();
                for (int i = 0; i < indexStr.length(); i++) {
                  if (!Character.isDigit(indexStr.charAt(i))) {
                    endIndex = i;
                    break;
                  }
                }
                stmtIndex = Integer.parseInt(indexStr.substring(0, endIndex));
              } catch (Exception e) {
                // If parsing fails, use -1
              }
            }

            // Extract variable name and value
            // Handle both: var=value and return=value
            int eqPos = varValuePart.indexOf("=");
            if (eqPos > 0) {
              String varName = varValuePart.substring(0, eqPos).trim();
              String value = varValuePart.substring(eqPos + 1).trim();
              // Only add if value is not empty (safety check)
              if (!value.isEmpty()) {
                constants.add(new ConstantInfo(tag, varName, value, stmtIndex));
              }
            } else if (eqPos == 0) {
              // Edge case: =value (shouldn't happen, but handle gracefully)
              String value = varValuePart.substring(1).trim();
              if (!value.isEmpty()) {
                constants.add(new ConstantInfo(tag, "return", value, stmtIndex));
              }
            }
          }
        }
      }
    }

    // Second pass: create normalized variable name mapping
    // Map (statementIndex, value) -> normalized variable name
    Map<String, String> valueToVarName = new HashMap<>();
    int varCounter = 0;
    for (ConstantInfo ci : constants) {
      String valueKey = ci.stmtIndex + ":" + ci.value;
      if (!valueToVarName.containsKey(valueKey)) {
        valueToVarName.put(valueKey, "v" + varCounter++);
      }
    }

    // Third pass: build simple format with normalized variable names
    List<String> simpleLines = new ArrayList<>();
    for (ConstantInfo ci : constants) {
      String valueKey = ci.stmtIndex + ":" + ci.value;
      String normalizedVar = valueToVarName.get(valueKey);
      simpleLines.add(ci.tag + normalizedVar + "=" + ci.value);
    }

    return String.join("; ", simpleLines);
  }

  /** Helper class to store constant information during extraction */
  private static final class ConstantInfo {
    final String tag;
    final String varName;
    final String value;
    final int stmtIndex;

    ConstantInfo(String tag, String varName, String value, int stmtIndex) {
      this.tag = tag;
      this.varName = varName;
      this.value = value;
      this.stmtIndex = stmtIndex;
    }
  }

  private static String pct(double p) {
    return String.format(CSV_LOCALE, "%.2f%%", p);
  }

  /** index of jar entries; also tracks base names for multi-release lookup */
  private static final class RefJarIndex {
    private final Set<String> raw = new HashSet<>();
    private final Set<String> baseNames = new HashSet<>();

    RefJarIndex(Path jarPath) {
      try (ZipFile zip = new ZipFile(jarPath.toFile())) {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
          ZipEntry e = en.nextElement();
          if (e.isDirectory()) continue;
          String name = e.getName();
          raw.add(name);
          if (name.endsWith(".class")) baseNames.add(stripMrPrefix(name));
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read ref jar: " + jarPath, e);
      }
    }

    boolean containsAnyVersion(String classRelPath) {
      return raw.contains(classRelPath) || baseNames.contains(classRelPath);
    }

    private static String stripMrPrefix(String entry) {
      if (entry.startsWith("META-INF/versions/")) {
        int i = entry.indexOf('/', "META-INF/versions/".length());
        if (i != -1 && i + 1 < entry.length()) return entry.substring(i + 1);
      }
      return entry;
    }
  }

  /** return all owner and owner$*.class under the SAME PACKAGE directory as ownerInternalName */
  private static List<Path> findOwnerFamilyInSamePackage(Path root, String ownerInternalName)
      throws IOException {
    if (root == null) return List.of();
    String pkg =
        ownerInternalName.contains("/")
            ? ownerInternalName.substring(0, ownerInternalName.lastIndexOf('/'))
            : "";
    String ownerSimple =
        ownerInternalName.contains("/")
            ? ownerInternalName.substring(ownerInternalName.lastIndexOf('/') + 1)
            : ownerInternalName;
    Path pkgDir = pkg.isEmpty() ? root : root.resolve(pkg);
    if (!Files.isDirectory(pkgDir)) return List.of();
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(pkgDir)) {
      for (Path p : ds) {
        if (Files.isRegularFile(p)) {
          String n = p.getFileName().toString();
          if (n.equals(ownerSimple + ".class") || n.startsWith(ownerSimple + "$")) {
            out.add(p);
          }
        }
      }
    }
    return out;
  }

  /**
   * for lambda$ methods, scan same package for any classes starting with ownerSimple (helps capture
   * lambda carriers)
   */
  private static List<Path> findSamePackagePrefix(Path root, String ownerInternalName)
      throws IOException {
    if (root == null) return List.of();
    String pkg =
        ownerInternalName.contains("/")
            ? ownerInternalName.substring(0, ownerInternalName.lastIndexOf('/'))
            : "";
    String ownerSimple =
        ownerInternalName.contains("/")
            ? ownerInternalName.substring(ownerInternalName.lastIndexOf('/') + 1)
            : ownerInternalName;
    Path pkgDir = pkg.isEmpty() ? root : root.resolve(pkg);
    if (!Files.isDirectory(pkgDir)) return List.of();
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(pkgDir)) {
      for (Path p : ds) {
        if (Files.isRegularFile(p)) {
          String n = p.getFileName().toString();
          if (n.startsWith(ownerSimple) && n.endsWith(".class")) {
            out.add(p);
          }
        }
      }
    }
    return out;
  }

  private static boolean isLambdaName(String name) {
    return name != null && name.startsWith("lambda$");
  }
}
