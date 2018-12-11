/* Soot - a J*va Optimization Framework
 * Copyright (C) 1999 Patrick Lam
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

/*
 * Modified by the Sable Research Group and others 1997-1999.  
 * See the 'credits' file distributed with Soot for the complete list of
 * contributors.  (Soot is distributed at http://www.sable.mcgill.ca/soot)
 */

package de.upb.soot.jimple.common.stmt;

import de.upb.soot.jimple.Jimple;
import de.upb.soot.jimple.basic.IStmtBox;
import de.upb.soot.jimple.visitor.IStmtVisitor;
import de.upb.soot.jimple.visitor.IVisitor;
import de.upb.soot.util.printer.IStmtPrinter;

import java.util.Collections;
import java.util.List;

public class JGotoStmt extends AbstractStmt {
  /**
   * 
   */
  private static final long serialVersionUID = -7771670610404109177L;
  final IStmtBox targetBox;
  final List<IStmtBox> targetBoxes;

  public JGotoStmt(IStmt target) {
    this(Jimple.newStmtBox(target));
  }

  public JGotoStmt(IStmtBox box) {
    targetBox = box;
    targetBoxes = Collections.singletonList(box);
  }

  @Override
  public JGotoStmt clone() {
    return new JGotoStmt(getTarget());
  }

  @Override
  public String toString() {
    IStmt t = getTarget();
    String target = "(branch)";
    if (!t.branches()) {
      target = t.toString();
    }
    return Jimple.GOTO + " [?= " + target + "]";
  }

  @Override
  public void toString(IStmtPrinter up) {
    up.literal(Jimple.GOTO);
    up.literal(" ");
    targetBox.toString(up);
  }

  public IStmt getTarget() {
    return targetBox.getStmt();
  }

  public void setTarget(IStmt target) {
    targetBox.setStmt(target);
  }

  public IStmtBox getTargetBox() {
    return targetBox;
  }

  @Override
  public List<IStmtBox> getStmtBoxes() {
    return targetBoxes;
  }

  @Override
  public void accept(IVisitor sw) {
    ((IStmtVisitor) sw).caseGotoStmt(this);
  }

  @Override
  public boolean fallsThrough() {
    return false;
  }

  @Override
  public boolean branches() {
    return true;
  }

  @Override
  public boolean equivTo(Object o) {
    if (!(o instanceof JGotoStmt) || !targetBox.getStmt().equivTo(((JGotoStmt) o).targetBox.getStmt())) {
      return false;
    }
    /*
     * JGotoStmt gotoStmt = (JGotoStmt) o; List<IStmtBox> gotoStmtBoxes = gotoStmt.getStmtBoxes(); if( gotoStmtBoxes.size()
     * != targetBoxes.size() ){ return false; } if( gotoStmt.equivHashCode() != equivHashCode()){ return false; }
     */
    return true;
  }

  @Override
  public int equivHashCode() {
    /*
     * int res = 7; int prime = 31; for(IStmtBox box : targetBoxes){ res += prime * box.getStmt().equivHashCode(); } return
     * res + prime * targetBox.getStmt().equivHashCode();
     */
    return targetBox.getStmt().equivHashCode();
  }

}