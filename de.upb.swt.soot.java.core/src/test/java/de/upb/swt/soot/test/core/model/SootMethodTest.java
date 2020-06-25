package de.upb.swt.soot.test.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import categories.Java8Test;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import de.upb.swt.soot.core.Project;
import de.upb.swt.soot.core.frontend.OverridingMethodSource;
import de.upb.swt.soot.core.inputlocation.EagerInputLocation;
import de.upb.swt.soot.core.jimple.Jimple;
import de.upb.swt.soot.core.jimple.basic.LocalGenerator;
import de.upb.swt.soot.core.jimple.basic.StmtPositionInfo;
import de.upb.swt.soot.core.jimple.common.stmt.Stmt;
import de.upb.swt.soot.core.model.Body;
import de.upb.swt.soot.core.model.Modifier;
import de.upb.swt.soot.core.model.SootMethod;
import de.upb.swt.soot.core.model.SourceType;
import de.upb.swt.soot.core.signatures.MethodSignature;
import de.upb.swt.soot.core.types.ClassType;
import de.upb.swt.soot.core.views.View;
import de.upb.swt.soot.java.core.JavaProject;
import de.upb.swt.soot.java.core.JavaSootClass;
import de.upb.swt.soot.java.core.OverridingJavaClassSource;
import de.upb.swt.soot.java.core.language.JavaLanguage;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** @author Linghui Luo */
@Category(Java8Test.class)
public class SootMethodTest {

  @Test
  public void testCreateMethod() {
    Project project =
        JavaProject.builder(new JavaLanguage(8)).addClassPath(new EagerInputLocation()).build();
    View view = project.createOnDemandView();
    ClassType type = view.getIdentifierFactory().getClassType("java.lang.String");

    LocalGenerator generator = new LocalGenerator(new HashSet<>());
    final MutableGraph<Stmt> graph = GraphBuilder.directed().build();

    graph.addNode(
        Jimple.newIdentityStmt(
            generator.generateLocal(type),
            Jimple.newParameterRef(type, 0),
            StmtPositionInfo.createNoStmtPositionInfo()));
    graph.addNode(
        Jimple.newAssignStmt(
            generator.generateLocal(type),
            Jimple.newNewExpr(type),
            StmtPositionInfo.createNoStmtPositionInfo()));

    MethodSignature methodSignature =
        view.getIdentifierFactory()
            .getMethodSignature("main", "dummyMain", "void", Collections.emptyList());
    Body.BodyBuilder bodyBuilder = new Body.BodyBuilder(graph);
    bodyBuilder
            .setMethodSignature(methodSignature)
        .setLocals(generator.getLocals())
        .setTraps(Collections.emptyList())
        .setFirstStmt(null)
        .setPosition(null);
    Body body = bodyBuilder.build();
    assertEquals(2, body.getLocalCount());

    SootMethod dummyMainMethod =
        new SootMethod(
            new OverridingMethodSource(methodSignature, body),
            methodSignature,
            EnumSet.of(Modifier.PUBLIC, Modifier.STATIC),
            Collections.emptyList());

    JavaSootClass mainClass =
        new JavaSootClass(
            new OverridingJavaClassSource(
                new EagerInputLocation(),
                null,
                view.getIdentifierFactory().getClassType("dummyMain"),
                null,
                Collections.emptySet(),
                null,
                Collections.emptySet(),
                Collections.singleton(dummyMainMethod),
                null,
                EnumSet.of(Modifier.PUBLIC),
                Collections.emptyList()),
            SourceType.Application);

    assertEquals(mainClass.getMethods().size(), 1);
    assertTrue(
        mainClass
            .getMethod(methodSignature)
            .orElseThrow(() -> new RuntimeException("Failed getting method " + methodSignature))
            .hasBody());
  }
}
