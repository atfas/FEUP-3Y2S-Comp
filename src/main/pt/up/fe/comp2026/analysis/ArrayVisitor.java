package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;

import java.util.ArrayList;
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
        // Collect all dims in order: first from ARRAY_TYPE child, then from NEW_ARRAY_EXPR directly
        List<JmmNode> dims = new ArrayList<>();

        var arrayTypeNodes = newArrayExpr.getChildren(ARRAY_TYPE);
        if (!arrayTypeNodes.isEmpty()) {
            arrayTypeNodes.get(0).getChildren().stream()
                    .filter(c -> SIZED_ARRAY_DIM.check(c) || UNSIZED_ARRAY_DIM.check(c))
                    .forEach(dims::add);
        }

        newArrayExpr.getChildren().stream()
                .filter(c -> SIZED_ARRAY_DIM.check(c) || UNSIZED_ARRAY_DIM.check(c))
                .forEach(dims::add);

        // Also handle old-style SIMPLE_TYPE + PRIMARY_EXPR children
        var simpleTypeNodes = newArrayExpr.getChildren(SIMPLE_TYPE);
        if (!simpleTypeNodes.isEmpty() && dims.isEmpty()) {
            // Old grammar style - just validate size is int
            var sizeNodes = newArrayExpr.getChildren().stream()
                    .filter(c -> !SIMPLE_TYPE.check(c))
                    .toList();
            for (var sizeExpr : sizeNodes) {
                var sizeType = TypeUtils.with(table).getExprType(sizeExpr);
                if (!sizeType.equals(TypeUtils.intType())) {
                    addReport(newError(newArrayExpr, "Array size must be of type int, got: " + sizeType));
                }
            }
            return null;
        }

        if (dims.isEmpty()) return null;

        // First dim must be sized
        if (!SIZED_ARRAY_DIM.check(dims.get(0))) {
            addReport(newError(newArrayExpr,
                    "At least the first dimension of a new array expression must have a size"));
            return null;
        }

        // No gap rule
        boolean seenEmpty = false;
        for (JmmNode dim : dims) {
            boolean sized = SIZED_ARRAY_DIM.check(dim);
            if (seenEmpty && sized) {
                addReport(newError(newArrayExpr,
                        "Array dimensions cannot have sizes after an empty dimension"));
                return null;
            }
            if (!sized) seenEmpty = true;
        }

        // Validate all sized dims have int size
        for (JmmNode dim : dims) {
            if (SIZED_ARRAY_DIM.check(dim) && dim.getNumChildren() > 0) {
                var sizeType = TypeUtils.with(table).getExprType(dim.getChild(0));
                if (!sizeType.equals(TypeUtils.intType())) {
                    addReport(newError(dim, "Array size must be of type int, got: " + sizeType));
                }
            }
        }

        return null;
    }

    private boolean isImportedMethodCall(JmmNode expr, SymbolTable table) {
        if (!POSTFIX_EXPR.check(expr)) return false;
        if (expr.getNumChildren() < 2) return false;
        if (!OBJECT_CALL_EXPR.check(expr.getChild(1))) return false;

        JmmNode base = expr.getChild(0);
        if (!VAR_REF_EXPR.check(base)) return false;

        String baseName = base.get("name");
        return table.getImports().stream()
                .anyMatch(imp -> imp.equals(baseName) || imp.endsWith("." + baseName));
    }


    /** Returns true if this dimension node actually carries a size expression. */
    private boolean hasSize(JmmNode dimNode) {

        return dimNode.getNumChildren() > 0;
    }

    private Void arrayAssignChecks(JmmNode varAssign, SymbolTable table) {
        JmmNode rhsExpr = varAssign.getChild(0);

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

        if (assignType == null) return null;
        System.out.println("varType: " + varType + " dim=" + (varType instanceof JmmArrayType at ? at.dimension() : "N/A"));
        System.out.println("assignType: " + assignType + " dim=" + (assignType instanceof JmmArrayType at ? at.dimension() : "N/A"));
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
                int remainingDims = arrayType.dimension() - 1;
                if (remainingDims <= 0) {
                    currentType = arrayType.itemType();          // fully dereferenced
                } else {
                    currentType = JmmArrayType.of(arrayType.itemType(), remainingDims);
                }
            } else {
                addReport(newError(varAssign, "Too many array indices for type " + currentType));
                break;
            }
        }
    }

    private Void arrayAccessChecks(JmmNode postfixExpr, SymbolTable table) {
        var children = postfixExpr.getChildren();
        if (children.size() <= 1) return null;

        JmmType baseType = TypeUtils.with(table).getExprType(postfixExpr.getChild(0));
        if (baseType instanceof JmmClassType cls && cls.isImported()) {
            return null;
        }

        // Handle multi-dim array creation: new int[b][c][d]
        if (NEW_ARRAY_EXPR.check(children.get(0))) {
            // Validate the extra dimension indices
            for (int i = 1; i < children.size(); i++) {
                var child = children.get(i);
                if (ARRAY_EXPR.check(child)) {
                    var indexExpr = child.getChild(0);
                    var indexType = TypeUtils.with(table).getExprType(indexExpr);
                    if (!indexType.equals(TypeUtils.intType())) {
                        addReport(newError(indexExpr,
                                "Array size must be of type int, got: " + indexType));
                    }
                }
            }
            return null;
        }

        // Normal array access checks
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
                    int remaining = arrayType.dimension() - 1;
                    currentType = remaining <= 0
                            ? arrayType.itemType()
                            : JmmArrayType.of(arrayType.itemType(), remaining);
                } else {
                    addReport(newError(postfixExpr, "Array access must be done on an array"));
                    break;
                }
            }
        }

        return null;
    }
}