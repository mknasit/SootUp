package main; // import Util.DexNumTransformer;

import Util.DexUtil;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.LinkedListMultimap;
import dexpler.DexMethodSource;
import instruction.*;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.*;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.ClassPathResolver;
import org.jf.dexlib2.analysis.ClassProvider;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.debug.DebugItem;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.debug.ImmutableEndLocal;
import org.jf.dexlib2.immutable.debug.ImmutableLineNumber;
import org.jf.dexlib2.immutable.debug.ImmutableRestartLocal;
import org.jf.dexlib2.immutable.debug.ImmutableStartLocal;
import org.jf.dexlib2.util.MethodUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.graph.MutableBlockStmtGraph;
import sootup.core.jimple.Jimple;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.StmtPositionInfo;
import sootup.core.jimple.basic.Trap;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.stmt.BranchingStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JNopStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;
import sootup.core.transform.BodyInterceptor;
import sootup.core.types.ClassType;
import sootup.core.types.PrimitiveType;
import sootup.core.types.Type;
import sootup.core.types.UnknownType;
import sootup.core.views.View;
import sootup.java.core.JavaIdentifierFactory;
import sootup.java.core.types.JavaClassType;

public class DexBody {

  private static final Logger logger = LoggerFactory.getLogger(DexBody.class);

  protected int numRegisters;
  protected int numParameterRegisters;
  protected final List<String> parameterNames;
  protected boolean isStatic;

  protected List<? extends TryBlock<? extends ExceptionHandler>> tries;

  protected List<DeferableInstruction> deferredInstructions;

  protected final Method method;

  protected Set<Local> locals;

  protected final List<Type> parameterTypes;

  protected Set<String> takenLocalNames;

  protected Local[] registerLocals;

  protected Local storeResultLocal;

  protected final MultiDexContainer.DexEntry dexEntry;

  List<Stmt> stmtList = new ArrayList<>();

  protected Map<Integer, DexLibAbstractInstruction> instructionAtAddress;

  protected List<DexLibAbstractInstruction> instructions;

  protected DanglingInstruction dangling;

  protected ClassType classType;

  protected List<Trap> traps;

  protected Set<ReTypeableInstruction> instructionsToRetype;

  LinkedListMultimap<BranchingStmt, List<Stmt>> branchingMap = LinkedListMultimap.create();

  protected class RegDbgEntry {
    public int startAddress;
    public int endAddress;
    public int register;
    public String name;
    public Type type;
    public String signature;

    public RegDbgEntry(int sa, int ea, int reg, String nam, String ty, String sig) {
      this.startAddress = sa;
      this.endAddress = ea;
      this.register = reg;
      this.name = nam;
      this.type = DexUtil.toSootType(ty, 0);
      this.signature = sig;
    }
  }

  public LinkedListMultimap<BranchingStmt, List<Stmt>> getBranchingMap() {
    return branchingMap;
  }

  public void addDeferredJimplification(DeferableInstruction i) {
    deferredInstructions.add(i);
  }

  private final ArrayListMultimap<Integer, RegDbgEntry> localDebugs;

  public DexBody(Method method, MultiDexContainer.DexEntry dexEntry, ClassType classType) {
    MethodImplementation code = method.getImplementation();
    if (code == null) {
      throw new RuntimeException("error: no code for method " + method.getName());
    }

    tries = code.getTryBlocks();
    locals = new LinkedHashSet<>();

    parameterNames = new ArrayList<String>();
    parameterTypes = new ArrayList<Type>();
    for (MethodParameter param : method.getParameters()) {
      parameterNames.add(param.getName());
      parameterTypes.add(DexUtil.toSootType(param.getType(), 0));
    }

    takenLocalNames = new HashSet<>();
    traps = new ArrayList<>();
    deferredInstructions = new ArrayList<>();

    isStatic = Modifier.isStatic(method.getAccessFlags());
    numRegisters = code.getRegisterCount();
    numParameterRegisters = MethodUtil.getParameterRegisterCount(method);
    if (!isStatic) {
      numParameterRegisters--;
    }

    instructions = new ArrayList<>();
    instructionAtAddress = new HashMap<>();
    instructionsToRetype = new HashSet<>();
    localDebugs = ArrayListMultimap.create();

    extractDexInstructions(code);

    if (numParameterRegisters > numRegisters) {
      throw new RuntimeException(
          "Malformed dex file: insSize ("
              + numParameterRegisters
              + ") > registersSize ("
              + numRegisters
              + ")");
    }

    registerLocals = new Local[numRegisters];

    for (DebugItem di : code.getDebugItems()) {
      if (di instanceof ImmutableLineNumber) {
        ImmutableLineNumber ln = (ImmutableLineNumber) di;
        instruction.DexLibAbstractInstruction ins = instructionAtAddress(ln.getCodeAddress());
        if (ins == null) {
          // Debug.printDbg("Line number tag pointing to invalid
          // offset: " + ln.getCodeAddress());
          continue;
        }
        ins.setLineNumber(ln.getLineNumber());
      } else if (di instanceof ImmutableStartLocal || di instanceof ImmutableRestartLocal) {
        int reg, codeAddr;
        String type, signature, name;
        if (di instanceof ImmutableStartLocal) {
          ImmutableStartLocal sl = (ImmutableStartLocal) di;
          reg = sl.getRegister();
          codeAddr = sl.getCodeAddress();
          name = sl.getName();
          type = sl.getType();
          signature = sl.getSignature();
        } else {
          ImmutableRestartLocal sl = (ImmutableRestartLocal) di;
          // ImmutableRestartLocal and ImmutableStartLocal share the same members but
          // don't share a base. So we have to write some duplicated code.
          reg = sl.getRegister();
          codeAddr = sl.getCodeAddress();
          name = sl.getName();
          type = sl.getType();
          signature = sl.getSignature();
        }
        if (name != null && type != null) {
          localDebugs.put(
              reg, new RegDbgEntry(codeAddr, -1 /* endAddress */, reg, name, type, signature));
        }
      } else if (di instanceof ImmutableEndLocal) {
        ImmutableEndLocal el = (ImmutableEndLocal) di;
        List<RegDbgEntry> lds = localDebugs.get(el.getRegister());
        if (lds == null || lds.isEmpty()) {
          // Invalid debug info
          continue;
        } else {
          lds.get(lds.size() - 1).endAddress = el.getCodeAddress();
        }
      }
    }

    this.method = method;
    this.dexEntry = dexEntry;
    this.classType = classType;
  }

  public DexLibAbstractInstruction instructionAtAddress(int address) {
    DexLibAbstractInstruction i = null;
    while (i == null && address >= 0) {
      i = instructionAtAddress.get(address);
      address--;
    }
    return i;
  }

  /**
   * Add a retypeable instruction to this body.
   *
   * @param i the retypeable instruction.
   */
  public void addRetype(ReTypeableInstruction i) {
    instructionsToRetype.add(i);
  }

  public Local getStoreResultLocal() {
    return storeResultLocal;
  }

  public void add(Stmt stmt) {
    stmtList.add(stmt);
  }

  public void addBranchingStmt(BranchingStmt branchingStmt, List<Stmt> stmt) {
    branchingMap.put(branchingStmt, stmt);
  }

  public void insertAfter(Stmt tobeInserted, Stmt afterThisStmt) {
    int specificStmtIndex = stmtList.indexOf(afterThisStmt);
    if (specificStmtIndex != -1) {
      // If the specific statement is found in the list
      stmtList.add(specificStmtIndex + 1, tobeInserted);
    }
  }

  public void replaceStmt(Stmt toBeReplaced, Stmt newStmt) {
    int indexOf = stmtList.indexOf(toBeReplaced);
    if (indexOf != -1) {
      stmtList.set(indexOf, newStmt);
    }
  }

  protected void extractDexInstructions(MethodImplementation code) {
    int address = 0;
    for (Instruction instruction : code.getInstructions()) {
      DexLibAbstractInstruction dexInstruction =
          InstructionFactory.fromInstruction(instruction, address);
      instructions.add(dexInstruction);
      instructionAtAddress.put(address, dexInstruction);
      address += instruction.getCodeUnits();
    }
  }

  public List<DexLibAbstractInstruction> instructionsBefore(DexLibAbstractInstruction instruction) {
    int i = instructions.indexOf(instruction);
    if (i == -1) {
      throw new IllegalArgumentException("Instruction " + instruction + " not part of this body.");
    }

    List<DexLibAbstractInstruction> l = new ArrayList<DexLibAbstractInstruction>();
    l.addAll(instructions.subList(0, i));
    Collections.reverse(l);
    return l;
  }

  /**
   * Return the Local that are associated with the number in the current register state.
   *
   * <p>Handles if the register number actually points to a method parameter.
   *
   * @param num the register number
   * @throws RuntimeException
   */
  public Local getRegisterLocal(int num) {
    int totalRegisters = registerLocals.length;
    if (num >= totalRegisters) {
      throw new RuntimeException(
          "Trying to access register "
              + num
              + " but only "
              + totalRegisters
              + " is/are available.");
    }
    return registerLocals[num];
  }

  @Override
  public String toString() {
    return "The method "
        + method.getName()
        + " contains "
        + numRegisters
        + " Registers "
        + "and "
        + numParameterRegisters
        + " parameterRegisters."
        + "\n Is this method Static? "
        + isStatic
        + "\n The parameters of this methods are "
        + parameterNames;
  }

  public SootMethod makeSootMethod(
      Method method, ClassType classType, List<BodyInterceptor> bodyInterceptors, View<?> view) {
    jimplify();
    // All the statements are converted, it is time to create a mutable statement graph
    MutableBlockStmtGraph graph = new MutableBlockStmtGraph();
    // If the Nop Statements are not removed, graph.initializeWith throws a runtime exception
    // It is only for the case where there is a JNop Statement after the return statement. Crazy
    // android code :(
    stmtList.removeIf(JNopStmt.class::isInstance);
    graph.initializeWith(stmtList, convertMultimap(branchingMap), traps);
    DexMethodSource dexMethodSource =
        new DexMethodSource(
            classType, locals, graph, method, parameterTypes, bodyInterceptors, view);
    return dexMethodSource.makeSootMethod();
  }

  // Just a conversion code from LinkedListMultimap<BranchingStmt, List<Stmt>> to Map<BranchingStmt,
  // List<Stmt>>.
  public Map<BranchingStmt, List<Stmt>> convertMultimap(
      LinkedListMultimap<BranchingStmt, List<Stmt>> multimap) {
    Map<BranchingStmt, List<Stmt>> resultMap = new HashMap<>();

    for (Map.Entry<BranchingStmt, Collection<List<Stmt>>> entry : multimap.asMap().entrySet()) {
      resultMap.put(entry.getKey(), new ArrayList<>(entry.getValue().iterator().next()));
    }

    return resultMap;
  }

  public void jimplify() {
    final UnknownType unknownType = UnknownType.getInstance();
    final NullConstant nullConstant = NullConstant.getInstance();
    // process method parameters and generate Jimple locals from Dalvik
    // registers
    List<Local> paramLocals = new LinkedList<Local>();
    if (!isStatic) {
      int thisRegister = numRegisters - numParameterRegisters - 1;
      Local thisLocal = Jimple.newLocal(freshLocalName("this"), unknownType);
      locals.add(thisLocal);
      registerLocals[thisRegister] = thisLocal;

      JIdentityStmt idStmt =
          Jimple.newIdentityStmt(
              thisLocal, Jimple.newThisRef(classType), StmtPositionInfo.createNoStmtPositionInfo());
      add(idStmt);
      paramLocals.add(thisLocal);
    }
    {
      int i = 0; // index of parameter type
      int argIdx = 0;
      int parameterRegister = numRegisters - numParameterRegisters; // index of parameter register
      for (Type type : parameterTypes) {
        String localName = null;
        Type localType = null;
        if (localName == null && localDebugs.containsKey(parameterRegister)) {
          localName = localDebugs.get(parameterRegister).get(0).name;
        } else {
          localName = "$u" + parameterRegister;
        }

        if (localType == null) {
          // may only use UnknownType here because the local may be
          // reused with a different type later (before splitting)
          localType = unknownType;
        }

        Local gen = Jimple.newLocal(freshLocalName(localName), localType);
        locals.add(gen);
        registerLocals[parameterRegister] = gen;

        JIdentityStmt jIdentityStmt =
            Jimple.newIdentityStmt(
                gen,
                Jimple.newParameterRef(type, i++),
                StmtPositionInfo.createNoStmtPositionInfo());
        add(jIdentityStmt);
        paramLocals.add(gen);

        // some parameters may be encoded on two registers.
        // in Jimple only the first Dalvik register name is used
        // as the corresponding Jimple Local name. However, we also add
        // the second register to the registerLocals array since it
        // could be used later in the Dalvik bytecode
        if (type instanceof PrimitiveType.LongType || type instanceof PrimitiveType.DoubleType) {
          parameterRegister++;
          // may only use UnknownType here because the local may be reused with a different
          // type later (before splitting)
          String name;
          if (localDebugs.containsKey(parameterRegister)) {
            name = localDebugs.get(parameterRegister).get(0).name;
          } else {
            name = "$u" + parameterRegister;
          }
          Local local = Jimple.newLocal(freshLocalName(name), unknownType);
          locals.add(local);
          registerLocals[parameterRegister] = local;
        }
        parameterRegister++;
        argIdx++;
      }
    }

    for (int i = 0; i < (numRegisters - numParameterRegisters - (isStatic ? 0 : 1)); i++) {
      String name;
      if (localDebugs.containsKey(i)) {
        name = localDebugs.get(i).get(0).name;
      } else {
        name = "$u" + i;
      }
      registerLocals[i] = Jimple.newLocal(freshLocalName(name), unknownType);
      locals.add(registerLocals[i]);
    }
    // add local to store intermediate results
    storeResultLocal = Jimple.newLocal(freshLocalName("$stack"), unknownType);
    locals.add(storeResultLocal);
    // process ByteCode instructions
    final DexFile dexFile = dexEntry.getDexFile();
    final boolean isOdex =
        dexFile instanceof DexBackedDexFile
            && ((DexBackedDexFile) dexFile).supportsOptimizedOpcodes();
    ClassPath cp = null;
    if (isOdex) {
      try {
        ArrayList<String> classPaths = new ArrayList<>();
        //                    classPaths.add("/Users/palaniappanmuthuraman/Desktop/dex_files"); //
        // directory of the current apk we are dealing with
        ClassPathResolver resolver =
            new ClassPathResolver(classPaths, Collections.emptyList(), dexEntry);
        cp = new ClassPath(resolver.getResolvedClassProviders().toArray(new ClassProvider[0]));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    int prevLineNumber = -1;
    for (DexLibAbstractInstruction instruction : instructions) {
      if (dangling != null) {
        dangling.finalize(this, instruction);
        dangling = null;
      }
      if (instruction.getLineNumber() > 0) {
        prevLineNumber = instruction.getLineNumber();
      } else {
        instruction.setLineNumber(prevLineNumber);
      }
      instruction.jimplify(this);
    }
    if (dangling != null) {
      dangling.finalize(this, null);
    }
    for (DeferableInstruction instruction : deferredInstructions) {
      instruction.deferredJimplify(this);
    }
    if (tries != null && !tries.isEmpty()) {
      addTraps();
    }
    addBranchingMap(instructions);
    // By this point, all the "jimplification" process should be done, so clean everything.
    instructions = null;
    instructionAtAddress.clear();
    dangling = null;
    tries = null;
    parameterNames.clear();

    for (ReTypeableInstruction reTypeableInstruction : instructionsToRetype) {
      //                reTypeableInstruction.retype(this);
    }
  }

  private void addBranchingMap(List<DexLibAbstractInstruction> instructions) {
    instructions.stream()
        .filter(SwitchInstruction.class::isInstance)
        .forEach(
            switchInstruction -> {
              ((SwitchInstruction) switchInstruction).addBranchingStmts(this);
            });

    instructions.stream()
        .filter(JumpInstruction.class::isInstance)
        .forEach(
            dexLibAbstractInstruction -> {
              Stmt targetStmt;
              targetStmt =
                  ((JumpInstruction) dexLibAbstractInstruction).targetInstruction.getStmt();
              if (targetStmt != null) {
                branchingMap.put(
                    (BranchingStmt) dexLibAbstractInstruction.getStmt(),
                    Collections.singletonList(targetStmt));
              }
            });
  }

  private void addTraps() {
    for (TryBlock<? extends ExceptionHandler> tryItem : tries) {
      int startAddress = tryItem.getStartCodeAddress();
      int length = tryItem.getCodeUnitCount(); // .getTryLength();
      int endAddress = startAddress + length; // - 1;
      Stmt beginStmt = instructionAtAddress(startAddress).getStmt();
      // (startAddress + length) typically points to the first byte of the
      // first instruction after the try block
      // except if there is no instruction after the try block in which
      // case it points to the last byte of the last
      // instruction of the try block. Removing 1 from (startAddress +
      // length) always points to "somewhere" in
      // the last instruction of the try block since the smallest
      // instruction is on two bytes (nop = 0x0000).
      Stmt endStmt = instructionAtAddress(endAddress).getStmt();
      // if the try block ends on the last instruction of the body, add a
      // nop instruction so Soot can include
      // the last instruction in the try block.
//      if (stmtList.get(stmtList.size() - 1) == endStmt
//          && instructionAtAddress(endAddress - 1).getStmt() == endStmt) {
//        Stmt nop = Jimple.newNopStmt(StmtPositionInfo.createNoStmtPositionInfo());
//        insertAfter(endStmt, endStmt);
//        endStmt = nop;
//      }

      List<? extends ExceptionHandler> hList = tryItem.getExceptionHandlers();
      for (ExceptionHandler handler : hList) {
        String exceptionType = handler.getExceptionType();
        if (exceptionType == null) {
          exceptionType = "Ljava/lang/Throwable;";
        }
        Type t = DexUtil.toSootType(exceptionType, 0);
        // exceptions can only be of ReferenceType
        if (t instanceof JavaClassType) {
          JavaIdentifierFactory identifierFactory = JavaIdentifierFactory.getInstance();
          JavaClassType type = identifierFactory.getClassType(((JavaClassType) t).getClassName());
          DexLibAbstractInstruction instruction =
              instructionAtAddress(handler.getHandlerCodeAddress());
          if (!(instruction instanceof MoveExceptionInstruction)) {
            logger.debug(
                    String.format(
                            "First instruction of trap handler unit not MoveException but %s",
                            instruction.getClass().getName()));
          } else {
            ((MoveExceptionInstruction) instruction).setRealType(this, type);
          }
          Trap trap = Jimple.newTrap(type, beginStmt, endStmt, instruction.getStmt());
          traps.add(trap);
        }
      }
    }
  }

  public void setDanglingInstruction(DanglingInstruction i) {
    dangling = i;
  }

  protected String freshLocalName(String hint) {
    if (hint == null || hint.isEmpty()) {
      hint = "$local";
    }
    String fresh;
    if (!takenLocalNames.contains(hint)) {
      fresh = hint;
    } else {
      for (int i = 1; ; i++) {
        fresh = hint + i;
        if (!takenLocalNames.contains(fresh)) {
          break;
        }
      }
    }
    takenLocalNames.add(fresh);
    return fresh;
  }
}
