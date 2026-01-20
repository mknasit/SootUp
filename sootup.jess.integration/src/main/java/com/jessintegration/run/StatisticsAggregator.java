package com.jessintegration.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates statistics from multiple project result directories and generates a combined JSON
 * report.
 */
public final class StatisticsAggregator {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /**
   * Aggregates statistics from all project result directories and writes a combined JSON file.
   *
   * @param resultBaseDir Base directory containing project result subdirectories
   * @param outputJsonPath Path where the combined JSON should be written
   * @throws IOException if reading or writing fails
   */
  public static void aggregateAndWrite(Path resultBaseDir, Path outputJsonPath) throws IOException {
    CombinedStatistics combined = aggregate(resultBaseDir);
    writeJson(combined, outputJsonPath);
  }

  /**
   * Aggregates statistics from all project result directories.
   *
   * @param resultBaseDir Base directory containing project result subdirectories
   * @return Combined statistics across all projects
   * @throws IOException if reading fails
   */
  public static CombinedStatistics aggregate(Path resultBaseDir) throws IOException {
    CombinedStatistics stats = new CombinedStatistics();
    long totalTimeMs = 0;

    // Find all project result directories
    if (!Files.exists(resultBaseDir) || !Files.isDirectory(resultBaseDir)) {
      return stats; // Return empty stats if directory doesn't exist
    }

    Files.list(resultBaseDir)
        .filter(Files::isDirectory)
        .forEach(
            projectDir -> {
              Path csvFile = projectDir.resolve("results.csv");
              Path summaryFile = projectDir.resolve("summary.txt");
              if (Files.exists(csvFile)) {
                try {
                  // Parse totalMethods from summary.txt (actual total), not from CSV (only
                  // comparable)
                  long totalMethodsFromSummary = parseTotalMethodsFromSummary(summaryFile);
                  ProjectStatistics projStats = parseProjectCsv(csvFile, totalMethodsFromSummary);
                  aggregateProjectStats(stats, projStats);
                } catch (IOException e) {
                  System.err.println(
                      "Warning: Failed to parse CSV for project "
                          + projectDir.getFileName()
                          + ": "
                          + e.getMessage());
                }
              }
            });

    // Calculate rates and averages
    stats.okRate_micro =
        (stats.totalMethods > 0) ? (double) stats.okMethods / stats.totalMethods : 0.0;

    // Set levenshteinEqual from equivalent (which tracks equal cases)
    stats.levenshteinEqual = stats.equivalent;

    // Calculate Levenshtein statistics
    stats.levenshteinEqualRate_micro =
        (stats.comparable > 0) ? (double) stats.levenshteinEqual / stats.comparable : null;
    stats.levenshteinUnequal = stats.comparable - stats.levenshteinEqual;

    // Average Levenshtein distance (excluding equal ones)
    stats.avgLevenshteinDistance_micro =
        (stats.similarityCount > 0) ? stats.similaritySum / stats.similarityCount : null;

    // Calculate constant propagation value similarity average (for unequal methods)
    stats.avgConstPropValueSimilarity_micro =
        (stats.constPropComparable > 0 && stats.constPropSimilaritySum > 0)
            ? stats.constPropSimilaritySum / stats.constPropSimilarityCount
            : null;

    // Build similarity histogram
    buildSimilarityHistogram(stats);

    return stats;
  }

  private static void aggregateProjectStats(CombinedStatistics combined, ProjectStatistics proj) {
    combined.totalMethods += proj.totalMethods;
    combined.okMethods += proj.okMethods;
    combined.comparable += proj.comparable;
    combined.equivalent += proj.equivalent; // This will be converted to levenshteinEqual later
    combined.similaritySum += proj.similaritySum;
    combined.similarityCount += proj.similarityCount;
    combined.similarityValues.addAll(proj.similarityValues);
    combined.constPropAnalysisPerformed += proj.constPropAnalysisPerformed;
    combined.constPropComparable += proj.constPropComparable;
    combined.constPropSimilaritySum += proj.constPropSimilaritySum;
    combined.constPropSimilarityCount += proj.constPropSimilarityCount;
  }

  /** Parses totalMethods from summary.txt file. Format: "totalMethods: 424" */
  private static long parseTotalMethodsFromSummary(Path summaryFile) throws IOException {
    if (!Files.exists(summaryFile)) {
      return 0; // If summary doesn't exist, we can't get total
    }

    List<String> lines = Files.readAllLines(summaryFile, StandardCharsets.UTF_8);
    for (String line : lines) {
      if (line.startsWith("totalMethods:")) {
        try {
          String value = line.substring("totalMethods:".length()).trim();
          return Long.parseLong(value);
        } catch (NumberFormatException e) {
          // If parsing fails, return 0
          return 0;
        }
      }
    }
    return 0; // Not found
  }

  private static ProjectStatistics parseProjectCsv(Path csvFile, long totalMethods)
      throws IOException {
    ProjectStatistics stats = new ProjectStatistics();
    stats.totalMethods = totalMethods; // Use the actual total from summary.txt, not CSV row count
    List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
    if (lines.isEmpty()) return stats;

    // Skip header
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.trim().isEmpty()) continue;

      String[] fields = line.split(",", -1);
      if (fields.length < 7) continue; // Need at least constPropReason column

      // Don't increment totalMethods here - it's already set from summary.txt
      // The CSV only contains comparable methods, not all methods

      // Column indices (from RepoRunner CSV header - all comparable cases are recorded):
      // 3: status, 4: levenshteinDistance, 5: constPropValueSimilarity, 6: constPropReason
      // 7: jessConstPropOutput, 8: jarConstPropOutput

      String status = fields.length > 3 ? fields[3] : "";
      String levenshteinDistanceStr = fields.length > 4 ? fields[4] : "";
      String constPropValueSimilarityStr = fields.length > 5 ? fields[5] : "";

      if ("OK".equals(status)) {
        stats.okMethods++;
      }

      // All rows in CSV are comparable (both equal and unequal cases)
      stats.comparable++;

      // Track Levenshtein distance
      try {
        if (!levenshteinDistanceStr.isEmpty()) {
          double levenshteinDistance = Double.parseDouble(levenshteinDistanceStr);
          if (!Double.isNaN(levenshteinDistance) && !Double.isInfinite(levenshteinDistance)) {
            stats.similarityValues.add(levenshteinDistance);
            if (levenshteinDistance == 1.0) {
              // Equal cases are marked as 1.0 in CSV but not counted in average
              stats.equivalent++; // Count equal cases
            } else {
              // Only count non-equal (actual distance > 0.0) for average calculation
              stats.similaritySum += levenshteinDistance;
              stats.similarityCount++;
            }
          }
        }
      } catch (NumberFormatException e) {
        // Ignore invalid Levenshtein distance values
      }

      // Constant propagation statistics
      // Check constPropReason column (index 6) to see if analysis was performed
      String constPropReasonStr = fields.length > 6 ? fields[6] : "";

      // If constPropReason is empty, it means constant propagation was performed
      // (if it has a reason like "no_arithmetic_operations" or "identical_code", it was skipped)
      if (constPropReasonStr.isEmpty() || constPropReasonStr.trim().isEmpty()) {
        // Constant propagation was performed
        stats.constPropAnalysisPerformed++;

        // Track value similarity (only for cases where analysis was performed)
        try {
          if (!levenshteinDistanceStr.isEmpty()) {
            double levenshteinDistance = Double.parseDouble(levenshteinDistanceStr);
            // Only process constant propagation for unequal cases (not 1.0 which marks equal cases)
            if (levenshteinDistance != 1.0
                && levenshteinDistance > 0.0
                && !constPropValueSimilarityStr.isEmpty()) {
              double constPropValueSimilarity = Double.parseDouble(constPropValueSimilarityStr);
              if (!Double.isNaN(constPropValueSimilarity)
                  && !Double.isInfinite(constPropValueSimilarity)) {
                stats.constPropComparable++;
                stats.constPropSimilaritySum += constPropValueSimilarity;
                stats.constPropSimilarityCount++;
              }
            }
          }
        } catch (NumberFormatException e) {
          // Ignore invalid constant propagation similarity values
        }
      }
    }

    return stats;
  }

  private static void buildSimilarityHistogram(CombinedStatistics stats) {
    double[] bins = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
    int[] counts = new int[bins.length - 1]; // 10 bins

    for (double similarity : stats.similarityValues) {
      boolean placed = false;
      for (int i = 0; i < bins.length - 1; i++) {
        // For the last bin [0.9, 1.0], include both endpoints
        if (i == bins.length - 2) {
          if (similarity >= bins[i] && similarity <= bins[i + 1]) {
            counts[i]++;
            placed = true;
            break;
          }
        } else {
          if (similarity >= bins[i] && similarity < bins[i + 1]) {
            counts[i]++;
            placed = true;
            break;
          }
        }
      }
      if (!placed && similarity == 1.0) {
        // Fallback: put exactly 1.0 in the last bin
        counts[counts.length - 1]++;
      }
    }

    stats.similarityHistogram = new SimilarityHistogram();
    stats.similarityHistogram.bins = bins;
    stats.similarityHistogram.counts = counts;
  }

  private static void writeJson(CombinedStatistics stats, Path outputPath) throws IOException {
    Files.createDirectories(outputPath.getParent());
    MAPPER.writeValue(outputPath.toFile(), stats);
  }

  // Inner classes for JSON structure
  public static class CombinedStatistics {
    public long totalMethods = 0;
    public long okMethods = 0; // Compiled by JESS
    public double okRate_micro = 0.0;
    public long comparable = 0; // Byte code found method (both Jimple converted)
    public long levenshteinEqual = 0; // Cases with equal LevenshteinDistance
    public Double levenshteinEqualRate_micro = null; // Rate of equal cases
    public long levenshteinUnequal = 0; // Cases with unequal LevenshteinDistance
    public Double avgLevenshteinDistance_micro = null; // Average for unequal methods
    public long constPropAnalysisPerformed =
        0; // Number of cases where constant propagation analysis was performed
    public long constPropComparable = 0; // Unequal cases with constant propagation results
    public Double avgConstPropValueSimilarity_micro =
        null; // Average similarity after constant propagation
    public double timeSec = 0.0;
    public SimilarityHistogram similarityHistogram = new SimilarityHistogram();

    // Internal tracking (not serialized to JSON)
    @com.fasterxml.jackson.annotation.JsonIgnore
    public transient long equivalent = 0; // Internal: tracks levenshteinEqual

    @com.fasterxml.jackson.annotation.JsonIgnore public transient double similaritySum = 0.0;
    @com.fasterxml.jackson.annotation.JsonIgnore public transient long similarityCount = 0;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public transient List<Double> similarityValues = new ArrayList<>();

    @com.fasterxml.jackson.annotation.JsonIgnore
    public transient double constPropSimilaritySum = 0.0;

    @com.fasterxml.jackson.annotation.JsonIgnore public transient long constPropSimilarityCount = 0;
  }

  public static class SimilarityHistogram {
    public double[] bins = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
    public int[] counts = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  }

  private static class ProjectStatistics {
    long totalMethods = 0;
    long okMethods = 0;
    long comparable = 0;
    long equivalent = 0; // Tracks levenshteinEqual cases
    double similaritySum = 0.0;
    long similarityCount = 0;
    List<Double> similarityValues = new ArrayList<>();
    long constPropAnalysisPerformed = 0;
    long constPropComparable = 0;
    double constPropSimilaritySum = 0.0;
    long constPropSimilarityCount = 0;
  }
}
