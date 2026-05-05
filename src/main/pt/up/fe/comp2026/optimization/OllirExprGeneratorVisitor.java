package pt.up.fe.comp2026.optimization;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.AJmmVisitor;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.AccessType;
import pt.up.fe.comp2026.ast.TypeUtils;

import java.util.stream.Collectors;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

/**
 * Generates OLLIR code from JmmNodes that are expressions.
 */
public class OllirExprGeneratorVisitor extends AJmmVisitor<Void, OllirExprResult> {

    private static final String SPACE = " ";
    private static final String ASSIGN = ":=";
    private final String END_STMT = ";\n";

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

        addVisit(THIS_LITERAL, this::visitThisLiteral);
        addVisit(INTEGER_LITERAL, this::visitInteger);
        addVisit(TRUE_LITERAL, this::visitTrueLiteral);
        addVisit(FALSE_LITERAL, this::visitFalseLiteral);
    }

    private OllirExprResult visitArrayExpr(JmmNode node, Void unused) {
        return visit(node.getChild(0));
    }

    private OllirExprResult visitPostfixExpr(JmmNode node, Void unused) {

        // baseExpr
        var base = visit(node.getChild(0));

        // No postfix → just the base
        if (node.getNumChildren() == 1) {
            return base;
        }

        // Only handling ArrayExpr here (others later)
        JmmNode postfix = node.getChild(1);

        if (postfix.getKind().equals(ARRAY_EXPR)) {

            var index = visit(postfix); // index expression

            StringBuilder computation = new StringBuilder();
            computation.append(base.getComputation());
            computation.append(index.getComputation());

            // Element type of a[i]
            JmmType elemType = TypeUtils.with(table).getExprType(node);
            String elemOllirType = ollirTypes.toOllirType(elemType);

            String temp = ollirTypes.nextTemp() + elemOllirType;

            computation.append(temp)
                    .append(" :=.")
                    .append(elemOllirType.substring(1))
                    .append(" ")
                    .append(base.getCode())
                    .append("[")
                    .append(index.getCode())
                    .append("]")
                    .append(elemOllirType)
                    .append(";\n");

            return new OllirExprResult(temp, computation);
        }

        // Fallback: just return base (field/method later)
        return base;
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

    private OllirExprResult visitVarRef(JmmNode node, Void unused) {

        var id = ollirTypes.sanitizeId(node.get("name"));
        JmmType type = types.getExprType(node);
        String ollirType = ollirTypes.toOllirType(type);


        String code = id + ollirType;

        return new OllirExprResult(code);

    }


}
