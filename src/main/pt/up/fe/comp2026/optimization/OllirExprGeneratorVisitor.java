package pt.up.fe.comp2026.optimization;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.Signature;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.AJmmVisitor;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.symboltable.JmmSymbolTable;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.NodeChangeEvent;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

/**
 * Generates OLLIR code from JmmNodes that are expressions.
 */
public class OllirExprGeneratorVisitor extends AJmmVisitor<Void, OllirExprResult> {

    private static final String SPACE = " ";
    private static final String ASSIGN = ":=";
    private final String END_STMT = ";\n";
    private final String END_LABEL = ":\n";

    private final SymbolTable table;

    private final TypeUtils types;
    private final OptUtils ollirTypes;


    public OllirExprGeneratorVisitor(SymbolTable table, OptUtils ollirTypes) {
        this.table = table;
        this.types = new TypeUtils(table);
        this.ollirTypes = ollirTypes; // We need to pass ollirTypes, to ensure labels are unique
    }


    @Override
    protected void buildVisitor() {
        addVisit(VAR_REF_EXPR, this::visitVarRef);
        addVisit(BINARY_EXPR, this::visitBinExpr);
        addVisit(PRIMARY_EXPR, this::defaultVisitExpr);
        addVisit(POSTFIX_EXPR, this::visitPostfixExpr);
        addVisit(ARRAY_EXPR, this::visitArrayExpr);
        addVisit(NEW_ARRAY_EXPR, this::visitNewArrayExpr);
        addVisit(NEW_OBJECT_EXPR, this::visitNewObjectExpr);
        addVisit(SIZED_ARRAY_DIM, this::defaultVisitExpr);
        addVisit(NOT_EXPR, this::visitNotExpr);
        addVisit(UNARY_EXPR, this::visitUnaryExpr);
        addVisit(CALL_EXPR, this::visitCallExpr);

        addVisit(THIS_LITERAL, this::visitThisLiteral);
        addVisit(INTEGER_LITERAL, this::visitInteger);
        addVisit(TRUE_LITERAL, this::visitTrueLiteral);
        addVisit(FALSE_LITERAL, this::visitFalseLiteral);
        addVisit(NEW_ARRAY_LITERAL_EXPR, this::visitNewArrayLiteral);
    }

    private OllirExprResult visitCallExpr(JmmNode node, Void unused) {

        String methodName = node.get("name");

        StringBuilder computation = new StringBuilder();

        // collect args
        List<OllirExprResult> args = new ArrayList<>();

        if (node.getNumChildren() == 1
                && node.getChild(0).getKind().equals(EXPR_LIST)) {

            for (var argNode : node.getChild(0).getChildren()) {
                var arg = visit(argNode);
                args.add(arg);
                computation.append(arg.getComputation());
            }
        }

        // infer return type
        String ollirRetType =
                ollirTypes.toOllirType(
                        TypeUtils.with(table).getExprType(node)
                );

        // ---- VOID CALL ----
        if (ollirRetType.equals(".V")) {

            computation.append("invokevirtual(")
                    .append("this.")
                    .append(table.getClassName())
                    .append(", \"")
                    .append(methodName)
                    .append("\"");

            for (var arg : args) {
                computation.append(", ")
                        .append(arg.getCode());
            }

            computation.append(").V")
                    .append(END_STMT);

            return new OllirExprResult("", computation);
        }

        // ---- NON-VOID CALL ----
        String temp =
                ollirTypes.nextTemp() + ollirRetType;

        computation.append(temp)
                .append(ASSIGN)
                .append(ollirRetType)
                .append(SPACE)
                .append("invokevirtual(")
                .append("this.")
                .append(table.getClassName())
                .append(", \"")
                .append(methodName)
                .append("\"");

        for (var arg : args) {
            computation.append(", ")
                    .append(arg.getCode());
        }

        computation.append(")")
                .append(ollirRetType)
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private OllirExprResult visitNewObjectExpr(JmmNode node, Void unused) {
        String className;

        if (node.hasAttribute("name")) {
            className = node.get("name");
        } else {
            className = node.getChild(0).get("name");
        }

        String type = "." + className;
        String temp = ollirTypes.nextTemp() + type;

        StringBuilder computation = new StringBuilder();
        List<OllirExprResult> args = new ArrayList<>();

        if (node.hasAttribute("name")
                && node.getNumChildren() == 1
                && node.getChild(0).getKind().equals(EXPR_LIST)) {

            for (var argNode : node.getChild(0).getChildren()) {
                var arg = visit(argNode);
                args.add(arg);
                computation.append(arg.getComputation());
            }
        }

        computation.append(temp)
                .append(" :=.")
                .append(type.substring(1))
                .append(" new(")
                .append(className)
                .append(")")
                .append(type)
                .append(END_STMT);

        computation.append("invokespecial(")
                .append(temp)
                .append(", \"<init>\"");

        for (var arg : args) {
            computation.append(", ").append(arg.getCode());
        }

        computation.append(").V")
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }


    private OllirExprResult visitNotExpr(JmmNode node, Void unused) {

        // Visit operand
        var operand = visit(node.getChild(0));

        StringBuilder computation = new StringBuilder();
        computation.append(operand.getComputation());

        // Result temporary
        String temp = ollirTypes.nextTemp() + ".bool";

        // Unary NOT
        computation.append(temp)
                .append(" :=.bool !.bool ")
                .append(operand.getCode())
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private OllirExprResult applyObjectCall(OllirExprResult base, JmmNode node) {
        String methodName = node.get("name");

        // Collect Args
        List<OllirExprResult> args = getMethodArgs(node);
        
        // get return type
        String ollirRetType = getMethodReturnType(node);

        JmmNode baseNode = node.getParent().getChild(0);

        // static imported call ONLY if the VAR_REF refers to an imported class name
        boolean isStaticImportedCall =
                baseNode.getKind().equals(VAR_REF_EXPR) &&
                        table.getImports().stream()
                                .anyMatch(imp ->
                                        imp.equals(baseNode.get("name")) ||
                                                imp.endsWith("." + baseNode.get("name"))
                                );

        boolean isStaticLocalCall =
            baseNode.getKind().equals(VAR_REF_EXPR) &&
                (baseNode.get("name").equals(table.getClassName()) ||
                    baseNode.get("name").equals(table.getFullyQualifiedName()));

        if (isStaticImportedCall || isStaticLocalCall) {
            return emitInvokeStatic(node, methodName, ollirRetType, args);
        }

        return emitInvokeVirtual(base, node, methodName, ollirRetType, args);
    }

    private OllirExprResult emitInvokeStatic(
            JmmNode node,
            String methodName,
            String ollirRetType,
            List<OllirExprResult> args) {

        StringBuilder computation = new StringBuilder();

        // Emit argument computations
        for (var arg : args) {
            computation.append(arg.getComputation());
        }

        // Static calls use the class name, not an object
        String className =
                ((JmmClassType) TypeUtils.with(table)
                        .getExprType(node.getParent().getChild(0)))
                        .name();


        // ---- VOID static call ----
        if (ollirRetType.equals(".V")) {

            computation.append("invokestatic(")
                    .append(className)
                    .append(", \"")
                    .append(methodName)
                    .append("\"");

            for (var arg : args) {
                computation.append(", ").append(arg.getCode());
            }

            computation.append(").V")
                    .append(END_STMT);

            // No value produced
            return new OllirExprResult("", computation);
        }

        // ---- NON‑VOID static call ----
        String temp = ollirTypes.nextTemp() + ollirRetType;

        computation.append(temp)
                .append(ASSIGN)
                .append(ollirRetType)
                .append(SPACE)
                .append("invokestatic(")
                .append(className)
                .append(", \"")
                .append(methodName)
                .append("\"");

        for (var arg : args) {
            computation.append(", ").append(arg.getCode());
        }

        computation.append(")")
                .append(ollirRetType)
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private String getMethodReturnType(JmmNode node) {

        String methodName = node.get("name");

        List<JmmType> args = new ArrayList<>();
        if (node.getNumChildren() == 1 &&
                node.getChild(0).getKind().equals(EXPR_LIST)) {

            for (var argNode : node.getChild(0).getChildren()) {
                args.add(types.getExprType(argNode));
            }
        }

        JmmType baseType =
                TypeUtils.with(table).getExprType(node.getParent().getChild(0));

        // ✅ FIRST: try current class
        Signature signature = new Signature(methodName, args);
        var methodOpt = table.getMethod(signature);
        if (methodOpt.isPresent()) {
            return ollirTypes.toOllirType(methodOpt.get().returnType());
        }

        // ✅ SECOND: imported or superclass → trust semantic analysis
        return ollirTypes.toOllirType(
                TypeUtils.with(table).getExprType(node.getParent())
        );
    }

    private List<OllirExprResult> getMethodArgs(JmmNode node) {
        List<OllirExprResult> args = new ArrayList<>();

        if (node.getNumChildren() == 1 &&
                node.getChild(0).getKind().equals(EXPR_LIST)) {

            for (var argNode : node.getChild(0).getChildren()) {
                var arg = visit(argNode);
                args.add(arg);
            }
        }

        return args;
    }

    private OllirExprResult emitInvokeVirtual(
            OllirExprResult base,
            JmmNode node,
            String methodName,
            String ollirRetType,
            List<OllirExprResult> args
    ) {
        StringBuilder computation = new StringBuilder();

        JmmType receiverType = TypeUtils.with(table).getExprType(node.getParent().getChild(0));
        if (receiverType instanceof JmmClassType classType && classType.isImported()) {
            var symbolTable = (JmmSymbolTable) table;
            final int argCount = args.size();
            var importedTableOpt = symbolTable.getImportedSymbolTable(classType.name())
                .or(() -> symbolTable.getImportedSymbolTable(classType.fullyQualifiedName()));

            if (importedTableOpt.isPresent()) {
                var matchingMethod = importedTableOpt.get().getMethods(methodName).stream()
                .filter(method -> method.parameters().size() == argCount)
                        .findFirst();

                if (matchingMethod.isPresent()) {
                    var expectedTypes = matchingMethod.get().parameters().stream()
                            .map(parameter -> parameter.type())
                            .toList();

                    List<OllirExprResult> coercedArgs = new ArrayList<>();
                    for (int i = 0; i < args.size(); i++) {
                        coercedArgs.add(coerceArgToType(args.get(i), expectedTypes.get(i)));
                    }
                    args = coercedArgs;
                }
            }
        }

        // Include base and argument computations
        computation.append(base.getComputation());
        for (var arg : args) {
            computation.append(arg.getComputation());
        }

        // ---- VOID CALL ----
        if (ollirRetType.equals(".V")) {
            computation.append("invokevirtual(")
                    .append(base.getCode())
                    .append(", \"")
                    .append(methodName)
                    .append("\"");

            for (var arg : args) {
                computation.append(", ").append(arg.getCode());
            }

            computation.append(").V")
                    .append(END_STMT);

            // preserve receiver for chaining
            return new OllirExprResult(base.getCode(), computation);
        }

        // ---- NON-VOID CALL ----
        String temp = ollirTypes.nextTemp() + ollirRetType;

        computation.append(temp)
                .append(ASSIGN)
                .append(ollirRetType)
                .append(SPACE)
                .append("invokevirtual(")
                .append(base.getCode())
                .append(", \"")
                .append(methodName)
                .append("\"");

        for (var arg : args) {
            computation.append(", ").append(arg.getCode());
        }

        computation.append(")")
                .append(ollirRetType)
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private OllirExprResult coerceArgToType(OllirExprResult arg, JmmType targetType) {
        String targetOllirType = ollirTypes.toOllirType(targetType);

        if (arg.getCode().endsWith(targetOllirType)) {
            return arg;
        }

        String temp = ollirTypes.nextTemp() + targetOllirType;
        StringBuilder computation = new StringBuilder(arg.getComputation());

        computation.append(temp)
                .append(ASSIGN)
                .append(targetOllirType)
                .append(SPACE)
                .append(arg.getCode())
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private OllirExprResult applyArrayAccess(OllirExprResult base, JmmNode node) {

        // node is ARRAY_EXPR → child(0) is the index expression
        var index = visit(node.getChild(0));

        StringBuilder computation = new StringBuilder();

        // Compute base and index first
        computation.append(base.getComputation());
        computation.append(index.getComputation());

        // Element type of a[i] → inferred from PostfixExpr
        JmmType elemType = TypeUtils.with(table).getExprType(node.getParent());
        String elemOllirType = ollirTypes.toOllirType(elemType);

        // Temporary to hold array element
        String temp = ollirTypes.nextTemp() + elemOllirType;

        computation.append(temp)
                .append(ASSIGN)
                .append(elemOllirType)
                .append(SPACE)
                .append(base.getCode())
                .append("[")
                .append(index.getCode())
                .append("]")
                .append(elemOllirType)
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private OllirExprResult visitNewArrayExpr(JmmNode node, Void unused) {
        StringBuilder computation = new StringBuilder();
        var arrayTemp = ollirTypes.nextTemp();
        var type = ollirTypes.toOllirType(node.getChild(0));

        List<OllirExprResult> sizes = new ArrayList<>();

        var arrayTypeNodes = node.getChildren(ARRAY_TYPE);
        if (!arrayTypeNodes.isEmpty()) {
            arrayTypeNodes.getFirst().getChildren().stream()
                .filter(c -> SIZED_ARRAY_DIM.check(c) || UNSIZED_ARRAY_DIM.check(c))
                .forEach(dim -> sizes.add(visit(dim.getChild(0))));
        }

        node.getChildren().stream()
            .filter(c -> SIZED_ARRAY_DIM.check(c) || UNSIZED_ARRAY_DIM.check(c))
            .forEach(dim -> sizes.add(visit(dim.getChild(0))));

        for (var size : sizes) {
            computation.append(size.getComputation());
        }

        computation.append(arrayTemp)
                .append(".array")
                .append(type)
                .append(ASSIGN)
                .append(".array")
                .append(type)
                .append(SPACE)
                .append("new(array, ")
            .append(sizes.stream().map(OllirExprResult::getCode).reduce((a, b) -> a + ", " + b).orElse("") )
                .append(").array")
                .append(type)
                .append(END_STMT);

        var code = arrayTemp + ".array" + type;

        return new OllirExprResult(code, computation);
    }

    private OllirExprResult visitArrayExpr(JmmNode node, Void unused) {
        return visit(node.getChild(0));
    }

    private OllirExprResult visitPostfixExpr(JmmNode node, Void unused) {

        // Start with baseExpr
        OllirExprResult result = visit(node.getChild(0));

        // Apply postfixes left-to-right
        for (int i = 1; i < node.getNumChildren(); i++) {
            JmmNode postfix = node.getChild(i);

            switch (postfix.getKind()) {

                case ARRAY_EXPR -> result = applyArrayAccess(result, postfix);

                case OBJECT_CALL_EXPR -> result = applyObjectCall(result, postfix);

                case FIELD_ACCESS_EXPR -> result = applyFieldAccess(result, postfix);

                default -> throw new RuntimeException(
                        "Unsupported postfix: " + postfix.getKind());
            }
        }

        return result;
    }


    private OllirExprResult applyFieldAccess(OllirExprResult base, JmmNode node) {
        String fieldName = node.get("name");

        StringBuilder computation = new StringBuilder();
        computation.append(base.getComputation());

        // ---- SPECIAL CASE: array.length ----
        if (fieldName.equals("length") && base.getCode().contains(".array")) {
            String temp = ollirTypes.nextTemp() + ".i32";

            computation.append(temp)
                    .append(ASSIGN)
                    .append(".i32")
                    .append(SPACE)
                    .append("arraylength(")
                    .append(base.getCode())
                    .append(")")
                    .append(".i32")
                    .append(END_STMT);

            return new OllirExprResult(temp, computation);
        }

        // ---- NORMAL FIELD ACCESS ----
        JmmType fieldType = TypeUtils.with(table).getExprType(node.getParent());
        String fieldOllirType = ollirTypes.toOllirType(fieldType);

        String temp = ollirTypes.nextTemp() + fieldOllirType;

        computation.append(temp)
                .append(ASSIGN)
                .append(fieldOllirType)
                .append(SPACE)
                .append("getfield(")
                .append(base.getCode())
                .append(", ")
                .append(fieldName)
                .append(fieldOllirType)
                .append(")")
                .append(fieldOllirType)
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private OllirExprResult visitFalseLiteral(JmmNode node, Void unused) {
        return new OllirExprResult("0.bool");
    }

    private OllirExprResult visitTrueLiteral(JmmNode node, Void unused) {
        return new OllirExprResult("1.bool");
    }

    private OllirExprResult visitThisLiteral(JmmNode node, Void unused) {
        // Type of "this" is the current class (or its supertype)
        String className = table.getClassName();
        String code = "this." + className;

        return new OllirExprResult(code);
    }

    private OllirExprResult visitNewArrayLiteral(JmmNode node, Void unused) {
        StringBuilder computation = new StringBuilder();
        var arrayTemp = ollirTypes.nextTemp();
        var type = ollirTypes.toOllirType((node.getChild(0)));
        var elements = (node.getChild(1)).getChildren();
        var sizeCode = elements.size() + ".i32";

        // Create array
        computation.append(arrayTemp)
                .append(".array")
                .append(type)
                .append(ASSIGN)
                .append(".array")
                .append(type)
                .append(SPACE)
                .append("new(array, ")
                .append(sizeCode)
                .append(").array")
                .append(type)
                .append(END_STMT);

        for (int i = 0; i < elements.size(); i++) {
            var element = visit(elements.get(i));

            // Evaluate element
            computation.append(element.getComputation());

            // Store element in array position i
            computation.append(arrayTemp)
                    .append(".array")
                    .append(type)
                    .append("[")
                    .append(i)
                    .append(".i32")
                    .append("]")
                    .append(type)
                    .append(SPACE)
                    .append(ASSIGN)
                    .append(type)
                    .append(SPACE)
                    .append(element.getCode())
                    .append(END_STMT);
        }

        var code = arrayTemp + ".array" + type;

        return new OllirExprResult(code, computation);
    }

    private OllirExprResult defaultVisitExpr(JmmNode node, Void unused) {
        return visit(node.getChild(0));
    }

    private OllirExprResult visitInteger(JmmNode node, Void unused) {
        var intType = TypeUtils.intType();
        String ollirIntType = ollirTypes.toOllirType(intType);
        String code = node.get("value") + ollirIntType;
        return new OllirExprResult(code);
    }

    private OllirExprResult visitBinExpr(JmmNode node, Void unused) {
        var op = node.get("op");

        if (op.equals("&&")) return visitShortCircuitAnd(node);
        if (op.equals("||")) return visitShortCircuitOR(node);

        var lhs = visit(node.getChild(0));
        var rhs = visit(node.getChild(1));

        StringBuilder computation = new StringBuilder();

        // code to compute the children
        computation.append(lhs.getComputation());
        computation.append(rhs.getComputation());

        // code to compute self
        JmmType resType = types.getExprType(node);
        String resOllirType = ollirTypes.toOllirType(resType);
        String code = ollirTypes.nextTemp() + resOllirType;

        computation.append(code).append(SPACE)
                .append(ASSIGN).append(resOllirType).append(SPACE)
                .append(lhs.getCode()).append(SPACE);

        JmmType type = types.getExprType(node);
        computation.append(op).append(ollirTypes.toOllirType(type)).append(SPACE)
                .append(rhs.getCode()).append(END_STMT);

        return new OllirExprResult(code, computation);
    }

    private OllirExprResult visitShortCircuitOR(JmmNode node) {

        var lhs = visit(node.getChild(0));
        var rhs = visit(node.getChild(1));

        String res = ollirTypes.nextTemp() + ".bool";
        String trueLabel = ollirTypes.nextTemp("OR_TRUE");
        String endLabel  = ollirTypes.nextTemp("OR_END");

        StringBuilder computation = new StringBuilder();

        // Evaluate LHS
        computation.append(lhs.getComputation());

        // If lhs is true, jump to TRUE branch
        computation.append("if (")
                .append(lhs.getCode())
                .append(") goto ")
                .append(trueLabel)
                .append(END_STMT);

        // LHS is false → evaluate RHS
        computation.append(rhs.getComputation());

        computation.append(res)
                .append(" :=.bool ")
                .append(rhs.getCode())
                .append(END_STMT);

        computation.append("goto ")
                .append(endLabel)
                .append(END_STMT);

        // TRUE branch
        computation.append(trueLabel)
                .append(":\n");

        computation.append(res)
                .append(" :=.bool 1.bool")
                .append(END_STMT);

        // END
        computation.append(endLabel)
                .append(":\n");

        return new OllirExprResult(res, computation);
    }

    private OllirExprResult visitShortCircuitAnd(JmmNode node) {

        var lhs = visit(node.getChild(0));
        var rhs = visit(node.getChild(1));

        String res = ollirTypes.nextTemp() + ".bool";
        String trueLabel = ollirTypes.nextTemp("AND_TRUE");
        String endLabel  = ollirTypes.nextTemp("AND_END");

        StringBuilder computation = new StringBuilder();

        // Evaluate LHS
        computation.append(lhs.getComputation());

        // If lhs is true, jump to TRUE branch
        computation.append("if (")
                .append(lhs.getCode())
                .append(") goto ")
                .append(trueLabel)
                .append(END_STMT);

        // LHS is false → result is false
        computation.append(res)
                .append(" :=.bool 0.bool")
                .append(END_STMT);

        computation.append("goto ")
                .append(endLabel)
                .append(END_STMT);

        // TRUE branch
        computation.append(trueLabel)
                .append(":\n");

        computation.append(rhs.getComputation());

        computation.append(res)
                .append(" :=.bool ")
                .append(rhs.getCode())
                .append(END_STMT);

        // END
        computation.append(endLabel)
                .append(":\n");

        return new OllirExprResult(res, computation);
    }

    private OllirExprResult visitVarRef(JmmNode node, Void unused) {

        var name = node.get("name");
        var id = ollirTypes.sanitizeId(node.get("name"));

        JmmType type = types.getExprType(node);
        String ollirType = ollirTypes.toOllirType(type);


        var methodNode = node.getAncestor(METHOD_DECL).orElseThrow();
        var signature = TypeUtils.with(table).getMethodDeclSignature(methodNode);
        var method = table.getMethod(signature).orElseThrow();

        boolean isLocal = method.getLocalVariable(name).isPresent();
        boolean isParam = method.getParameter(name).isPresent();

        // Locals and parameters
        if (isLocal || isParam) {
            String code = id + ollirType;
            return new OllirExprResult(code);
        }

        // Fields
        String temp = ollirTypes.nextTemp() + ollirType;
        StringBuilder computation = new StringBuilder();

        computation.append(temp)
                .append(ASSIGN)
                .append(ollirType)
                .append(SPACE)
                .append("getfield(")
                .append("this.")
                .append(table.getClassName())
                .append(", ")
                .append(id)
                .append(ollirType)
                .append(")")
                .append(ollirType)
                .append(END_STMT);

        return new OllirExprResult(temp, computation);
    }

    private OllirExprResult visitUnaryExpr(JmmNode node, Void unused) {
        StringBuilder computation = new StringBuilder();
        var operand = visit(node.getChild(0));

        var exprType = TypeUtils.with(table).getExprType(node);
        var ollirType = ollirTypes.toOllirType(exprType);

        String code = operand.getCode();

        switch (node.get("op")) {
            case "+": {
                computation.append(operand.getComputation());
                code = operand.getCode();
                break;
            }

            case "-": {
                var temp = ollirTypes.nextTemp() + ollirType;
                computation.append(operand.getComputation());

                computation.append(temp)
                    .append(ASSIGN)
                    .append(ollirType)
                    .append(SPACE)
                    .append("0")
                    .append(ollirType)
                    .append(SPACE)
                    .append("-")
                    .append(ollirType)
                    .append(SPACE)
                    .append(operand.getCode())
                    .append(END_STMT);

                code = temp;
                break;
            }

            case "++": {
                // Special-case: a[i]++ / ++a[i] should store back into the array slot.
                JmmNode operandNode = node.getChild(0);

                // Search subtree for an ARRAY_EXPR and its enclosing POSTFIX_EXPR
                JmmNode arrayExpr = null;
                JmmNode arrayPostfix = null;

                // BFS search for ARRAY_EXPR
                List<JmmNode> queue = new ArrayList<>();
                queue.add(operandNode);
                while (!queue.isEmpty() && arrayExpr == null) {
                    JmmNode cur = queue.remove(0);
                    if (cur.getKind().equals(ARRAY_EXPR)) {
                        arrayExpr = cur;
                        break;
                    }
                    for (var c : cur.getChildren()) queue.add(c);
                }

                if (arrayExpr != null) {
                    // find enclosing POSTFIX_EXPR (walk up parents)
                    JmmNode p = arrayExpr.getParent();
                    while (p != null && !p.getKind().equals(POSTFIX_EXPR)) p = p.getParent();
                    arrayPostfix = p;
                }

                if (arrayExpr != null && arrayPostfix != null) {
                    // base is first child of postfix, index is child(0) of arrayExpr
                    var baseRes = visit(arrayPostfix.getChild(0));
                    var indexRes = visit(arrayExpr.getChild(0));

                    StringBuilder comp = new StringBuilder();
                    comp.append(baseRes.getComputation());
                    comp.append(indexRes.getComputation());

                    String tempElem = ollirTypes.nextTemp() + ollirType;
                    comp.append(tempElem)
                            .append(ASSIGN)
                            .append(ollirType)
                            .append(SPACE)
                            .append(baseRes.getCode())
                            .append("[")
                            .append(indexRes.getCode())
                            .append("]")
                            .append(ollirType)
                            .append(END_STMT);

                    String tempInc = ollirTypes.nextTemp() + ollirType;
                    comp.append(tempInc)
                            .append(ASSIGN)
                            .append(ollirType)
                            .append(SPACE)
                            .append(tempElem)
                            .append(SPACE)
                            .append("+")
                            .append(ollirType)
                            .append(SPACE)
                            .append("1")
                            .append(ollirType)
                            .append(END_STMT);

                    // store back into array slot
                    comp.append(baseRes.getCode())
                            .append("[")
                            .append(indexRes.getCode())
                            .append("]")
                            .append(ollirType)
                            .append(SPACE)
                            .append(ASSIGN)
                            .append(ollirType)
                            .append(SPACE)
                            .append(tempInc)
                            .append(END_STMT);

                    computation.append(comp);
                    code = tempInc;
                } else {
                    computation.append(operand.getComputation());

                    // Detect if operand corresponds to a field reference so we can store back
                    JmmNode varRefNode = null;
                    List<JmmNode> q = new ArrayList<>();
                    q.add(operandNode);
                    while (!q.isEmpty() && varRefNode == null) {
                        JmmNode cur = q.remove(0);
                        if (cur.getKind().equals(VAR_REF_EXPR)) {
                            varRefNode = cur;
                            break;
                        }
                        for (var c : cur.getChildren()) q.add(c);
                    }

                    boolean emittedPutfield = false;
                    if (varRefNode != null) {
                        var name = varRefNode.get("name");
                        var methodNode = varRefNode.getAncestor(METHOD_DECL).orElseThrow();
                        var signature = TypeUtils.with(table).getMethodDeclSignature(methodNode);
                        var method = table.getMethod(signature).orElseThrow();

                        boolean isLocal = method.getLocalVariable(name).isPresent();
                        boolean isParam = method.getParameter(name).isPresent();

                        if (!isLocal && !isParam) {
                            // It's a field: operand.getComputation() already loaded it into a temp
                            String tempLoaded = operand.getCode();
                            String tempInc = ollirTypes.nextTemp() + ollirType;

                            computation.append(tempInc)
                                    .append(ASSIGN)
                                    .append(ollirType)
                                    .append(SPACE)
                                    .append(tempLoaded)
                                    .append(SPACE)
                                    .append("+")
                                    .append(ollirType)
                                    .append(SPACE)
                                    .append("1")
                                    .append(ollirType)
                                    .append(END_STMT);

                            String id = ollirTypes.sanitizeId(name);
                            JmmType fieldType = TypeUtils.with(table).getExprType(varRefNode);
                            String fieldOllirType = ollirTypes.toOllirType(fieldType);

                            computation.append("putfield(this, ")
                                    .append(id)
                                    .append(fieldOllirType)
                                    .append(", ")
                                    .append(tempInc)
                                    .append(").V")
                                    .append(END_STMT);

                            code = tempInc;
                            emittedPutfield = true;
                        }
                    }

                    if (!emittedPutfield) {
                        computation.append(operand.getCode())
                                .append(ASSIGN)
                                .append(ollirType)
                                .append(SPACE)
                                .append(operand.getCode())
                                .append(SPACE)
                                .append("+")
                                .append(ollirType)
                                .append(SPACE)
                                .append("1")
                                .append(ollirType)
                                .append(END_STMT);

                        code = operand.getCode();
                    }
                }

                break;
            }

            case "--": {
                // Special-case for array decrement, mirror of ++
                JmmNode operandNode = node.getChild(0);

                boolean handledDec = false;

                if (operandNode.getKind().equals(PRIMARY_EXPR) && operandNode.getNumChildren() > 0
                        && operandNode.getChild(0).getKind().equals(POSTFIX_EXPR)) {

                    JmmNode post = operandNode.getChild(0);

                    for (int i = 1; i < post.getNumChildren(); i++) {
                        JmmNode pf = post.getChild(i);
                        if (pf.getKind().equals(ARRAY_EXPR)) {
                            var baseRes = visit(post.getChild(0));
                            var indexRes = visit(pf.getChild(0));

                            StringBuilder comp = new StringBuilder();
                            comp.append(baseRes.getComputation());
                            comp.append(indexRes.getComputation());

                            String tempElem = ollirTypes.nextTemp() + ollirType;
                            comp.append(tempElem)
                                    .append(ASSIGN)
                                    .append(ollirType)
                                    .append(SPACE)
                                    .append(baseRes.getCode())
                                    .append("[")
                                    .append(indexRes.getCode())
                                    .append("]")
                                    .append(ollirType)
                                    .append(END_STMT);

                            String tempDec = ollirTypes.nextTemp() + ollirType;
                            comp.append(tempDec)
                                    .append(ASSIGN)
                                    .append(ollirType)
                                    .append(SPACE)
                                    .append(tempElem)
                                    .append(SPACE)
                                    .append("-")
                                    .append(ollirType)
                                    .append(SPACE)
                                    .append("1")
                                    .append(ollirType)
                                    .append(END_STMT);

                            comp.append(baseRes.getCode())
                                    .append("[")
                                    .append(indexRes.getCode())
                                    .append("]")
                                    .append(ollirType)
                                    .append(SPACE)
                                    .append(ASSIGN)
                                    .append(ollirType)
                                    .append(SPACE)
                                    .append(tempDec)
                                    .append(END_STMT);

                            computation.append(comp);
                            code = tempDec;
                            handledDec = true;
                            break;
                        }
                    }
                }

                if (!handledDec) {
                    computation.append(operand.getComputation());
                    computation.append(operand.getCode())
                            .append(ASSIGN)
                            .append(ollirType)
                            .append(SPACE)
                            .append(operand.getCode())
                            .append(SPACE)
                            .append("-")
                            .append(ollirType)
                            .append(SPACE)
                            .append("1")
                            .append(ollirType)
                            .append(END_STMT);

                    code = operand.getCode();
                }

                break;
            }

            default:
                throw new RuntimeException("Unsupported unary operator: " + node.get("op"));
        }

        return new OllirExprResult(code, computation);
    }

}
