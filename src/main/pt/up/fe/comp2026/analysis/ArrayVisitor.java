package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
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

    // ---------------------------
    // NEW ARRAY CREATION CHECKS
    // ---------------------------
    private Void arraySizeChecks(JmmNode newArrayExpr, SymbolTable table) {

        List<JmmNode> dims = new ArrayList<>();

        var arrayTypeNodes = newArrayExpr.getChildren(ARRAY_TYPE);
        if (!arrayTypeNodes.isEmpty()) {
            arrayTypeNodes.getFirst().getChildren().stream()
                    .filter(c -> SIZED_ARRAY_DIM.check(c) || UNSIZED_ARRAY_DIM.check(c))
                    .forEach(dims::add);
        }

        newArrayExpr.getChildren().stream()
                .filter(c -> SIZED_ARRAY_DIM.check(c) || UNSIZED_ARRAY_DIM.check(c))
                .forEach(dims::add);

        // Validate sizes are int
        for (var dim : dims) {
            if (SIZED_ARRAY_DIM.check(dim) && dim.getNumChildren() > 0) {
                var sizeType = TypeUtils.with(table).getExprType(dim.getChild(0));
                if (!sizeType.equals(TypeUtils.intType())) {
                    addReport(newError(dim,
                            "Array size must be of type int, got: " + sizeType));
                }
            }
        }

        boolean seenUnsizedDim = false;
        boolean seenSizedDim = false;

        for (var dim : dims) {
            if (SIZED_ARRAY_DIM.check(dim)) {
                if (seenUnsizedDim) {
                    addReport(newError(dim,
                            "Array sizes must be specified from the first dimension only"));
                    return null;
                }

                seenSizedDim = true;
            } else {
                seenUnsizedDim = true;
            }
        }

        if (!seenSizedDim) {
            addReport(newError(newArrayExpr,
                    "Array initialization must specify at least one size"));
        }

        return null;
    }

    // ---------------------------
    // ASSIGNMENT CHECKS (FIXED)
    // ---------------------------
    private Void arrayAssignChecks(JmmNode assign, SymbolTable table) {
        var methodNode = assign.getAncestor(METHOD_DECL).orElseThrow();
        var signature = TypeUtils.with(table).getMethodDeclSignature(methodNode);
        var method = table.getMethod(signature).orElseThrow();

        JmmType baseType =
                TypeUtils.with(table).getVarType(assign.get("var"), method);

        List<JmmNode> children = assign.getChildren();

        JmmNode rhs = children.get(children.size() - 1);
        List<JmmNode> indices = children.subList(0, children.size() - 1);

        // -------------------------
        // ARRAY ELEMENT ASSIGNMENT
        // -------------------------
        if (!indices.isEmpty()) {
            checkArrayElementAssignment(assign, table, baseType, indices, rhs);
            return null;
        }

        // -------------------------
        // SIMPLE ASSIGNMENT
        // -------------------------
        JmmType assignType =
                TypeUtils.with(table).getExprType(rhs);

        if (assignType == null) return null;

        if (baseType.isArray() && !assignType.isArray()) {
            addReport(newError(assign,
                    "Array variable must be assigned an array"));
        }
        else if (!baseType.isArray() && assignType.isArray()) {
            addReport(newError(assign,
                    "Array type cannot be assigned to a non array variable"));
        }
        else if (baseType.isArray() && assignType.isArray()) {
            if (!baseType.equals(assignType)) {
                addReport(newError(assign,
                        "Array type mismatch in assignment: expected "
                                + baseType + " but got " + assignType));
            }
        }

        return null;
    }

    // ---------------------------
    // FIXED ARRAY ELEMENT LOGIC
    // ---------------------------
    private void checkArrayElementAssignment(
            JmmNode assign,
            SymbolTable table,
            JmmType varType,
            List<JmmNode> indices,
            JmmNode rhs
    ) {

        if (!varType.isArray()) {
            addReport(newError(assign, "Array access must be done on an array"));
            return;
        }

        JmmType currentType = varType;

        // reduce dimension per index
        for (JmmNode indexExpr : indices) {

            var indexType = TypeUtils.with(table).getExprType(indexExpr);

            if (!indexType.equals(TypeUtils.intType())) {
                addReport(newError(indexExpr,
                        "Array index must be of type int, got: " + indexType));
            }

            if (currentType instanceof JmmArrayType arrayType) {
                int remaining = arrayType.dimension() - 1;

                currentType = (remaining <= 0)
                        ? arrayType.itemType()
                        : JmmArrayType.of(arrayType.itemType(), remaining);
            }
            else {
                addReport(newError(assign,
                        "Too many array indices for type " + currentType));
                return;
            }
        }

        // FINAL CRITICAL CHECK:
        // RHS must match final element type
        var rhsType = TypeUtils.with(table).getExprType(rhs);

        if (!currentType.equals(rhsType)) {
            addReport(newError(assign,
                    "Type mismatch: expected " + currentType +
                            " but got " + rhsType));
        }
    }

    // ---------------------------
    // ARRAY ACCESS CHECKS
    // ---------------------------
    private Void arrayAccessChecks(JmmNode postfixExpr, SymbolTable table) {

        var children = postfixExpr.getChildren();
        if (children.size() <= 1) return null;

        JmmType currentType =
                TypeUtils.with(table).getExprType(children.get(0));

        for (int i = 1; i < children.size(); i++) {

            var child = children.get(i);

            if (ARRAY_EXPR.check(child)) {

                var indexExpr = child.getChild(0);
                var indexType = TypeUtils.with(table).getExprType(indexExpr);

                if (!indexType.equals(TypeUtils.intType())) {
                    addReport(newError(indexExpr,
                            "Array index must be of type int, got: " + indexType));
                }

                if (currentType instanceof JmmArrayType arrayType) {
                    int remaining = arrayType.dimension() - 1;

                    currentType = (remaining <= 0)
                            ? arrayType.itemType()
                            : JmmArrayType.of(arrayType.itemType(), remaining);
                }
                else {
                    addReport(newError(postfixExpr,
                            "Array access must be done on an array"));
                    break;
                }
            }
        }

        return null;
    }
}