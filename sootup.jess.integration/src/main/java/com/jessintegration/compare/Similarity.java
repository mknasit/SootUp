package com.jessintegration.compare;

import java.util.Arrays;
import org.apache.commons.text.similarity.LevenshteinDistance;

public final class Similarity {
  private static final LevenshteinDistance LD = LevenshteinDistance.getDefaultInstance();

  /** Computes normalized Levenshtein distance (0.0=identical, 1.0=completely different). */
  public static double nld(String a, String b) {
    if (a.equals(b)) return 0.0;
    int d = LD.apply(a, b);
    int denom = Math.max(a.length(), b.length());
    return denom == 0 ? 0.0 : (double) d / (double) denom;
  }

  /** Computes normalized Levenshtein distance at token level for Jimple statements. */
  public static double tokenNld(String a, String b) {
    if (a == null || b == null) {
      return (a == null && b == null) ? 0.0 : 1.0;
    }

    // Split into tokens (statements) - handle both semicolon and newline separators
    String[] tokensA = splitIntoTokens(a);
    String[] tokensB = splitIntoTokens(b);

    if (tokensA.length == 0 && tokensB.length == 0) {
      return 0.0;
    }
    if (tokensA.length == 0 || tokensB.length == 0) {
      return 1.0;
    }

    // Compute Levenshtein distance on token arrays
    int distance = computeTokenLevenshtein(tokensA, tokensB);

    // Normalize by maximum token count
    int maxLen = Math.max(tokensA.length, tokensB.length);
    return (double) distance / maxLen;
  }

  /** Splits Jimple code into tokens, handles semicolon and newline separators. */
  private static String[] splitIntoTokens(String jimple) {
    if (jimple == null || jimple.trim().isEmpty()) {
      return new String[0];
    }

    // Try semicolon first (normalized format)
    if (jimple.contains(";")) {
      return Arrays.stream(jimple.split(";"))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .toArray(String[]::new);
    }

    // Fallback to newline (original format)
    return Arrays.stream(jimple.split("\n"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
  }

  /** Computes Levenshtein distance between two token arrays using dynamic programming. */
  private static int computeTokenLevenshtein(String[] tokensA, String[] tokensB) {
    int m = tokensA.length;
    int n = tokensB.length;

    // Create DP table
    int[][] dp = new int[m + 1][n + 1];

    // Initialize base cases
    for (int i = 0; i <= m; i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= n; j++) {
      dp[0][j] = j;
    }

    // Fill DP table
    for (int i = 1; i <= m; i++) {
      for (int j = 1; j <= n; j++) {
        if (tokensA[i - 1].equals(tokensB[j - 1])) {
          // Tokens match - no cost
          dp[i][j] = dp[i - 1][j - 1];
        } else {
          // Tokens differ - take minimum of insert, delete, or substitute
          dp[i][j] =
              1
                  + Math.min(
                      Math.min(dp[i - 1][j], dp[i][j - 1]), // insert or delete
                      dp[i - 1][j - 1] // substitute
                      );
        }
      }
    }

    return dp[m][n];
  }
}
