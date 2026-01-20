package com.jessintegration.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.constant.Constant;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.expr.AbstractBinopExpr;
import sootup.core.jimple.common.expr.AbstractConditionExpr;
import sootup.core.jimple.common.expr.JAddExpr;
import sootup.core.jimple.common.expr.JAndExpr;
import sootup.core.jimple.common.expr.JDivExpr;
import sootup.core.jimple.common.expr.JMulExpr;
import sootup.core.jimple.common.expr.JOrExpr;
import sootup.core.jimple.common.expr.JShlExpr;
import sootup.core.jimple.common.expr.JShrExpr;
import sootup.core.jimple.common.expr.JSubExpr;
import sootup.core.jimple.common.expr.JUshrExpr;
import sootup.core.jimple.common.expr.JXorExpr;
import sootup.core.jimple.common.ref.IdentityRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.JReturnVoidStmt;
import sootup.core.jimple.common.stmt.Stmt;

/**
 * Performs constant propagation analysis on Jimple statements using a lattice-based approach. Uses
 * ConstVal lattice: CONST(int value) | TOP | BOTTOM
 */
public final class ConstantPropagationAnalysis {

  /** Lattice value for constant propagation: CONST(c) | TOP | BOTTOM */
  private static final class ConstVal {
    private final Kind kind;
    private final int value; // Only valid when kind == CONST

    private ConstVal(Kind kind, int value) {
      this.kind = kind;
      this.value = value;
    }

    enum Kind {
      CONST, // Definitely that constant
      TOP, // Reachable but not a single constant (multiple possible values or unknown)
      BOTTOM // Unreachable / no information yet
    }

    static ConstVal constInt(int c) {
      return new ConstVal(Kind.CONST, c);
    }

    static final ConstVal TOP = new ConstVal(Kind.TOP, 0);
    static final ConstVal BOTTOM = new ConstVal(Kind.BOTTOM, 0);

    boolean isConst() {
      return kind == Kind.CONST;
    }

    boolean isTop() {
      return kind == Kind.TOP;
    }

    boolean isBottom() {
      return kind == Kind.BOTTOM;
    }

    int getInt() {
      if (kind != Kind.CONST) {
        throw new IllegalStateException("Cannot get int value from non-const: " + kind);
      }
      return value;
    }

    /** Joins two lattice values. */
    ConstVal join(ConstVal other) {
      // join(BOTTOM, x) = x
      if (this.isBottom()) {
        return other;
      }
      // join(x, BOTTOM) = x
      if (other.isBottom()) {
        return this;
      }
      // join(CONST(c1), CONST(c2)) = CONST(c1) if c1 == c2, else TOP
      if (this.isConst() && other.isConst()) {
        if (this.value == other.value) {
          return this; // or other, they're equal
        } else {
          return TOP; // Different constants -> TOP
        }
      }
      // join(TOP, anything) = TOP
      // join(anything, TOP) = TOP
      return TOP;
    }

    @Override
    public String toString() {
      switch (kind) {
        case CONST:
          return "CONST(" + value + ")";
        case TOP:
          return "TOP";
        case BOTTOM:
          return "BOTTOM";
        default:
          return "UNKNOWN";
      }
    }
  }

  /** Result of constant propagation analysis. */
  public static final class ConstantPropagationResult {
    private final int constantsPropagated;
    private final int expressionsFolded;
    private final int deadBranchesRemoved;
    private final int simplifiedStatementCount;
    private final String simplifiedSequence;
    private final List<String> simplifiedStatementTypes;

    public ConstantPropagationResult(
        int constantsPropagated,
        int expressionsFolded,
        int deadBranchesRemoved,
        int simplifiedStatementCount,
        String simplifiedSequence,
        List<String> simplifiedStatementTypes) {
      this.constantsPropagated = constantsPropagated;
      this.expressionsFolded = expressionsFolded;
      this.deadBranchesRemoved = deadBranchesRemoved;
      this.simplifiedStatementCount = simplifiedStatementCount;
      this.simplifiedSequence = simplifiedSequence;
      this.simplifiedStatementTypes = simplifiedStatementTypes;
    }

    public int getConstantsPropagated() {
      return constantsPropagated;
    }

    public int getExpressionsFolded() {
      return expressionsFolded;
    }

    public int getDeadBranchesRemoved() {
      return deadBranchesRemoved;
    }

    public int getSimplifiedStatementCount() {
      return simplifiedStatementCount;
    }

    public String getSimplifiedSequence() {
      return simplifiedSequence;
    }

    public List<String> getSimplifiedStatementTypes() {
      return simplifiedStatementTypes;
    }

    /** Checks if this result is equivalent to another result. */
    public boolean isEquivalent(ConstantPropagationResult other) {
      if (other == null) {
        return false;
      }
      return this.simplifiedSequence.equals(other.simplifiedSequence);
    }

    /** Computes similarity score (0.0-1.0) between this and another result. */
    public double computeSimilarity(ConstantPropagationResult other) {
      if (other == null) {
        return 0.0;
      }

      if (this.simplifiedSequence.equals(other.simplifiedSequence)) {
        return 1.0;
      }

      // Compare simplified statement counts
      if (this.simplifiedStatementCount == 0 && other.simplifiedStatementCount == 0) {
        return 1.0;
      }

      double countSim =
          1.0
              - Math.abs(this.simplifiedStatementCount - other.simplifiedStatementCount)
                  / (double)
                      Math.max(this.simplifiedStatementCount, other.simplifiedStatementCount);

      // Compare simplified sequences using LCS
      double seqSim =
          computeSequenceSimilarity(this.simplifiedStatementTypes, other.simplifiedStatementTypes);

      return (countSim + seqSim) / 2.0;
    }

    private double computeSequenceSimilarity(List<String> seq1, List<String> seq2) {
      if (seq1.isEmpty() && seq2.isEmpty()) {
        return 1.0;
      }
      if (seq1.isEmpty() || seq2.isEmpty()) {
        return 0.0;
      }

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

  /** Checks if statements contain constant-producing operations that can be optimized. */
  public static boolean hasConstantProducingOperations(List<Stmt> statements) {
    if (statements == null || statements.isEmpty()) {
      return false;
    }

    for (Stmt stmt : statements) {
      if (stmt instanceof JAssignStmt) {
        JAssignStmt assign = (JAssignStmt) stmt;
        Value rightOp = assign.getRightOp();

        // Check if assigning a constant to a variable (for propagation)
        // This is a literal/constant that can be propagated
        if (rightOp instanceof Constant) {
          return true;
        }

        // Check if right side is a binary operation with at least one constant operand
        // (arithmetic/bitwise operations that can actually be folded)
        if (rightOp instanceof AbstractBinopExpr) {
          AbstractBinopExpr binop = (AbstractBinopExpr) rightOp;
          Value op1 = binop.getOp1();
          Value op2 = binop.getOp2();

          // Only return true if it's an arithmetic/bitwise operation AND at least one operand is a
          // constant
          // This ensures the operation can actually be evaluated to a constant
          if ((op1 instanceof Constant || op2 instanceof Constant)
              && (rightOp instanceof JAddExpr
                  || rightOp instanceof JSubExpr
                  || rightOp instanceof JMulExpr
                  || rightOp instanceof JDivExpr
                  || rightOp instanceof JShlExpr
                  || rightOp instanceof JShrExpr
                  || rightOp instanceof JUshrExpr
                  || rightOp instanceof JXorExpr
                  || rightOp instanceof JAndExpr
                  || rightOp instanceof JOrExpr)) {
            return true;
          }
        }

        // Check for return statements with constants
        if (stmt instanceof JReturnStmt) {
          JReturnStmt ret = (JReturnStmt) stmt;
          if (ret.getOp() instanceof Constant) {
            return true;
          }
        }
      } else if (stmt instanceof JReturnStmt) {
        JReturnStmt ret = (JReturnStmt) stmt;
        if (ret.getOp() instanceof Constant) {
          return true;
        }
      }
    }

    return false;
  }

  /** Performs constant propagation analysis on Jimple statements using a lattice-based approach. */
  public static ConstantPropagationResult analyze(List<Stmt> statements) {
    if (statements == null || statements.isEmpty()) {
      return new ConstantPropagationResult(0, 0, 0, 0, "", List.of());
    }

    // Environment: Map<Local, ConstVal> - tracks constant values at each program point
    Map<Local, ConstVal> env = new HashMap<>();
    int constantsPropagated = 0;
    int expressionsFolded = 0;
    int deadBranchesRemoved = 0;

    List<String> simplified = new ArrayList<>();
    List<String> simplifiedTypes = new ArrayList<>();

    int statementIndex = 0;
    for (Stmt stmt : statements) {
      try {
        String stmtType = getStatementType(stmt);
        String simplifiedStmt = processStatement(stmt, env, stmtType, statementIndex);

        // Check if this was a constant propagation or folding
        if (simplifiedStmt != null && !simplifiedStmt.equals(normalizeStatement(stmt))) {
          if (simplifiedStmt.contains("CONSTANT:")) {
            constantsPropagated++;
          } else if (simplifiedStmt.contains("PROPAGATED:")) {
            constantsPropagated++;
          } else if (simplifiedStmt.contains("FOLDED:")) {
            expressionsFolded++;
          }
          if (simplifiedStmt.contains("DEAD_BRANCH:")) {
            deadBranchesRemoved++;
            // Skip dead branches in output
            continue;
          }
        }

        if (simplifiedStmt != null) {
          simplified.add(simplifiedStmt);
          simplifiedTypes.add(stmtType);
        } else {
          // Keep original if we can't simplify
          String normalized = normalizeStatement(stmt);
          simplified.add(normalized);
          simplifiedTypes.add(stmtType);
        }
      } catch (Exception e) {
        // Never skip statements - always output something
        // If processing fails, output the original statement
        try {
          String normalized = normalizeStatement(stmt);
          simplified.add(normalized);
          simplifiedTypes.add(getStatementType(stmt));
        } catch (Exception e2) {
          // Last resort: output a basic representation
          String type = getStatementType(stmt);
          simplified.add(type + ":" + stmt.getClass().getSimpleName());
          simplifiedTypes.add(type);
        }
      }
      statementIndex++;
    }

    String simplifiedSequence = String.join("|", simplified);

    return new ConstantPropagationResult(
        constantsPropagated,
        expressionsFolded,
        deadBranchesRemoved,
        simplified.size(),
        simplifiedSequence,
        simplifiedTypes);
  }

  /** Processes a statement with constant propagation, returns simplified string or null if unchanged. */
  private static String processStatement(
      Stmt stmt, Map<Local, ConstVal> env, String stmtType, int statementIndex) {
    try {
      if (stmt instanceof JAssignStmt) {
        JAssignStmt assign = (JAssignStmt) stmt;
        Value leftOp = assign.getLeftOp();
        Value rightOp = assign.getRightOp();

        // Evaluate RHS into ConstVal lattice (never throws, returns TOP for unsupported)
        ConstVal rhsVal = eval(rightOp, env);

        // Handle Local assignments (track in environment)
        if (leftOp instanceof Local) {
          Local leftLocal = (Local) leftOp;

          // Always update environment: env.put(lhs, rhsVal)
          // For unsupported RHS, rhsVal will be TOP
          env.put(leftLocal, rhsVal);

          // If rhsVal is CONST(c), rewrite: lhs = c
          if (rhsVal.isConst()) {
            try {
              int constValue = rhsVal.getInt();
              IntConstant constConst = IntConstant.getInstance(constValue);

              // Determine tag based on RHS type
              String tag;
              if (rightOp instanceof Constant) {
                // Direct constant assignment
                tag = "CONSTANT:";
              } else if (rightOp instanceof Local) {
                // Propagation from another variable
                tag = "PROPAGATED:";
              } else {
                // Folded expression (arithmetic/bitwise operation)
                tag = "FOLDED:";
              }

              // Include statement index for stable key matching: stmtType:tag:var=value:INDEXN
              return stmtType
                  + ":"
                  + tag
                  + leftLocal.getName()
                  + "="
                  + constConst
                  + ":INDEX"
                  + statementIndex;
            } catch (Exception e) {
              // If creating constant fails, just mark as TOP and keep original
              return null;
            }
          }

          // If rhsVal is TOP (unsupported RHS), keep original JAssign unchanged
          // Return null to indicate no simplification
          return null;
        }

        // Handle field writes and array writes with constant RHS
        // We don't track these in environment, but we should tag them if RHS is constant
        if (rhsVal.isConst()) {
          try {
            int constValue = rhsVal.getInt();
            IntConstant constConst = IntConstant.getInstance(constValue);

            // Format left-hand side as string for tagging
            String lhsStr = leftOp.toString();

            // Determine tag based on RHS type
            String tag;
            if (rightOp instanceof Constant) {
              // Direct constant assignment
              tag = "CONSTANT:";
            } else if (rightOp instanceof Local) {
              // Propagation from another variable
              tag = "PROPAGATED:";
            } else {
              // Folded expression (arithmetic/bitwise operation)
              tag = "FOLDED:";
            }

            // Tag field/array writes with constants: stmtType:tag:lhs=value:INDEXN
            return stmtType + ":" + tag + lhsStr + "=" + constConst + ":INDEX" + statementIndex;
          } catch (Exception e) {
            // If creating constant fails, keep original
            return null;
          }
        }

        // If RHS is not constant, keep original statement
        return null;
      }

      // Handle JIf statements: evaluate condition (treat as TOP if unsupported),
      // keep original statement, propagate env to both successors (in linear pass, just continue)
      if (stmt instanceof JIfStmt) {
        JIfStmt ifStmt = (JIfStmt) stmt;
        AbstractConditionExpr condition = ifStmt.getCondition();

        // Evaluate condition - treat as TOP if unsupported
        // This is for internal tracking only; we don't use it to remove branches
        // in this simple forward pass
        eval(condition, env);

        // Keep original if statement exactly as is
        // Environment propagates to both successors (in linear pass, we just continue with same
        // env)
        return null; // No simplification
      }

      // Handle return statements - tag if returning a constant
      if (stmt instanceof JReturnStmt) {
        JReturnStmt ret = (JReturnStmt) stmt;
        Value returnValue = ret.getOp();
        ConstVal returnVal = eval(returnValue, env);

        // If returning a constant, tag it
        if (returnVal.isConst()) {
          try {
            int constValue = returnVal.getInt();
            IntConstant constConst = IntConstant.getInstance(constValue);
            // Format: JReturn:CONSTANT:return=value:INDEXN
            return stmtType + ":CONSTANT:return=" + constConst + ":INDEX" + statementIndex;
          } catch (Exception e) {
            // If creating constant fails, keep original
            return null;
          }
        }
        // If not a constant, keep original
        return null;
      }

      if (stmt instanceof JReturnVoidStmt) {
        // Void return - no value to propagate
        return null; // Keep original
      }

      // For all other statements (JInvoke, JThrow, etc.), keep as-is
      // Analysis doesn't need to model them in detail - just treat effects as unknown
      return null;
    } catch (Exception e) {
      // Never throw - if something goes wrong, just keep original statement
      // Don't update env (conservative: leave it as-is)
      return null;
    }
  }

  /** Evaluates a value into a ConstVal lattice, returns TOP for unknown cases. */
  private static ConstVal eval(Value v, Map<Local, ConstVal> env) {
    try {
      if (v == null) {
        return ConstVal.TOP;
      }

      // 1) Local variable - lookup in environment
      if (v instanceof Local) {
        Local local = (Local) v;
        return env.getOrDefault(local, ConstVal.TOP);
      }

      // 2) Integer constant
      if (v instanceof IntConstant) {
        try {
          int c = ((IntConstant) v).getValue();
          return ConstVal.constInt(c);
        } catch (Exception e) {
          return ConstVal.TOP;
        }
      }

      // 3) Binary operators: +, -, *, /, %, &, |, ^, <<, >>, >>>
      if (v instanceof AbstractBinopExpr) {
        try {
          AbstractBinopExpr be = (AbstractBinopExpr) v;
          ConstVal left = eval(be.getOp1(), env);
          ConstVal right = eval(be.getOp2(), env);

          // If both operands are CONST, compute result
          if (left.isConst() && right.isConst()) {
            try {
              int l = left.getInt();
              int r = right.getInt();

              // Choose based on operator type
              if (be instanceof JAddExpr) {
                return ConstVal.constInt(l + r);
              } else if (be instanceof JSubExpr) {
                return ConstVal.constInt(l - r);
              } else if (be instanceof JMulExpr) {
                return ConstVal.constInt(l * r);
              } else if (be instanceof JDivExpr) {
                // Beware division by zero
                if (r == 0) {
                  return ConstVal.TOP; // Division by zero is undefined
                }
                return ConstVal.constInt(l / r);
              } else if (be instanceof JAndExpr) {
                return ConstVal.constInt(l & r);
              } else if (be instanceof JOrExpr) {
                return ConstVal.constInt(l | r);
              } else if (be instanceof JXorExpr) {
                return ConstVal.constInt(l ^ r);
              } else if (be instanceof JShlExpr) {
                return ConstVal.constInt(l << r);
              } else if (be instanceof JShrExpr) {
                return ConstVal.constInt(l >> r);
              } else if (be instanceof JUshrExpr) {
                return ConstVal.constInt(l >>> r);
              }
              // Unknown binary operator - return TOP
              return ConstVal.TOP;
            } catch (Exception e) {
              // Arithmetic overflow or other computation error - return TOP
              return ConstVal.TOP;
            }
          } else {
            // At least one operand is not a constant, result is unknown
            return ConstVal.TOP;
          }
        } catch (Exception e) {
          // Error evaluating binary expression - return TOP
          return ConstVal.TOP;
        }
      }

      // 4) Anything else (field refs, array refs, casts, method calls, condition expressions, etc.)
      // Return TOP - never throw exceptions
      return ConstVal.TOP;
    } catch (Exception e) {
      // Catch-all: if anything goes wrong, return TOP
      return ConstVal.TOP;
    }
  }

  /** Gets the type/category of a statement. */
  private static String getStatementType(Stmt stmt) {
    if (stmt == null) {
      return "UNKNOWN";
    }
    String className = stmt.getClass().getSimpleName();
    if (className.endsWith("Stmt")) {
      return className.substring(0, className.length() - 4);
    }
    return className;
  }

  /** Formats a statement preserving parameter indices for constant propagation analysis. */
  private static String normalizeStatement(Stmt stmt) {
    if (stmt == null) {
      return "NULL";
    }

    // Get statement type outside try block so it's accessible in catch
    String type = getStatementType(stmt);

    try {
      // Handle identity statements specially to preserve parameter indices
      if (stmt instanceof JIdentityStmt) {
        JIdentityStmt identity = (JIdentityStmt) stmt;
        Local left = identity.getLeftOp();
        IdentityRef right = identity.getRightOp();

        // Format with preserved parameter indices
        if (right instanceof JParameterRef) {
          JParameterRef paramRef = (JParameterRef) right;
          // Preserve the actual parameter index
          String paramStr = "@parameter" + paramRef.getIndex() + ": " + paramRef.getType();
          return type + ":" + left.getName() + " := " + paramStr;
        } else if (right instanceof JThisRef) {
          JThisRef thisRef = (JThisRef) right;
          return type + ":" + left.getName() + " := @this: " + thisRef.getType();
        } else {
          // Other identity refs (caught exception, etc.)
          return type + ":" + left.getName() + " := " + right;
        }
      }

      // Handle return statements
      if (stmt instanceof JReturnVoidStmt) {
        return type + ":return";
      }
      if (stmt instanceof JReturnStmt) {
        JReturnStmt ret = (JReturnStmt) stmt;
        return type + ":return " + ret.getOp();
      }

      // For other statements (JAssign, JIf, JInvoke, JThrow, etc.),
      // use toString() but preserve parameter indices
      // Always output the original Jimple statement - never ERROR strings
      String stmtStr;
      try {
        stmtStr = stmt.toString();
      } catch (Exception e) {
        // If toString() fails, try to get a basic representation
        // This should rarely happen, but we need to handle it gracefully
        stmtStr = stmt.getClass().getSimpleName();
      }

      // Only normalize variable names (r0, r1, etc. and $stack0, $stack1, etc.)
      // DO NOT normalize @parameter indices - they are important for constant propagation
      stmtStr = stmtStr.replaceAll("\\br\\d+\\b", "rX");
      stmtStr = stmtStr.replaceAll("\\$stack\\d+", "$stackX");
      // Preserve @parameter0, @parameter1, etc. - do NOT replace them
      return type + ":" + stmtStr;
    } catch (Exception e) {
      // Never emit ERROR strings - always return original statement representation
      // Last resort: use statement type and class name
      try {
        return type + ":" + stmt.getClass().getSimpleName();
      } catch (Exception e2) {
        // Absolute last resort: just return the type
        return type + ":<statement>";
      }
    }
  }
}
