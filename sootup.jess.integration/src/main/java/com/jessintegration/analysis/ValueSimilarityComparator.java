package com.jessintegration.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sootup.core.jimple.common.constant.BooleanConstant;
import sootup.core.jimple.common.constant.Constant;
import sootup.core.jimple.common.constant.DoubleConstant;
import sootup.core.jimple.common.constant.FloatConstant;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.LongConstant;
import sootup.core.jimple.common.constant.NumericConstant;

/**
 * Compares constant values extracted from constant propagation analysis outputs. Handles cases like
 * a=5 vs 1=5.00 by normalizing numeric values.
 */
public final class ValueSimilarityComparator {

  /** Represents a constant assignment extracted from analysis output. */
  public static final class ConstantAssignment {
    private final String variable;
    private final String valueStr;
    private final Constant constant;
    private final int statementIndex; // For stable key matching

    public ConstantAssignment(
        String variable, String valueStr, Constant constant, int statementIndex) {
      this.variable = variable;
      this.valueStr = valueStr;
      this.constant = constant;
      this.statementIndex = statementIndex;
    }

    public String getVariable() {
      return variable;
    }

    public String getValueStr() {
      return valueStr;
    }

    public Constant getConstant() {
      return constant;
    }

    public int getStatementIndex() {
      return statementIndex;
    }

    /** Returns stable key: (statementIndex, variableName) - for exact matching */
    public String getStableKey() {
      return statementIndex + ":" + variable;
    }

    /** Returns value-based key for matching constants ignoring variable names. */
    public String getValueKey() {
      return statementIndex + ":" + valueStr;
    }

    /** Returns position-based key for matching constants at same position. */
    public String getPositionKey() {
      return String.valueOf(statementIndex);
    }
  }

  /** Extracts constant assignments from a constant propagation simplified sequence. */
  public static List<ConstantAssignment> extractConstantAssignments(String simplifiedSequence) {
    List<ConstantAssignment> assignments = new ArrayList<>();
    if (simplifiedSequence == null || simplifiedSequence.isEmpty()) {
      return assignments;
    }

    // Pattern to match: TYPE:CONSTANT:var=value:INDEXN or TYPE:FOLDED:var=value:INDEXN or
    // TYPE:PROPAGATED:var=value:INDEXN
    // The value part should capture everything after = until :INDEX or end
    Pattern pattern =
        Pattern.compile(
            "([A-Za-z]+):(CONSTANT|FOLDED|PROPAGATED):([^=]+)=(.+?)(?::INDEX(\\d+))?(?:\\||$)");
    String[] parts = simplifiedSequence.split("\\|");

    for (String part : parts) {
      part = part.trim();
      if (part.isEmpty()) continue;

      Matcher matcher = pattern.matcher(part);
      if (matcher.find()) {
        String varName = matcher.group(3).trim();
        String valueStr = matcher.group(4).trim();
        // Extract statement index if present
        int stmtIndex = -1;
        if (matcher.group(5) != null) {
          try {
            stmtIndex = Integer.parseInt(matcher.group(5));
          } catch (NumberFormatException e) {
            // If index parsing fails, use -1 (will use position-based index)
          }
        }

        // Remove any trailing pipe that might have been captured
        if (valueStr.endsWith("|")) {
          valueStr = valueStr.substring(0, valueStr.length() - 1).trim();
        }

        // Try to parse constant value for type-aware comparison
        Constant constant = parseConstantFromString(valueStr);
        assignments.add(new ConstantAssignment(varName, valueStr, constant, stmtIndex));
      }
    }

    return assignments;
  }

  /** Compares constant values from two results, returns similarity score (0.0-1.0) based on matching variable-value pairs. */
  public static double compareConstantValues(
      List<ConstantAssignment> jessAssignments, List<ConstantAssignment> jarAssignments) {
    // If both sides have no constants, return -1.0 to indicate "not applicable"
    // (not 1.0, which would be misleading - there's nothing to compare)
    if (jessAssignments.isEmpty() && jarAssignments.isEmpty()) {
      return -1.0; // Special value: no constants to compare
    }

    // Count total variables on each side
    int totalJess = jessAssignments.size();
    int totalJar = jarAssignments.size();
    int maxTotal = Math.max(totalJess, totalJar);

    // If one side is empty, similarity is 0 (no matches possible)
    if (maxTotal == 0) {
      return 0.0;
    }

    // Build maps using position-based keys: statementIndex -> assignments
    // This matches constants at the same program position, ignoring variable names
    // Then we compare their values using type-aware comparison
    Map<String, List<ConstantAssignment>> jessMap = new HashMap<>();
    for (ConstantAssignment ca : jessAssignments) {
      String key = ca.getPositionKey(); // Just statementIndex
      jessMap.computeIfAbsent(key, k -> new ArrayList<>()).add(ca);
    }

    Map<String, List<ConstantAssignment>> jarMap = new HashMap<>();
    for (ConstantAssignment ca : jarAssignments) {
      String key = ca.getPositionKey(); // Just statementIndex
      jarMap.computeIfAbsent(key, k -> new ArrayList<>()).add(ca);
    }

    // Count matches: compare values at same statement index, ignoring variable names
    int matches = 0;
    Set<String> allPositions = new HashSet<>(jessMap.keySet());
    allPositions.addAll(jarMap.keySet());

    // For each position (statementIndex), check if both sides have matching constants
    for (String positionKey : allPositions) {
      List<ConstantAssignment> jessList = jessMap.getOrDefault(positionKey, new ArrayList<>());
      List<ConstantAssignment> jarList = jarMap.getOrDefault(positionKey, new ArrayList<>());

      if (jessList.isEmpty() || jarList.isEmpty()) {
        // One side has a constant at this position, other doesn't - no match at this position
        continue;
      }

      // Both sides have constants at this statement index
      // Compare values using type-aware comparison (handles 1 vs 1.0, etc.)
      // Count how many variables match at this position
      Set<Integer> matchedJarIndices = new HashSet<>();
      for (ConstantAssignment jessCA : jessList) {
        for (int i = 0; i < jarList.size(); i++) {
          if (matchedJarIndices.contains(i)) {
            continue; // Already matched
          }
          ConstantAssignment jarCA = jarList.get(i);
          if (constantsEqual(jessCA, jarCA)) {
            matches++;
            matchedJarIndices.add(i);
            break; // Match found for this jess assignment
          }
        }
      }
    }

    // Similarity = matching variables / max(total variables on side1, total variables on side2)
    return (double) matches / maxTotal;
  }

  /** Checks if two constant assignments represent the same value using type-aware comparison. */
  private static boolean constantsEqual(ConstantAssignment ca1, ConstantAssignment ca2) {
    // If both have Constant objects, use type-aware comparison
    if (ca1.getConstant() != null && ca2.getConstant() != null) {
      return constantsEqualTyped(ca1.getConstant(), ca2.getConstant());
    }

    // Fallback to string-based comparison with numeric normalization
    return valuesEqual(ca1.getValueStr(), ca2.getValueStr());
  }

  /** Type-aware comparison of SootUp Constant objects, handles different numeric types. */
  private static boolean constantsEqualTyped(Constant c1, Constant c2) {
    // Boolean constants
    if (c1 instanceof BooleanConstant && c2 instanceof BooleanConstant) {
      return c1.equals(c2);
    }

    // Integer constants (int, short, byte)
    if (c1 instanceof IntConstant && c2 instanceof IntConstant) {
      return ((IntConstant) c1).getValue() == ((IntConstant) c2).getValue();
    }

    // Long constants
    if (c1 instanceof LongConstant && c2 instanceof LongConstant) {
      return ((LongConstant) c1).getValue() == ((LongConstant) c2).getValue();
    }

    // Float constants
    if (c1 instanceof FloatConstant && c2 instanceof FloatConstant) {
      float f1 = ((FloatConstant) c1).getValue();
      float f2 = ((FloatConstant) c2).getValue();
      return Math.abs(f1 - f2) < 1e-6f;
    }

    // Double constants
    if (c1 instanceof DoubleConstant && c2 instanceof DoubleConstant) {
      double d1 = ((DoubleConstant) c1).getValue();
      double d2 = ((DoubleConstant) c2).getValue();
      return Math.abs(d1 - d2) < 1e-10;
    }

    // Numeric constants (cross-type comparison: int vs long, float vs double, etc.)
    if (c1 instanceof NumericConstant && c2 instanceof NumericConstant) {
      // Try to compare as doubles (widest numeric type)
      try {
        double d1 = parseNumericValue(c1);
        double d2 = parseNumericValue(c2);
        return Math.abs(d1 - d2) < 1e-10;
      } catch (Exception e) {
        // Fall back to string comparison
      }
    }

    // For other types or mixed types, use string comparison
    return c1.toString().equals(c2.toString());
  }

  /** Parses a numeric constant to double value. */
  private static double parseNumericValue(Constant c) {
    if (c instanceof IntConstant) {
      return ((IntConstant) c).getValue();
    } else if (c instanceof LongConstant) {
      return ((LongConstant) c).getValue();
    } else if (c instanceof FloatConstant) {
      return ((FloatConstant) c).getValue();
    } else if (c instanceof DoubleConstant) {
      return ((DoubleConstant) c).getValue();
    }
    throw new IllegalArgumentException("Not a numeric constant: " + c);
  }

  /** Parses a constant from string representation. Tries to create appropriate Constant type. */
  private static Constant parseConstantFromString(String valueStr) {
    if (valueStr == null || valueStr.isEmpty()) {
      return null;
    }

    valueStr = valueStr.trim();

    // Try boolean
    if ("true".equalsIgnoreCase(valueStr) || "1".equals(valueStr)) {
      return BooleanConstant.getTrue();
    }
    if ("false".equalsIgnoreCase(valueStr) || "0".equals(valueStr)) {
      return BooleanConstant.getFalse();
    }

    // Try integer
    try {
      int intVal = Integer.parseInt(valueStr);
      return IntConstant.getInstance(intVal);
    } catch (NumberFormatException e) {
      // Not an int
    }

    // Try long
    try {
      if (valueStr.endsWith("L") || valueStr.endsWith("l")) {
        long longVal = Long.parseLong(valueStr.substring(0, valueStr.length() - 1));
        return LongConstant.getInstance(longVal);
      }
    } catch (NumberFormatException e) {
      // Not a long
    }

    // Try float
    try {
      if (valueStr.endsWith("F") || valueStr.endsWith("f")) {
        float floatVal = Float.parseFloat(valueStr.substring(0, valueStr.length() - 1));
        return FloatConstant.getInstance(floatVal);
      }
    } catch (NumberFormatException e) {
      // Not a float
    }

    // Try double
    try {
      double doubleVal = Double.parseDouble(valueStr);
      return DoubleConstant.getInstance(doubleVal);
    } catch (NumberFormatException e) {
      // Not numeric
    }

    // Could not parse as constant - return null (will use string comparison)
    return null;
  }

  /** Checks if two value strings represent the same constant, handles numeric normalization. */
  private static boolean valuesEqual(String value1, String value2) {
    if (value1 == null || value2 == null) {
      return value1 == null && value2 == null;
    }
    if (value1.equals(value2)) {
      return true;
    }

    // Try numeric comparison
    try {
      double d1 = Double.parseDouble(value1.trim());
      double d2 = Double.parseDouble(value2.trim());
      // Use epsilon for floating point comparison
      return Math.abs(d1 - d2) < 1e-10;
    } catch (NumberFormatException e) {
      // Not numeric, do exact string comparison
      return value1.equals(value2);
    }
  }

  /** Computes similarity between two constant propagation results by comparing constant values. */
  public static double computeValueSimilarity(
      ConstantPropagationAnalysis.ConstantPropagationResult jessResult,
      ConstantPropagationAnalysis.ConstantPropagationResult jarResult) {
    if (jessResult == null || jarResult == null) {
      return 0.0;
    }

    List<ConstantAssignment> jessAssignments =
        extractConstantAssignments(jessResult.getSimplifiedSequence());
    List<ConstantAssignment> jarAssignments =
        extractConstantAssignments(jarResult.getSimplifiedSequence());

    return compareConstantValues(jessAssignments, jarAssignments);
  }
}
