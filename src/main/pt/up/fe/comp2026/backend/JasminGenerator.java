package pt.up.fe.comp2026.backend;

import org.specs.comp.ollir.*;
import org.specs.comp.ollir.inst.*;
import org.specs.comp.ollir.tree.TreeNode;
import org.specs.comp.ollir.type.ArrayType;
import org.specs.comp.ollir.type.BuiltinKind;
import org.specs.comp.ollir.type.BuiltinType;
import org.specs.comp.ollir.type.ClassType;
import org.specs.comp.ollir.type.Type;
import pt.up.fe.comp.jmm.ollir.OllirResult;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp2026.optimization.OptUtils;
import pt.up.fe.specs.util.SpecsCheck;
import pt.up.fe.specs.util.classmap.FunctionClassMap;
import pt.up.fe.specs.util.exceptions.NotImplementedException;
import pt.up.fe.specs.util.utilities.StringLines;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generates Jasmin code from an OllirResult.
 * <p>
 * One JasminGenerator instance per OllirResult.
 */
public class JasminGenerator {

    private static final String NL = "\n";
    private static final String TAB = "   ";

    private final OllirResult ollirResult;

    private List<Report> reports;

    private String code;

    private Method currentMethod;

    boolean isInsideAssignment;

    private final JasminUtils types;
    private OptUtils utils;
    private final FunctionClassMap<TreeNode, String> generators;

    private int currentStack;
    private int maxStack;
    private int labelCounter;

    public JasminGenerator(OllirResult ollirResult) {
        this.ollirResult = ollirResult;

        reports = new ArrayList<>();
        code = null;
        currentMethod = null;
        isInsideAssignment = false;

        types = new JasminUtils(ollirResult);
        // Initialize everytime we start a method
        utils = null;
        this.generators = new FunctionClassMap<>();
        generators.put(ClassUnit.class, this::generateClassUnit);
        generators.put(Method.class, this::generateMethod);
        generators.put(AssignInstruction.class, this::generateAssign);
        generators.put(SingleOpInstruction.class, this::generateSingleOp);
        generators.put(LiteralElement.class, this::generateLiteral);
        generators.put(Operand.class, this::generateOperand);
        generators.put(BinaryOpInstruction.class, this::generateBinaryOp);
        generators.put(UnaryOpInstruction.class, this::generateUnaryOp);
        generators.put(ReturnInstruction.class, this::generateReturn);

        // Method Calls
        generators.put(InvokeVirtualInstruction.class, this::generateInvokeVirtual);
        generators.put(InvokeStaticInstruction.class, this::generateInvokeStatic);
        generators.put(InvokeSpecialInstruction.class, this::generateInvokeSpecial);

        // Fields
        generators.put(PutFieldInstruction.class, this::generatePutField);
        generators.put(GetFieldInstruction.class, this::generateGetField);

        // Arrays
        generators.put(NewInstruction.class, this::generateNew);
        generators.put(ArrayLengthInstruction.class, this::generateArrayLength);
        generators.put(ArrayOperand.class, this::generateArrayLoad);

        // Control flow
        generators.put(GotoInstruction.class, this::generateGoto);
        generators.put(SingleOpCondInstruction.class, this::generateSingleOpCond);
        generators.put(OpCondInstruction.class, this::generateOpCond);
    }

    private int computeLocals(Method method) {

        int maxReg = 0;

        for (var descriptor : method.getVarTable().values()) {
            maxReg = Math.max(maxReg,
                    descriptor.getVirtualReg());
        }

        return maxReg + 1;
    }

    private void updateStack(int delta) {
        currentStack += delta;
        maxStack = Math.max(maxStack, currentStack);
    }

    private String generateInvokeVirtual(
            InvokeVirtualInstruction invoke) {

        var code = new StringBuilder();

        // load object reference (this)
        code.append(apply(invoke.getCaller()));

        // load arguments
        for (var arg : invoke.getArguments()) {
            code.append(apply(arg));
        }

        // owner class
        var callerType = invoke.getCaller().getType();
        String callerClassName = (callerType instanceof ClassType ct) ? ct.getName() : callerType.getNodeName();
        String className = types.getFullClassName(callerClassName);

        // method name
        String methodName =
                ((LiteralElement) invoke.getMethodName())
                        .getLiteral()
                        .replace("\"", "");

        // parameter descriptor
        String params = invoke.getArguments().stream()
                .map(arg -> types.getTypeDescriptor(arg.getType()))
                .collect(Collectors.joining());

        // return descriptor
        String returnType =
                types.getTypeDescriptor(
                        invoke.getReturnType()
                );

        code.append("invokevirtual ")
                .append(className)
                .append("/")
                .append(methodName)
                .append("(")
                .append(params)
                .append(")")
                .append(returnType)
                .append(NL);

        int consumed = invoke.getArguments().size() + 1;
        updateStack(-consumed);

        if (!returnType.equals("V")) {
            updateStack(1);
            // Discard result if the call is not consumed by an assignment
            if (!isInsideAssignment) {
                code.append("pop").append(NL);
                updateStack(-1);
            }
        }

        return code.toString();
    }

    private String generateInvokeStatic(InvokeStaticInstruction invoke) {
        var code = new StringBuilder();

        // Load arguments (no caller object for static)
        for (var arg : invoke.getArguments()) {
            code.append(apply(arg));
        }

        // Caller is the class name
        var caller = (Operand) invoke.getCaller();
        String className = types.getFullClassName(caller.getName());

        String methodName = ((LiteralElement) invoke.getMethodName()).getLiteral().replace("\"", "");
        String params = invoke.getArguments().stream()
                .map(arg -> types.getTypeDescriptor(arg.getType()))
                .collect(Collectors.joining());
        String returnType = types.getTypeDescriptor(invoke.getReturnType());

        code.append("invokestatic ").append(className).append("/").append(methodName)
                .append("(").append(params).append(")").append(returnType).append(NL);

        updateStack(-invoke.getArguments().size());

        if (!returnType.equals("V")) {
            updateStack(1);
            if (!isInsideAssignment) {
                code.append("pop").append(NL);
                updateStack(-1);
            }
        }

        return code.toString();
    }

    private String generateInvokeSpecial(InvokeSpecialInstruction invoke) {
        var code = new StringBuilder();

        // Load caller object
        code.append(apply(invoke.getCaller()));

        // Load arguments
        for (var arg : invoke.getArguments()) {
            code.append(apply(arg));
        }

        // Determine the class to invoke on
        String className;
        var superClass = invoke.getSuperClass();
        if (superClass.isPresent()) {
            // superClass is stored as dotted name like "java.lang.Object"
            className = types.getFullClassName(superClass.get());
        } else {
            className = resolveClassName(invoke.getCaller().getType());
        }

        String methodName = ((LiteralElement) invoke.getMethodName()).getLiteral().replace("\"", "");
        String params = invoke.getArguments().stream()
                .map(arg -> types.getTypeDescriptor(arg.getType()))
                .collect(Collectors.joining());
        String returnType = types.getTypeDescriptor(invoke.getReturnType());

        code.append("invokespecial ").append(className).append("/").append(methodName)
                .append("(").append(params).append(")").append(returnType).append(NL);

        int consumed = invoke.getArguments().size() + 1;
        updateStack(-consumed);

        if (!returnType.equals("V")) {
            updateStack(1);
            if (!isInsideAssignment) {
                code.append("pop").append(NL);
                updateStack(-1);
            }
        }

        return code.toString();
    }

    private String generatePutField(PutFieldInstruction inst) {
        var code = new StringBuilder();
        // Load object reference
        var objReg = currentMethod.getVarTable().get(inst.getObject().getName());
        code.append(types.getLoad(objReg)).append(NL);
        updateStack(1);
        // Load value
        code.append(apply(inst.getValue()));
        // Emit putfield — use field operand's type, not instruction return type
        String className = resolveClassName(inst.getObject().getType());
        String fieldName = inst.getField().getName();
        String fieldType = types.getTypeDescriptor(inst.getField().getType());
        code.append("putfield ").append(className).append("/")
                .append(fieldName).append(" ").append(fieldType).append(NL);
        updateStack(-2);
        return code.toString();
    }

    private String generateGetField(GetFieldInstruction inst) {
        var code = new StringBuilder();
        // Load object reference
        var objReg = currentMethod.getVarTable().get(inst.getObject().getName());
        code.append(types.getLoad(objReg)).append(NL);
        updateStack(1);
        // Emit getfield — use field operand's type, not instruction return type
        String className = resolveClassName(inst.getObject().getType());
        String fieldName = inst.getField().getName();
        String fieldType = types.getTypeDescriptor(inst.getField().getType());
        code.append("getfield ").append(className).append("/")
                .append(fieldName).append(" ").append(fieldType).append(NL);
        // net: -1 (object) +1 (value) = 0 extra (already counted +1 for object load)
        return code.toString();
    }

    // Helper: resolve class name from a Type
    private String resolveClassName(Type type) {
        if (type instanceof ClassType ct) {
            return types.getFullClassName(ct.getName());
        }
        if (type instanceof BuiltinType bt && bt.getKind() == BuiltinKind.STRING) {
            return "java/lang/String";
        }
        return types.getFullClassName(type.getNodeName());
    }


    private String apply(TreeNode node) {
        var code = new StringBuilder();

        // Print the corresponding OLLIR code as a comment
        //code.append("; ").append(node).append(NL);

        code.append(generators.apply(node));

        return code.toString();
    }

    public List<Report> getReports() {
        return reports;
    }

    public String build() {

        // This way, build is idempotent
        if (code == null) {
            // Ensure var tables are initialised. When compiling from JMM, transformOllir
            // may be skipped (e.g. getJasminFromJmm), leaving all virtualReg values at 0.
            // Only rebuild when at least one non-constructor method still has an empty table,
            // so register-allocation results (from transformOllir) are preserved.
            var classUnit = ollirResult.getOllirClass();
            boolean needsBuild = classUnit.getMethods().stream()
                    .filter(m -> !m.isConstructMethod())
                    .anyMatch(m -> m.getVarTable().isEmpty());
            if (needsBuild) {
                classUnit.buildVarTables();
            }

            code = apply(classUnit);
        }

        return code;
    }


    private String generateClassUnit(ClassUnit classUnit) {

        var code = new StringBuilder();

        // generate class name
        var nameWithPackage = ollirResult.getOllirClass().getClassFullyQualifiedName().replace('.', '/');
        code.append(".class public ").append(nameWithPackage).append(NL).append(NL);

        // Resolve super class
        var superClassName = ollirResult.getOllirClass().getSuperClass();
        var fullSuperClass = (superClassName == null || superClassName.isEmpty())
                ? "java/lang/Object"
                : types.getFullClassName(superClassName);

        code.append(".super ").append(fullSuperClass).append(NL);

        // Generate field declarations
        for (var field : ollirResult.getOllirClass().getFields()) {
            var fieldModifier = types.getFieldModifier(field.getFieldAccessModifier());
            var fieldName = field.getFieldName();
            var fieldType = types.getTypeDescriptor(field.getFieldType());
            // Quote field name to avoid conflicts with Jasmin reserved words (e.g., "field")
            code.append(".field ").append(fieldModifier).append(" '")
                    .append(fieldName).append("' ").append(fieldType).append(NL);
        }

        // generate code for all methods, including the constructor if present
        for (var method : ollirResult.getOllirClass().getMethods()) {
            code.append(apply(method));
        }

        // Fallback for class files that do not define a constructor in OLLIR
        if (ollirResult.getOllirClass().getMethods().stream().noneMatch(Method::isConstructMethod)) {
            code.append("""
                    ;default constructor
                    .method public <init>()V
                        aload_0
                        invokespecial %s/<init>()V
                        return
                    .end method
                    """.formatted(fullSuperClass));
        }

        return code.toString();
    }

    private String generateMethod(Method method) {
        //System.out.println("STARTING METHOD " + method.getMethodName());
        // set method
        currentMethod = method;

        currentStack = 0;
        maxStack = 0;
        labelCounter = 0;

        // Initialize utils, to have fresh labels
        utils = new OptUtils(null);

        var code = new StringBuilder();

        var modifier = method.isConstructMethod()
                ? "public "
                : types.getModifier(method.getMethodAccessModifier());


        var staticMod = method.isStaticMethod() ? "static " : "";

        var methodName = method.getMethodName();

        var params = method.getParams().stream()
                .map(p -> types.getTypeDescriptor(p.getType()))
                .collect(Collectors.joining());

        var returnType = types.getTypeDescriptor(method.getReturnType());

        code.append("\n.method ").append(modifier)
                .append(staticMod)
                .append(methodName)
                .append("(" + params + ")" + returnType).append(NL);


        var bodyCode = new StringBuilder();
        for (var inst : method.getInstructions()) {
            // Emit any labels attached to this instruction
            var labels = method.getLabels(inst);
            if (labels != null) {
                for (var label : labels) {
                    bodyCode.append(label).append(":").append(NL);
                }
            }
            var instCode = StringLines.getLines(apply(inst)).stream()
                    .collect(Collectors.joining(NL + TAB, TAB, NL));

            bodyCode.append(instCode);
        }

        // Add limits
        code.append(TAB).append(".limit stack ").append(maxStack).append(NL);
        code.append(TAB).append(".limit locals ").append(computeLocals(method)).append(NL);

        code.append(TAB).append(bodyCode);

        if (method.isConstructMethod()
                && method.getInstructions().stream().noneMatch(ReturnInstruction.class::isInstance)) {
            code.append(TAB).append("return").append(NL);
        }

        code.append(".end method\n");
        // unset method
        currentMethod = null;
        //System.out.println("ENDING METHOD " + method.getMethodName());
        return code.toString();
    }

    private String generateAssign(AssignInstruction assign) {
        try {
            isInsideAssignment = true;

            var code = new StringBuilder();
            var lhs = assign.getDest();

            // Array store: arr[idx] := value
            if (lhs instanceof ArrayOperand arrayOp) {
                var reg = currentMethod.getVarTable().get(arrayOp.getName());
                // load array reference
                code.append(types.getLoad(reg)).append(NL);
                updateStack(1);
                // load index
                for (var idx : arrayOp.getIndexOperands()) {
                    code.append(apply(idx));
                }
                // load value
                code.append(apply(assign.getRhs()));
                // store into array: use aastore for reference types (arrays), iastore for int/bool
                String storeOp = (arrayOp.getType() instanceof ArrayType) ? "aastore" : "iastore";
                code.append(storeOp).append(NL);
                updateStack(-3);
                return code.toString();
            }

            // Regular variable store
            code.append(apply(assign.getRhs()));

            var operand = (Operand) lhs;
            var reg = currentMethod.getVarTable().get(operand.getName());

            code.append(types.getStore(reg)).append(NL);
            updateStack(-1);

            return code.toString();
        } finally {
            isInsideAssignment = false;
        }
    }

    private String generateSingleOp(SingleOpInstruction singleOp) {
        return apply(singleOp.getSingleOperand());
    }

    private String generateLiteral(LiteralElement literal) {
        updateStack(1);
        try {
            int val = Integer.parseInt(literal.getLiteral());
            if (val >= -1 && val <= 5) return "iconst_" + val + NL;
            if (val >= -128 && val <= 127) return "bipush " + val + NL;
            if (val >= -32768 && val <= 32767) return "sipush " + val + NL;
        } catch (NumberFormatException ignored) {}
        return "ldc " + literal.getLiteral() + NL;
    }

    private String generateOperand(Operand operand) {
        // get register
        var reg = currentMethod.getVarTable().get(operand.getName());
        updateStack(1);
        return types.getLoad(reg) + NL;
    }

    private String generateUnaryOp(UnaryOpInstruction unaryOp) {
        var code = new StringBuilder();
        code.append(apply(unaryOp.getOperand()));

        var typePrefix = types.getTypePrefix(unaryOp.getOperation().getTypeInfo());
        var opType = unaryOp.getOperation().getOpType();

        if (opType == OperationType.LOGICAL_NOT){
            code.append(typePrefix).append("const_1").append(NL);
            updateStack(1);
            code.append(typePrefix).append("xor").append(NL);
            updateStack(-1);
        } else {
            throw new NotImplementedException(opType);
        }

        return code.toString();
    }


    private String generateBinaryOp(BinaryOpInstruction binaryOp) {

        var code = new StringBuilder();

        // load values on the left and on the right
        code.append(apply(binaryOp.getLeftOperand()));
        code.append(apply(binaryOp.getRightOperand()));


        var typePrefix = types.getTypePrefix(binaryOp.getOperation().getTypeInfo());

        var opType = binaryOp.getOperation().getOpType();

        // Boolean comparison ops: generate iconst_0/1 via branch
        String cmpBranch = switch (opType) {
            case LTH -> "if_icmplt";
            case LTE -> "if_icmple";
            case GTH -> "if_icmpgt";
            case GTE -> "if_icmpge";
            case EQ  -> "if_icmpeq";
            case NEQ -> "if_icmpne";
            default  -> null;
        };

        if (cmpBranch != null) {
            // Consumed both operands (-2), then push result (1) → net -1
            String lTrue = nextLabel();
            String lEnd  = nextLabel();
            code.append(cmpBranch).append(" ").append(lTrue).append(NL);
            code.append("iconst_0").append(NL);
            code.append("goto ").append(lEnd).append(NL);
            code.append(lTrue).append(":").append(NL);
            code.append("iconst_1").append(NL);
            code.append(lEnd).append(":").append(NL);
            updateStack(-1); // -2 (operands) +1 (result)
            return code.toString();
        }

        // apply arithmetic operation
        var op = switch (opType) {
            case ADD -> "add";
            case MUL -> "mul";
            case SUB -> "sub";
            case DIV -> "div";
            case BITWISE_AND -> "and";
            case BITWISE_OR  -> "or";
            case REM -> "rem";
            default -> throw new NotImplementedException(opType);
        };

        code.append(typePrefix).append(op).append(NL);
        updateStack(-1);

        return code.toString();
    }

    private String generateReturn(ReturnInstruction returnInst) {
        var code = new StringBuilder();

        var returnType = returnInst.getReturnType();

        var typePrefix = types.getTypePrefix(returnType);

        // Load operand into the stack, if present
        returnInst.getOperand().ifPresent(op -> code.append(apply(op)));

        code.append(typePrefix).append("return").append(NL);

        return code.toString();
    }

    private String generateNew(NewInstruction newInst) {
        var code = new StringBuilder();

        var retType = newInst.getReturnType();

        if (retType instanceof ArrayType arrayType) {
            var args = newInst.getArguments();
            if (args.size() > 1) {
                // multidimensional: push all dimension sizes then emit multianewarray
                for (var arg : args) {
                    code.append(apply(arg));
                }
                String descriptor = types.getTypeDescriptor(arrayType);
                code.append("multianewarray ").append(descriptor).append(" ").append(args.size()).append(NL);
                updateStack(-(args.size() - 1)); // args consumed, array ref left on stack
            } else {
                // single-dimension: push size then emit newarray
                code.append(apply(args.get(0)));
                String elemTypeStr = getArrayElementTypeName(arrayType.getElementType());
                code.append("newarray ").append(elemTypeStr).append(NL);
                updateStack(-1); // size consumed, array ref pushed
                updateStack(1);  // array ref
            }
        } else {
            // Object instantiation
            String className = resolveClassName(retType);
            code.append("new ").append(className).append(NL);
            updateStack(1);
        }

        return code.toString();
    }

    private String getArrayElementTypeName(Type elemType) {
        if (elemType instanceof BuiltinType bt) {
            return switch (bt.getKind()) {
                case INT32 -> "int";
                case BOOLEAN -> "boolean";
                default -> "int";
            };
        }
        return "int";
    }

    private String generateArrayLength(ArrayLengthInstruction inst) {
        var code = new StringBuilder();
        // load array reference
        code.append(apply(inst.getCaller()));
        code.append("arraylength").append(NL);
        // net: consumed array ref, pushed length → 0 net (apply already +1, arraylength -1+1=0)
        return code.toString();
    }

    private String generateArrayLoad(ArrayOperand arrayOp) {
        var code = new StringBuilder();
        var reg = currentMethod.getVarTable().get(arrayOp.getName());
        // load array reference
        code.append(types.getLoad(reg)).append(NL);
        updateStack(1);
        // load index (only first index; multidim chains are handled via intermediate temps in OLLIR)
        for (var idx : arrayOp.getIndexOperands()) {
            code.append(apply(idx));
        }
        // load element: use aaload for reference types (arrays), iaload for int/bool
        var elemType = arrayOp.getType();
        String loadOp = (elemType instanceof ArrayType) ? "aaload" : "iaload";
        code.append(loadOp).append(NL);
        updateStack(-1); // consumed array ref + index, pushed element
        return code.toString();
    }

    private String nextLabel() {
        return "_jg_" + (labelCounter++);
    }

    private String generateGoto(GotoInstruction inst) {
        return "goto " + inst.getLabel() + NL;
    }

    private String generateSingleOpCond(SingleOpCondInstruction inst) {
        var code = new StringBuilder();
        // Load the boolean operand
        code.append(apply(inst.getCondition().getSingleOperand()));
        // Branch if non-zero
        code.append("ifne ").append(inst.getLabel()).append(NL);
        updateStack(-1);
        return code.toString();
    }

    private String generateOpCond(OpCondInstruction cond) {
        var code = new StringBuilder();

        var condition = cond.getCondition();

        if (condition instanceof BinaryOpInstruction binaryOp) {
            var opType = binaryOp.getOperation().getOpType();

            // For direct comparison ops, load both operands and branch with if_icmpXX
            String directBranch = switch (opType) {
                case LTH -> "if_icmplt";
                case GTH -> "if_icmpgt";
                case LTE -> "if_icmple";
                case GTE -> "if_icmpge";
                case EQ  -> "if_icmpeq";
                case NEQ -> "if_icmpne";
                default  -> null;
            };

            if (directBranch != null) {
                var right = binaryOp.getRightOperand();
                var left  = binaryOp.getLeftOperand();
                boolean rightIsZero = right instanceof LiteralElement lit
                        && lit.getLiteral().equals("0");
                boolean leftIsZero  = left instanceof LiteralElement lit
                        && lit.getLiteral().equals("0");

                if (rightIsZero) {
                    // compare left against zero
                    String zeroBranch = switch (opType) {
                        case LTH -> "iflt";
                        case GTH -> "ifgt";
                        case LTE -> "ifle";
                        case GTE -> "ifge";
                        case EQ  -> "ifeq";
                        case NEQ -> "ifne";
                        default  -> directBranch;
                    };
                    code.append(apply(left));
                    code.append(zeroBranch).append(" ").append(cond.getLabel()).append(NL);
                    updateStack(-1);
                } else if (leftIsZero) {
                    // flip operands and reverse the comparison
                    String zeroBranch = switch (opType) {
                        case LTH -> "ifgt";  // 0 < x  ↔  x > 0
                        case GTH -> "iflt";  // 0 > x  ↔  x < 0
                        case LTE -> "ifge";  // 0 <= x ↔  x >= 0
                        case GTE -> "ifle";  // 0 >= x ↔  x <= 0
                        case EQ  -> "ifeq";
                        case NEQ -> "ifne";
                        default  -> directBranch;
                    };
                    code.append(apply(right));
                    code.append(zeroBranch).append(" ").append(cond.getLabel()).append(NL);
                    updateStack(-1);
                } else {
                    code.append(apply(left));
                    code.append(apply(right));
                    code.append(directBranch).append(" ").append(cond.getLabel()).append(NL);
                    updateStack(-2);
                }
            } else {
                // LOGICAL_AND, LOGICAL_OR: generate full boolean result then branch if non-zero
                code.append(apply(binaryOp));
                code.append("ifne ").append(cond.getLabel()).append(NL);
                updateStack(-1);
            }

        } else if (condition instanceof UnaryOpInstruction unaryOp) {

            code.append(apply(unaryOp));

            switch (unaryOp.getOperation().getOpType()) {
                case LOGICAL_NOT -> code.append("ifne ");
                default ->
                        throw new NotImplementedException(unaryOp.getOperation().getOpType());
            }
            code.append(cond.getLabel()).append(NL);
            updateStack(-1);

        } else {
            throw new RuntimeException(
                    "Unsupported condition instruction: " + condition.getClass()
            );
        }

        return code.toString();
    }

}