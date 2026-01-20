package com.jessintegration.sootup;

import com.jessintegration.model.MethodId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.types.ArrayType;
import sootup.core.types.ClassType;
import sootup.core.types.PrimitiveType;
import sootup.core.types.Type;
import sootup.core.views.View;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.types.JavaClassType;
import sootup.java.core.views.JavaView;

/** Converts bytecode to Jimple using SootUp. Supports loading from class files or JAR files. */
public final class JimpleConverter {

  /** Result of Jimple conversion attempt, including error details if failed. */
  public static final class ConversionResult {
    private final Optional<List<Stmt>> statements;
    private final Optional<String> errorMessage;
    private final Optional<String>
        errorStage; // "viewCreation", "classLoading", "methodFinding", "bodyExtraction"

    private ConversionResult(
        Optional<List<Stmt>> statements,
        Optional<String> errorMessage,
        Optional<String> errorStage) {
      this.statements = statements;
      this.errorMessage = errorMessage;
      this.errorStage = errorStage;
    }

    public static ConversionResult success(List<Stmt> statements) {
      return new ConversionResult(Optional.of(statements), Optional.empty(), Optional.empty());
    }

    public static ConversionResult failure(String stage, String message) {
      return new ConversionResult(Optional.empty(), Optional.of(message), Optional.of(stage));
    }

    public Optional<List<Stmt>> statements() {
      return statements;
    }

    public Optional<String> errorMessage() {
      return errorMessage;
    }

    public Optional<String> errorStage() {
      return errorStage;
    }

    public boolean isSuccess() {
      return statements.isPresent();
    }
  }

  /**
   * Converts a method's bytecode to Jimple statements from a class file.
   *
   * @param classFile Path to the .class file
   * @param method Method identifier
   * @return Optional list of Jimple statements, empty if method not found or conversion failed
   */
  public static Optional<List<Stmt>> convertFromClassFile(Path classFile, MethodId method) {
    return convertFromClassesDirectory(classFile.getParent(), method);
  }

  /**
   * Converts a method's bytecode to Jimple statements from a classes directory.
   *
   * @param classesDir Root directory containing class files (e.g., /path/to/classes/)
   * @param method Method identifier
   * @return Optional list of Jimple statements, empty if method not found or conversion failed
   */
  public static Optional<List<Stmt>> convertFromClassesDirectory(Path classesDir, MethodId method) {
    ConversionResult result = convertFromClassesDirectoryDetailed(classesDir, method);
    return result.statements();
  }

  /**
   * Converts a method's bytecode to Jimple statements from a classes directory with detailed error
   * information.
   *
   * @param classesDir Root directory containing class files (e.g., /path/to/classes/)
   * @param method Method identifier
   * @return ConversionResult with statements or detailed error information
   */
  public static ConversionResult convertFromClassesDirectoryDetailed(
      Path classesDir, MethodId method) {
    if (classesDir == null || !Files.isDirectory(classesDir)) {
      return ConversionResult.failure(
          "viewCreation", "classesDir is null or not a directory: " + classesDir);
    }

    try {
      // Create SootUp view from the root classes directory
      // IMPORTANT: Must include Java runtime for SootUp to resolve java.lang.* types
      java.util.List<sootup.core.inputlocation.AnalysisInputLocation> inputLocations =
          new ArrayList<>();
      inputLocations.add(
          PathBasedAnalysisInputLocation.create(
              classesDir, sootup.core.model.SourceType.Application));
      inputLocations.add(new DefaultRuntimeAnalysisInputLocation());

      JavaView view = new JavaView(inputLocations);

      return extractJimpleStatementsDetailed(view, method, classesDir.toString());
    } catch (Exception e) {
      return ConversionResult.failure(
          "viewCreation",
          "Exception creating view: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Converts a method's bytecode to Jimple statements from a JAR file.
   *
   * @param jarPath Path to the JAR file
   * @param method Method identifier
   * @return Optional list of Jimple statements, empty if method not found or conversion failed
   */
  public static Optional<List<Stmt>> convertFromJar(Path jarPath, MethodId method) {
    ConversionResult result = convertFromJarDetailed(jarPath, method);
    return result.statements();
  }

  /**
   * Converts a method's bytecode to Jimple statements from a JAR file with detailed error
   * information.
   *
   * @param jarPath Path to the JAR file
   * @param method Method identifier
   * @return ConversionResult with statements or detailed error information
   */
  public static ConversionResult convertFromJarDetailed(Path jarPath, MethodId method) {
    if (jarPath == null || !Files.isRegularFile(jarPath)) {
      return ConversionResult.failure("viewCreation", "jarPath is null or not a file: " + jarPath);
    }

    try {
      // Create SootUp view from the JAR file
      // IMPORTANT: Must include Java runtime for SootUp to resolve java.lang.* types
      java.util.List<sootup.core.inputlocation.AnalysisInputLocation> inputLocations =
          new ArrayList<>();
      inputLocations.add(
          PathBasedAnalysisInputLocation.create(jarPath, sootup.core.model.SourceType.Application));
      inputLocations.add(new DefaultRuntimeAnalysisInputLocation());

      JavaView view = new JavaView(inputLocations);

      return extractJimpleStatementsDetailed(view, method, jarPath.toString());
    } catch (Exception e) {
      return ConversionResult.failure(
          "viewCreation",
          "Exception creating view: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Extracts Jimple statements from a SootUp view for the given method.
   *
   * @param view SootUp JavaView
   * @param method Method identifier
   * @return Optional list of Jimple statements
   */
  private static Optional<List<Stmt>> extractJimpleStatements(View view, MethodId method) {
    ConversionResult result = extractJimpleStatementsDetailed(view, method, "unknown");
    return result.statements();
  }

  /**
   * Extracts Jimple statements from a SootUp view for the given method with detailed error
   * information.
   *
   * @param view SootUp JavaView
   * @param method Method identifier
   * @param sourceInfo Source information for error messages (e.g., path to classes dir or JAR)
   * @return ConversionResult with statements or detailed error information
   */
  private static ConversionResult extractJimpleStatementsDetailed(
      View view, MethodId method, String sourceInfo) {
    try {
      // Convert binary class name (e.g., "org/apache/commons/io/ByteBuffers") to Java class name
      String javaClassName = method.binaryClassName().replace('/', '.');

      // Get class type - cast to JavaView to get JavaIdentifierFactory
      if (!(view instanceof JavaView)) {
        return ConversionResult.failure(
            "classLoading", "View is not a JavaView: " + view.getClass().getName());
      }
      JavaView javaView = (JavaView) view;
      JavaClassType classType = javaView.getIdentifierFactory().getClassType(javaClassName);

      // Get the class - cast the result
      Optional<? extends sootup.core.model.SootClass> sootClassOptRaw = view.getClass(classType);
      if (sootClassOptRaw.isEmpty()) {
        // Try to get more information about what's available
        StringBuilder availableInfo = new StringBuilder();
        try {
          // Try to list some classes in the view (if possible)
          // Note: This might not be directly available, but we can try
          availableInfo.append("Class not found: ").append(javaClassName);
          availableInfo.append(" (source: ").append(sourceInfo).append(")");
        } catch (Exception e) {
          availableInfo.append("Class not found: ").append(javaClassName);
        }
        return ConversionResult.failure("classLoading", availableInfo.toString());
      }

      sootup.core.model.SootClass sootClassRaw = sootClassOptRaw.get();
      if (!(sootClassRaw instanceof JavaSootClass)) {
        return ConversionResult.failure(
            "classLoading", "Class is not a JavaSootClass: " + sootClassRaw.getClass().getName());
      }
      JavaSootClass sootClass = (JavaSootClass) sootClassRaw;

      // Find the method by name and descriptor
      // We need to match the method signature
      Optional<? extends SootMethod> methodOpt = findMethod(sootClass, method, view);
      if (methodOpt.isEmpty()) {
        // Try to get available methods for debugging
        Set<? extends SootMethod> allMethods = sootClass.getMethods();
        StringBuilder methodInfo = new StringBuilder();
        methodInfo.append("Method not found: ").append(method.name());
        methodInfo.append(" in class ").append(javaClassName);
        methodInfo.append(". Available methods: ");
        int count = 0;
        for (SootMethod m : allMethods) {
          if (count++ > 0) methodInfo.append(", ");
          methodInfo.append(m.getName());
          if (count >= 5) {
            methodInfo.append("... (").append(allMethods.size()).append(" total)");
            break;
          }
        }
        return ConversionResult.failure("methodFinding", methodInfo.toString());
      }

      SootMethod sootMethod = methodOpt.get();

      // Get method body
      Body body = sootMethod.getBody();
      if (body == null) {
        return ConversionResult.failure(
            "bodyExtraction", "Method has no body: " + method.name() + " in " + javaClassName);
      }

      // Get statements from the body - this returns them in order
      List<Stmt> statements = body.getStmts();

      return ConversionResult.success(statements);
    } catch (Exception e) {
      return ConversionResult.failure(
          "bodyExtraction", "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Finds a method in a SootClass by matching name and JVM descriptor. */
  private static Optional<? extends SootMethod> findMethod(
      JavaSootClass sootClass, MethodId method, View view) {
    String methodName = method.name();
    String jvmDescriptor = method.jvmDescriptor();

    // Get all methods with the same name
    Set<? extends SootMethod> methodsWithName = sootClass.getMethodsByName(methodName);

    if (methodsWithName.isEmpty()) {
      return Optional.empty();
    }

    // If there's only one method with this name, use it (if it has a body)
    if (methodsWithName.size() == 1) {
      SootMethod m = methodsWithName.iterator().next();
      if (m.hasBody()) {
        return Optional.of(m);
      }
      return Optional.empty();
    }

    // Multiple methods with same name - match by FULL JVM descriptor
    // This ensures we match the exact overload (e.g., short[] vs boolean[])
    for (SootMethod m : methodsWithName) {
      if (m.hasBody()) {
        // Build JVM descriptor from method signature
        StringBuilder methodDesc = new StringBuilder("(");
        for (Type paramType : m.getParameterTypes()) {
          methodDesc.append(typeToJvmDescriptor(paramType));
        }
        methodDesc.append(")");
        methodDesc.append(typeToJvmDescriptor(m.getReturnType()));

        // Compare descriptors directly (JVM descriptors are already normalized)
        if (methodDesc.toString().equals(jvmDescriptor)) {
          return Optional.of(m);
        }
      }
    }

    // Fallback: try to match by parameter count (for backwards compatibility)
    int paramCount = countParameters(jvmDescriptor);
    for (SootMethod m : methodsWithName) {
      if (m.hasBody() && m.getParameterCount() == paramCount) {
        return Optional.of(m);
      }
    }

    // Fallback: return first method with body
    for (SootMethod m : methodsWithName) {
      if (m.hasBody()) {
        return Optional.of(m);
      }
    }

    return Optional.empty();
  }

  /**
   * Counts the number of parameters from a JVM descriptor. JVM descriptor format:
   * (paramTypes)returnType
   */
  private static int countParameters(String jvmDescriptor) {
    if (jvmDescriptor == null || jvmDescriptor.isEmpty() || !jvmDescriptor.startsWith("(")) {
      return 0;
    }

    int count = 0;
    int i = 1; // Skip '('
    while (i < jvmDescriptor.length() && jvmDescriptor.charAt(i) != ')') {
      char c = jvmDescriptor.charAt(i);
      if (c == 'L') {
        // Object type: Lpackage/Class;
        int end = jvmDescriptor.indexOf(';', i);
        if (end == -1) break;
        i = end + 1;
        count++;
      } else if (c == '[') {
        // Array type: [type
        i++;
        while (i < jvmDescriptor.length() && jvmDescriptor.charAt(i) == '[') {
          i++;
        }
        if (i < jvmDescriptor.length() && jvmDescriptor.charAt(i) == 'L') {
          int end = jvmDescriptor.indexOf(';', i);
          if (end == -1) break;
          i = end + 1;
        } else {
          i++;
        }
        count++;
      } else {
        // Primitive type: B, C, D, F, I, J, S, Z
        i++;
        count++;
      }
    }
    return count;
  }

  /**
   * Converts a SootUp Type to JVM descriptor format. Examples: int -> I, boolean[] -> [Z, String ->
   * Ljava/lang/String;
   */
  private static String typeToJvmDescriptor(Type type) {
    if (type == null) {
      return "V"; // void
    }

    // Handle primitive types
    if (type instanceof PrimitiveType) {
      PrimitiveType pt = (PrimitiveType) type;
      String typeName = pt.getName(); // Use getName() instead of getTypeName()
      if (typeName.equals("int")) return "I";
      if (typeName.equals("long")) return "J";
      if (typeName.equals("float")) return "F";
      if (typeName.equals("double")) return "D";
      if (typeName.equals("boolean")) return "Z";
      if (typeName.equals("byte")) return "B";
      if (typeName.equals("char")) return "C";
      if (typeName.equals("short")) return "S";
      if (typeName.equals("void")) return "V";
    }

    // Handle array types
    if (type instanceof ArrayType) {
      ArrayType at = (ArrayType) type;
      return "[" + typeToJvmDescriptor(at.getBaseType());
    }

    // Handle class types
    if (type instanceof ClassType) {
      ClassType ct = (ClassType) type;
      return "L" + ct.getFullyQualifiedName().replace('.', '/') + ";";
    }

    // Fallback: try to infer from type name
    String typeName = type.toString();
    if (typeName.equals("int")) return "I";
    if (typeName.equals("long")) return "J";
    if (typeName.equals("float")) return "F";
    if (typeName.equals("double")) return "D";
    if (typeName.equals("boolean")) return "Z";
    if (typeName.equals("byte")) return "B";
    if (typeName.equals("char")) return "C";
    if (typeName.equals("short")) return "S";
    if (typeName.equals("void")) return "V";

    // For class types, assume it's a class and convert package separators
    if (!typeName.contains("[")) {
      return "L" + typeName.replace('.', '/') + ";";
    }

    // Unknown type - return as-is (shouldn't happen)
    return typeName;
  }

  /**
   * Gets a string representation of Jimple statements for debugging/comparison.
   *
   * @param statements List of Jimple statements
   * @return String representation
   */
  public static String statementsToString(List<Stmt> statements) {
    if (statements == null || statements.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Stmt stmt : statements) {
      sb.append(stmt.toString()).append("\n");
    }
    return sb.toString().trim();
  }

  /**
   * Normalizes Jimple statements for comparison by: 1. Sorting statements by canonical
   * representation (type + content hash) 2. Renaming all local variables deterministically (v0, v1,
   * v2, ...) 3. Normalizing formatting
   *
   * <p>This makes comparison more accurate by ignoring: - Statement ordering differences - Variable
   * name differences - Formatting differences
   *
   * @param statements List of Jimple statements
   * @return Normalized string representation suitable for comparison
   */
  public static String normalizeJimpleForComparison(List<Stmt> statements) {
    if (statements == null || statements.isEmpty()) {
      return "";
    }

    // Step 1: Extract all local variables and build mapping (do this once)
    Map<Local, String> localMap = buildLocalVariableMap(statements);

    // Step 2: Sort statements by canonical representation (using the same localMap)
    List<Stmt> sorted = sortStatementsCanonically(statements, localMap);

    // Step 3: Convert to string with normalized locals
    StringBuilder sb = new StringBuilder();
    for (Stmt stmt : sorted) {
      String normalized = normalizeStatementString(stmt, localMap);
      sb.append(normalized).append(";");
    }

    return sb.toString();
  }

  /**
   * Builds a map from original local variable names to normalized names (v0, v1, v2, ...).
   * Preserves order of first occurrence.
   */
  private static Map<Local, String> buildLocalVariableMap(List<Stmt> statements) {
    Map<Local, String> localMap = new LinkedHashMap<>();
    int counter = 0;

    for (Stmt stmt : statements) {
      // Extract all locals used in this statement
      Set<Local> locals = extractLocalsFromStatement(stmt);
      for (Local local : locals) {
        if (!localMap.containsKey(local)) {
          localMap.put(local, "v" + counter++);
        }
      }
    }

    return localMap;
  }

  /** Extracts all Local variables from a statement (both uses and defs). */
  private static Set<Local> extractLocalsFromStatement(Stmt stmt) {
    Set<Local> locals = new LinkedHashSet<>();

    // Get all uses (values used in the statement)
    stmt.getUses()
        .forEach(
            value -> {
              if (value instanceof Local) {
                locals.add((Local) value);
              }
            });

    // Get def (value defined by the statement)
    stmt.getDef()
        .ifPresent(
            def -> {
              if (def instanceof Local) {
                locals.add((Local) def);
              }
            });

    return locals;
  }

  /**
   * Sorts statements by canonical representation to make comparison order-independent. Uses:
   * statement type + normalized content (with variable names replaced).
   *
   * @param statements List of statements to sort
   * @param localMap Pre-built local variable mapping (to avoid rebuilding)
   */
  private static List<Stmt> sortStatementsCanonically(
      List<Stmt> statements, Map<Local, String> localMap) {
    return statements.stream()
        .sorted(
            Comparator.comparing(
                stmt -> {
                  // Create canonical representation: type + normalized content
                  String type = stmt.getClass().getSimpleName();
                  String normalized = normalizeStatementString(stmt, localMap);
                  return type + ":" + normalized;
                }))
        .collect(Collectors.toList());
  }

  /**
   * Normalizes a statement string by replacing local variable names with normalized names.
   * Preserves structure but makes variable names deterministic.
   */
  private static String normalizeStatementString(Stmt stmt, Map<Local, String> localMap) {
    String stmtStr = stmt.toString();

    // Replace each local variable with its normalized name
    // Process in reverse order of key length to avoid partial replacements
    List<Map.Entry<Local, String>> sortedEntries =
        localMap.entrySet().stream()
            .sorted(
                (e1, e2) ->
                    Integer.compare(e2.getKey().getName().length(), e1.getKey().getName().length()))
            .collect(Collectors.toList());

    for (Map.Entry<Local, String> entry : sortedEntries) {
      String originalName = entry.getKey().getName();
      String normalizedName = entry.getValue();
      // Use word boundaries to avoid partial matches
      stmtStr = stmtStr.replaceAll("\\b" + escapeRegex(originalName) + "\\b", normalizedName);
    }

    // Normalize whitespace
    stmtStr = stmtStr.replaceAll("\\s+", " ").trim();

    return stmtStr;
  }

  /** Escapes special regex characters in a string. */
  private static String escapeRegex(String str) {
    return str.replaceAll("[\\[\\]{}()*+?.\\\\^$|]", "\\\\$0");
  }
}
