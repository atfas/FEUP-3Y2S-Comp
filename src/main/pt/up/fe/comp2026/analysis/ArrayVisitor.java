package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.Stage;
import pt.up.fe.comp2026.ast.TypeUtils;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class ArrayVisitor extends AnalysisVisitorWithTable{
    public ArrayVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(ASSIGN_STMT, this::arrayAssignChecks);
        addVisit(POSTFIX_EXPR, this::arrayAccessChecks);
        addVisit(NEW_ARRAY_EXPR, this::arraySizeMustBeInt);
    }

    private Void arraySizeMustBeInt(JmmNode newArrayExpr, SymbolTable table) {
        // NEW_ARRAY_EXPR has: type, size_expr
        var children = newArrayExpr.getChildren();
        if (children.size() < 2) return null;

        // The size expression is the last child (not the type)
        var sizeExpr = children.get(children.size() - 1);
        var sizeType = TypeUtils.with(table).getExprType(sizeExpr);

        if (!sizeType.equals(TypeUtils.intType())) {
            addReport(newError(newArrayExpr,
                    "Array size must be of type int, got: " + sizeType));
        }

        return null;
    }

    private Void arrayAssignChecks(JmmNode varAssign, SymbolTable table) {
        var methodNode = varAssign.getAncestor(METHOD_DECL).orElseThrow();
        var signature = TypeUtils.with(table).getMethodDeclSignature(methodNode);
        var method = table.getMethod(signature).orElseThrow();
        var varType = TypeUtils.with(table).getVarType(varAssign.get("var"), method);

        if (varAssign.getNumChildren() > 1) {
            checkArrayElementAssignment(varAssign, table, varType);
            return null;
        }

        var assignType = TypeUtils.with(table).getExprType(varAssign.getChild(0));

        if (varType.isArray() && !assignType.isArray())
            addReport(newError(varAssign, "Array variable must be assigned an array"));
        if (!varType.isArray() && assignType.isArray())
            addReport(newError(varAssign, "Array type cannot be assigned to a non array variable"));

        return null;
    }

    private void checkArrayElementAssignment(JmmNode varAssign, SymbolTable table, JmmType varType) {
        if (!varType.isArray()) {
            addReport(newError(varAssign, "Array access must be done on an array"));
        }

        var currentType = varType;
        int lastIndex = varAssign.getNumChildren() - 1;
        for (int i = 0; i < lastIndex; i++) {
            var indexExpr = varAssign.getChild(i);
            var indexType = TypeUtils.with(table).getExprType(indexExpr);
            if (!indexType.equals(TypeUtils.intType())) {
                addReport(newError(indexExpr, "Array index must be of type int, got: " + indexType));
            }

            if (currentType instanceof JmmArrayType arrayType) {
                currentType = arrayType.itemType();
            } else {
                addReport(newError(varAssign, "Too many array indices for type " + currentType));
                break;
            }
        }
    }

    private Void arrayAccessChecks(JmmNode postfixExpr, SymbolTable table) {
        var children = postfixExpr.getChildren();
        if (children.size() <= 1) return null;

        var currentType = TypeUtils.with(table).getExprType(children.get(0));

        for (int i = 1; i < children.size(); i++) {
            var child = children.get(i);
            if (ARRAY_EXPR.check(child)) {
                var indexExpr = child.getChild(0);
                var indexType = TypeUtils.with(table).getExprType(indexExpr);
                if (!indexType.equals(TypeUtils.intType())) {
                    addReport(newError(indexExpr, "Array index must be of type int, got: " + indexType));
                }

                if (currentType instanceof JmmArrayType arrayType) {
                    currentType = arrayType.itemType();
                } else {
                    addReport(newError(postfixExpr, "Array access must be done on an array"));
                    break;
                }
            }
        }

        return null;
    }
}
