package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.NodeUtils;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.jmm.ast.JmmAttributes;

import java.util.HashSet;
import java.util.Set;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class OperatorVisitor extends AnalysisVisitorWithTable {

    private static final Set<String> LOGIC_OPERATORS = Set.of("&&", "||");
    private static final Set<String> ARITHMETIC_OPERATORS = Set.of("+", "-", "*", "/");
    private static final Set<String> COMPARISON_OPERATORS = Set.of("<", ">");

    public OperatorVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(BINARY_EXPR, this::visitBinaryExpr);
        addVisit(UNARY_EXPR, this::visitUnaryExpr);
        addVisit(NOT_EXPR, this::visitUnaryExpr);
    }

    private Void visitBinaryExpr(JmmNode exprNode, SymbolTable table) {

        JmmType leftType = TypeUtils.with(table).getExprType(exprNode.getChild(0));
        JmmType rightType = TypeUtils.with(table).getExprType(exprNode.getChild(1));
        String op = exprNode.get("op");

        // Arithmetic operators: int op int -> int
        if (ARITHMETIC_OPERATORS.contains(op)) {
            if (!leftType.equals(TypeUtils.intType()) || !rightType.equals(TypeUtils.intType())) {
                addReport(newError(exprNode,
                        "Arithmetic operators require integer operands"));
            }
        }

        // Logical operators: boolean op boolean -> boolean
        else if (LOGIC_OPERATORS.contains(op)) {
            if (!leftType.equals(TypeUtils.boolType()) || !rightType.equals(TypeUtils.boolType())) {
                addReport(newError(exprNode,
                        "Logical operators require boolean operands"));
            }
        }

        // Comparison operators: int op int -> boolean
        else if (COMPARISON_OPERATORS.contains(op)) {
            if (!leftType.equals(TypeUtils.intType()) || !rightType.equals(TypeUtils.intType())) {
                addReport(newError(exprNode,
                        "Comparison operators require integer operands"));
            }
        }

        return null;
    }

    private Void visitUnaryExpr(JmmNode exprNode, SymbolTable table) {

        JmmType exprType = TypeUtils.with(table).getExprType(exprNode.getChild(0));

        if (!exprType.equals(TypeUtils.boolType())) {
            addReport(newError(exprNode,
                    "Logical NOT operator requires a boolean operand"));
        }

        return null;
    }
}
