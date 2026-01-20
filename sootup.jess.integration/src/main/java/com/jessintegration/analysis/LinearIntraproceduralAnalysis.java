package com.jessintegration.analysis;

import java.util.ArrayList;
import java.util.List;
import sootup.core.jimple.common.stmt.Stmt;

/**
 * Performs linear intraprocedural analysis on Jimple statements. This analysis processes statements
 * sequentially and extracts characteristics that can be used for comparison between different
 * bytecode versions.
 */
public final class LinearIntraproceduralAnalysis {

  /** Result of the linear intraprocedural analysis. */
  public static final class AnalysisResult {
    private final String normalizedSequence;
    private final int statementCount;
    private final int branchCount;
    private final int invokeCount;
    private final int assignCount;
    private final List<String> statementTypes;

    public AnalysisResult(
        String normalizedSequence,
        int statementCount,
        int branchCount,
        int invokeCount,
        int assignCount,
        List<String> statementTypes) {
      this.normalizedSequence = normalizedSequence;
      this.statementCount = statementCount;
      this.branchCount = branchCount;
      this.invokeCount = invokeCount;
      this.assignCount = assignCount;
      this.statementTypes = statementTypes;
    }

    public String getNormalizedSequence() {
      return normalizedSequence;
    }

    public int getStatementCount() {
      return statementCount;
    }

    public int getBranchCount() {
      return branchCount;
    }

    public int getInvokeCount() {
      return invokeCount;
    }

    public int getAssignCount() {
      return assignCount;
    }

    public List<String> getStatementTypes() {
      return statementTypes;
    }

    /**
     * Compares this result with another result.
     *
     * @param other The other analysis result
     * @return true if the results are equivalent
     */
    public boolean isEquivalent(AnalysisResult other) {
      if (other == null) {
        return false;
      }
      // Compare normalized sequences
      return this.normalizedSequence.equals(other.normalizedSequence);
    }

    /**
     * Computes a similarity score between this result and another result.
     *
     * @param other The other analysis result
     * @return A similarity score between 0.0 (completely different) and 1.0 (identical)
     */
    public double computeSimilarity(AnalysisResult other) {
      if (other == null) {
        return 0.0;
      }

      // If sequences are identical, return 1.0
      if (this.normalizedSequence.equals(other.normalizedSequence)) {
        return 1.0;
      }

      // Compute similarity based on structural features
      double similarity = 0.0;
      double weight = 0.0;

      // Statement count similarity
      if (this.statementCount > 0 || other.statementCount > 0) {
        double stmtSim =
            1.0
                - Math.abs(this.statementCount - other.statementCount)
                    / (double) Math.max(this.statementCount, other.statementCount);
        similarity += stmtSim * 0.2;
        weight += 0.2;
      }

      // Branch count similarity
      if (this.branchCount > 0 || other.branchCount > 0) {
        double branchSim =
            1.0
                - Math.abs(this.branchCount - other.branchCount)
                    / (double) Math.max(this.branchCount, other.branchCount);
        similarity += branchSim * 0.2;
        weight += 0.2;
      }

      // Invoke count similarity
      if (this.invokeCount > 0 || other.invokeCount > 0) {
        double invokeSim =
            1.0
                - Math.abs(this.invokeCount - other.invokeCount)
                    / (double) Math.max(this.invokeCount, other.invokeCount);
        similarity += invokeSim * 0.2;
        weight += 0.2;
      }

      // Assign count similarity
      if (this.assignCount > 0 || other.assignCount > 0) {
        double assignSim =
            1.0
                - Math.abs(this.assignCount - other.assignCount)
                    / (double) Math.max(this.assignCount, other.assignCount);
        similarity += assignSim * 0.2;
        weight += 0.2;
      }

      // Statement type sequence similarity (using longest common subsequence)
      double typeSim = computeSequenceSimilarity(this.statementTypes, other.statementTypes);
      similarity += typeSim * 0.2;
      weight += 0.2;

      return weight > 0 ? similarity / weight : 0.0;
    }

    private double computeSequenceSimilarity(List<String> seq1, List<String> seq2) {
      if (seq1.isEmpty() && seq2.isEmpty()) {
        return 1.0;
      }
      if (seq1.isEmpty() || seq2.isEmpty()) {
        return 0.0;
      }

      // Compute longest common subsequence length
      int lcs = longestCommonSubsequence(seq1, seq2);
      int maxLen = Math.max(seq1.size(), seq2.size());
      return (double) lcs / maxLen;
    }

    private int longestCommonSubsequence(List<String> seq1, List<String> seq2) {
      int m = seq1.size();
      int n = seq2.size();
      int[][] dp = new int[m + 1][n + 1];

      for (int i = 1; i <= m; i++) {
        for (int j = 1; j <= n; j++) {
          if (seq1.get(i - 1).equals(seq2.get(j - 1))) {
            dp[i][j] = dp[i - 1][j - 1] + 1;
          } else {
            dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
          }
        }
      }

      return dp[m][n];
    }
  }

  /** Performs linear intraprocedural analysis on Jimple statements. */
  public static AnalysisResult analyze(List<Stmt> statements) {
    if (statements == null || statements.isEmpty()) {
      return new AnalysisResult("", 0, 0, 0, 0, List.of());
    }

    List<String> normalized = new ArrayList<>();
    List<String> statementTypes = new ArrayList<>();
    int branchCount = 0;
    int invokeCount = 0;
    int assignCount = 0;

    try {
      for (Stmt stmt : statements) {
        try {
          // Get statement type
          String stmtType = getStatementType(stmt);
          statementTypes.add(stmtType);

          // Normalize statement to a canonical form
          String normalizedStmt = normalizeStatement(stmt);
          normalized.add(normalizedStmt);

          // Count specific statement types
          if (isBranch(stmt)) {
            branchCount++;
          }
          if (isInvoke(stmt)) {
            invokeCount++;
          }
          if (isAssign(stmt)) {
            assignCount++;
          }
        } catch (Exception e) {
          // If a single statement fails, skip it but continue
          statementTypes.add("ERROR:" + e.getClass().getSimpleName());
          normalized.add("ERROR");
        }
      }

      // Create normalized sequence string
      String normalizedSequence = String.join("|", normalized);

      return new AnalysisResult(
          normalizedSequence,
          statements.size(),
          branchCount,
          invokeCount,
          assignCount,
          statementTypes);
    } catch (Exception e) {
      // If analysis completely fails, throw with more context
      throw new IllegalArgumentException(
          "Analysis failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
  }

  /** Gets the type/category of a statement. */
  private static String getStatementType(Stmt stmt) {
    if (stmt == null) {
      return "UNKNOWN";
    }

    String className = stmt.getClass().getSimpleName();
    // Remove common prefixes/suffixes
    if (className.endsWith("Stmt")) {
      return className.substring(0, className.length() - 4);
    }
    return className;
  }

  /**
   * Normalizes a statement to a canonical form for comparison. This removes variable names and
   * focuses on the structure.
   */
  private static String normalizeStatement(Stmt stmt) {
    if (stmt == null) {
      return "NULL";
    }

    try {
      String stmtStr = stmt.toString();
      // Remove variable names (replace with placeholders)
      // This is a simplified normalization - a more sophisticated approach
      // would parse the statement structure
      stmtStr = stmtStr.replaceAll("r\\d+", "rX");
      stmtStr = stmtStr.replaceAll("\\$stack\\d+", "$stackX");
      stmtStr = stmtStr.replaceAll("@parameter\\d+", "@parameterX");
      stmtStr = stmtStr.replaceAll("@this", "@this");

      // Extract the core operation type
      String type = getStatementType(stmt);
      return type + ":" + stmtStr;
    } catch (Exception e) {
      // If toString() fails, return a safe representation
      return "ERROR:" + getStatementType(stmt) + ":" + e.getClass().getSimpleName();
    }
  }

  /** Checks if a statement is a branch statement. */
  private static boolean isBranch(Stmt stmt) {
    if (stmt == null) {
      return false;
    }
    String className = stmt.getClass().getSimpleName();
    return className.contains("If") || className.contains("Goto") || className.contains("Switch");
  }

  /** Checks if a statement is an invoke statement. */
  private static boolean isInvoke(Stmt stmt) {
    if (stmt == null) {
      return false;
    }
    String className = stmt.getClass().getSimpleName();
    return className.contains("Invoke");
  }

  /** Checks if a statement is an assignment statement. */
  private static boolean isAssign(Stmt stmt) {
    if (stmt == null) {
      return false;
    }
    String className = stmt.getClass().getSimpleName();
    return className.contains("Assign");
  }
}
