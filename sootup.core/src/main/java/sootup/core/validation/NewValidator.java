package sootup.core.validation;

import java.util.*;
import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.expr.JSpecialInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.types.ReferenceType;
import sootup.core.types.UnknownType;

public class NewValidator implements BodyValidator {

  private static final String errorMsg =
      "There is a path from '%s' to the usage '%s' where <init> does not get called in between.";

  public static boolean MUST_CALL_CONSTRUCTOR_BEFORE_RETURN = false;

  //  Checks whether after each new-instruction a constructor call follows. /
  @Override
  public void validate(Body body, List<ValidationException> exceptions) {

    StmtGraph g = body.getStmtGraph();
    for (Stmt u : body.getStmts()) {
      if (u instanceof JAssignStmt) {
        JAssignStmt assign = (JAssignStmt) u;

        // First seek for a JNewExpr.
        if (assign.getRightOp() instanceof JNewExpr) {
          if (!((assign.getLeftOp().getType() instanceof ReferenceType)
              || assign.getLeftOp().getType() instanceof UnknownType)) {
            exceptions.add(
                new ValidationException(
                    assign.getLeftOp(),
                    String.format(
                        "Body of methodRef %s contains a new-expression, which is assigned to a non-reference local",
                        body.getMethodSignature())));
            return;
          }

          // We search for a JSpecialInvokeExpr on the local.
          LinkedHashSet<Local> locals = new LinkedHashSet<Local>();
          locals.add((Local) assign.getLeftOp());

          checkForInitializerOnPath(g, assign, exceptions);
        }
      }
    }
  }

  private boolean checkForInitializerOnPath(
      StmtGraph g, JAssignStmt newStmt, List<ValidationException> exception) {
    List<Stmt> workList = new ArrayList<Stmt>();
    Set<Stmt> doneSet = new HashSet<Stmt>();
    workList.add(newStmt);

    Set<Local> aliasingLocals = new HashSet<Local>();
    aliasingLocals.add((Local) newStmt.getLeftOp());

    while (!workList.isEmpty()) {
      Stmt curStmt = (Stmt) workList.remove(0);
      if (!doneSet.add(curStmt)) {
        continue;
      }
      if (!newStmt.equals(curStmt)) {
        if (curStmt.containsInvokeExpr()) {
          AbstractInvokeExpr expr = curStmt.getInvokeExpr();
          if (!(expr instanceof JSpecialInvokeExpr)) {
            exception.add(
                new ValidationException(
                    curStmt.getInvokeExpr(),
                    "<init> methodRef calls may only be used with specialinvoke.")); // At least we
            // found an
            // initializer,
            // so we return
            // true...return true;
          }
          if (!(curStmt instanceof JInvokeStmt)) {
            exception.add(
                new ValidationException(
                    curStmt.getInvokeExpr(),
                    "<init> methods may only be called with invoke statements.")); // At least we
            // found an
            // initializer,
            // so we return
            // true...return
            // true;
          }

          JSpecialInvokeExpr invoke = (JSpecialInvokeExpr) expr;
          if (aliasingLocals.contains(
              invoke.getBase())) { // We are happy now,continue the loop and check other paths
            // continue;
          }
        }

        // We are still in the loop, so this was not the constructor call we // were looking for
        boolean creatingAlias = false;
        if (curStmt instanceof JAssignStmt) {
          JAssignStmt assignCheck = (JAssignStmt) curStmt;
          if (aliasingLocals.contains(assignCheck.getRightOp())) {
            if (assignCheck.getLeftOp()
                instanceof Local) { // A new aliasis created.aliasingLocals.add((Local)
              // assignCheck.getLeftOp());
              creatingAlias = true;
            }
          }
          Local originalLocal = aliasingLocals.iterator().next();
          if (originalLocal.equals(assignCheck.getLeftOp())) { // In case of dead assignments:

            // Handles cases like // $r0 = new x; // $r0 = null;

            // But not cases like // $r0 = new x; // $r1 = $r0; // $r1 = null; // Because we check
            // for the original local
            continue;
          } else { // Since the local on the left hand side gets overwritten // even if it was
            // aliasing with our original local, // now it does not any more...
            // aliasingLocals.remove(assignCheck.getLeftOp()); } }

            if (!creatingAlias) {
              for (Value box : curStmt.getUses()) {
                if (aliasingLocals.contains(
                    box)) { // The current unit uses one of the aliasing locals, but // there was no
                  // initializer in between. // However, when creating such an alias, the
                  // use is okay. exception.add(new ValidationException(newStmt,
                  String.format(errorMsg, newStmt, curStmt);
                  return false;
                }
              }
            }
          }
          // Enqueue the successors
          List successors = g.successors(curStmt);
          if (successors.isEmpty()
              && MUST_CALL_CONSTRUCTOR_BEFORE_RETURN) { // This means that we are e.g.at the end of
            // the methodRef // There was no <init> call
            // on our way...
            exception.add(
                new ValidationException(
                    newStmt.getLeftOp(), String.format(errorMsg, newStmt, curStmt)));
            return false;
          }
          workList.addAll(successors);
        }
      }
    }
    return true;
  }

  @Override
  public boolean isBasicValidator() {
    return false;
  }
}
