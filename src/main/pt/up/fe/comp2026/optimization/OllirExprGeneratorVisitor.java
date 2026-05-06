package pt.up.fe.comp2026.optimization;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.Signature;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.AJmmVisitor;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ast.JmmNodeImpl;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.ReportType;
import pt.up.fe.comp.jmm.report.Stage;
import pt.up.fe.comp2026.ast.AccessType;
import pt.up.fe.comp2026.ast.TypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

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

        addVisit(THIS_LITERAL, this::visitThisLiteral);
        addVisit(INTEGER_LITERAL, this::visitInteger);
        addVisit(TRUE_LITERAL, this::visitTrueLiteral);
        addVisit(FALSE_LITERAL, this::visitFalseLiteral);
        addVisit(NEW_ARRAY_LITERAL_EXPR, this::visitNewArrayLiteral);
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

        // Allocate object
        computation.append(temp)
                .append(" :=.")
                .append(type.substring(1))
                .append(" new(")
                .append(className)
                .append(")")
                .append(type)
                .append(END_STMT);

        // Call constructor
        computation.append("invokespecial(")
                .append(temp)
                .append(", \"<init>\")")
                .append(".V")
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

        StringBuilder computation = new StringBuilder();
        computation.append(base.getComputation());

        // Collect arguments
        List<OllirExprResult> args = new ArrayList<>();
        if (node.getNumChildren() > 0) {
            for (var argNode : node.getChild(0).getChildren()) {
                var arg = visit(argNode);
                args.add(arg);
                computation.append(arg.getComputation());
            }
        }


        JmmType baseType =
                TypeUtils.with(table).getExprType(node.getParent().getChild(0));

        List<JmmType> argumentTypes = List.of();
        if (node.getNumChildren() == 1 && node.getChild(0).getKind().equals(EXPR_LIST)) {
            argumentTypes = node.getChild(0).getChildren().stream()
                    .map(arg -> TypeUtils.with(table).getExprType(arg))
                    .toList();
        }

        List<JmmType> finalArgumentTypes = argumentTypes;
        MethodSymbol method = table.getMethods().stream()
                .filter(m -> m.name().equals(methodName))
                .filter(m -> m.parameters().size() == finalArgumentTypes.size())
                .findFirst()
                .orElseThrow();


        JmmType retType = method.returnType();
        String ollirRetType = ollirTypes.toOllirType(retType);


        // === VOID method ===
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

            return new OllirExprResult(base.getCode(), computation);
        }

        // === NON-VOID method ===
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

        var size = visit(node.getChild(1));

        computation.append(size.getComputation());

        computation.append(arrayTemp)
                .append(".array")
                .append(type)
                .append(ASSIGN)
                .append(".array")
                .append(type)
                .append(SPACE)
                .append("new(array, ")
                .append(size.getCode())
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

        var id = ollirTypes.sanitizeId(node.get("name"));
        JmmType type = types.getExprType(node);
        String ollirType = ollirTypes.toOllirType(type);


        String code = id + ollirType;

        return new OllirExprResult(code);

    }


}
