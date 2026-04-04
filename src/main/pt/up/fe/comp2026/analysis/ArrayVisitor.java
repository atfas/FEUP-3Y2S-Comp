package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;

import java.util.List;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class ArrayVisitor extends AnalysisVisitorWithTable {

    public ArrayVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(ASSIGN_STMT, this::arrayAssignChecks);
        addVisit(POSTFIX_EXPR, this::arrayAccessChecks);
        addVisit(NEW_ARRAY_EXPR, this::arraySizeChecks);
    }


    private Void arraySizeChecks(JmmNode newArrayExpr, SymbolTable table) {
        var children = newArrayExpr.getChildren();


        List<JmmNode> dims = children.stream()
                .filter(c -> !SIMPLE_TYPE.check(c) && !TYPE.check(c))
                .toList();

        if (dims.isEmpty()) return null; // 1-D handled elsewhere or no dims

        // Check: at least one sized dimension required
        boolean firstHasSize = hasSize(dims.get(0));
        if (!firstHasSize) {
            addReport(newError(newArrayExpr,
                    "At least the first dimension of a new array expression must have a size"));
            return null;
        }

        // Validate: no gap rule — once we see an empty dim, all subsequent must be empty
        boolean seenEmpty = false;
        for (JmmNode dim : dims) {
            boolean dimHasSize = hasSize(dim);
            if (seenEmpty && dimHasSize) {
                addReport(newError(newArrayExpr,
                        "Array dimensions cannot have sizes after an empty dimension (no gaps allowed)"));
                return null;
            }
            if (!dimHasSize) {
                seenEmpty = true;
            }
        }

        // Validate all provided sizes are int
        for (JmmNode dim : dims) {
            if (hasSize(dim) && dim.getNumChildren() > 0) {
                var sizeExpr = dim.getChild(0);
                var sizeType = TypeUtils.with(table).getExprType(sizeExpr);
                if (!sizeType.equals(TypeUtils.intType())) {
                    addReport(newError(dim,
                            "Array size must be of type int, got: " + sizeType));
                }
            }
        }

        return null;
    }

    /** Returns true if this dimension node actually carries a size expression. */
    private boolean hasSize(JmmNode dimNode) {

        return dimNode.getNumChildren() > 0;
    }

    private Void arrayAssignChecks(JmmNode varAssign, SymbolTable table) {
        var methodNode = varAssign.getAncestor(METHOD_DECL).orElseThrow();
        var signature = TypeUtils.with(table).getMethodDeclSignature(methodNode);
        var method = table.getMethod(signature).orElseThrow();
        var varType = TypeUtils.with(table).getVarType(varAssign.get("var"), method);

        if (varAssign.getNumChildren() > 1) {
            // Array element assignment: arr[i][j] = val
            checkArrayElementAssignment(varAssign, table, varType);
            return null;
        }

        var assignType = TypeUtils.with(table).getExprType(varAssign.getChild(0));

        if (varType.isArray() && !assignType.isArray()) {
            addReport(newError(varAssign, "Array variable must be assigned an array"));
        } else if (!varType.isArray() && assignType.isArray()) {
            addReport(newError(varAssign, "Array type cannot be assigned to a non array variable"));
        } else if (varType.isArray() && assignType.isArray()) {
            // Both arrays — types must match exactly (same depth and base type)
            if (!varType.equals(assignType)) {
                addReport(newError(varAssign,
                        "Array type mismatch in assignment: expected " + varType + " but got " + assignType));
            }
        }

        return null;
    }

    private void checkArrayElementAssignment(JmmNode varAssign, SymbolTable table, JmmType varType) {
        if (!varType.isArray()) {
            addReport(newError(varAssign, "Array access must be done on an array"));
            return;
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