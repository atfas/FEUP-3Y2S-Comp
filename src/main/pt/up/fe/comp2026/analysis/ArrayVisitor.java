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
        addVisit(ASSIGN_STMT,   this::checkArrayAssign);
        addVisit(POSTFIX_EXPR,  this::checkArrayAccess);
        addVisit(NEW_ARRAY_EXPR, this::checkNewArrayDimensions);
    }

    // Make sure every sized dimension uses an int expression
    private Void checkNewArrayDimensions(JmmNode newArrayExpr, SymbolTable table) {

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

        for (var dim : dims) {
            if (SIZED_ARRAY_DIM.check(dim) && dim.getNumChildren() > 0) {
                var dimExprType = TypeUtils.with(table).getExprType(dim.getChild(0));
                if (!dimExprType.equals(TypeUtils.intType())) {
                    addReport(newError(dim,
                            "Array dimension expression must be int, got '" + dimExprType + "'"));
                }
            }
        }

        boolean hitUnsized = false;
        boolean hitSized   = false;

        for (var dim : dims) {
            if (SIZED_ARRAY_DIM.check(dim)) {
                if (hitUnsized) {
                    addReport(newError(dim,
                            "Sized dimensions must come before unsized ones"));
                    return null;
                }
                hitSized = true;
            } else {
                hitUnsized = true;
            }
        }

        if (!hitSized) {
            addReport(newError(newArrayExpr,
                    "Array creation requires at least one sized dimension"));
        }

        return null;
    }

    // Validate assignments where the left-hand side is an array variable
    private Void checkArrayAssign(JmmNode assign, SymbolTable table) {
        var methodNode = assign.getAncestor(METHOD_DECL).orElseThrow();
        var sig        = TypeUtils.with(table).getMethodDeclSignature(methodNode);
        var method     = table.getMethod(sig).orElseThrow();

        JmmType lhsType = TypeUtils.with(table).getVarType(assign.get("var"), method);

        List<JmmNode> children = assign.getChildren();
        JmmNode rhs     = children.get(children.size() - 1);
        List<JmmNode> indices = children.subList(0, children.size() - 1);

        if (!indices.isEmpty()) {
            checkElementAssign(assign, table, lhsType, indices, rhs);
            return null;
        }

        JmmType rhsType = TypeUtils.with(table).getExprType(rhs);
        if (rhsType == null) return null;

        if (lhsType.isArray() && !rhsType.isArray()) {
            addReport(newError(assign, "Cannot assign a non-array value to an array variable"));
        } else if (!lhsType.isArray() && rhsType.isArray()) {
            addReport(newError(assign, "Cannot assign an array to a non-array variable"));
        } else if (lhsType.isArray() && rhsType.isArray() && !lhsType.equals(rhsType)) {
            addReport(newError(assign,
                    "Array type mismatch: left is '" + lhsType + "', right is '" + rhsType + "'"));
        }

        return null;
    }

    // Walk through index expressions and narrow the type one dimension at a time
    private void checkElementAssign(
            JmmNode assign,
            SymbolTable table,
            JmmType declaredType,
            List<JmmNode> indices,
            JmmNode rhs
    ) {
        if (!declaredType.isArray()) {
            addReport(newError(assign, "Indexed access requires an array type on the left-hand side"));
            return;
        }

        JmmType narrowed = declaredType;

        for (JmmNode idx : indices) {
            var idxType = TypeUtils.with(table).getExprType(idx);
            if (!idxType.equals(TypeUtils.intType())) {
                addReport(newError(idx,
                        "Array index must be int, got '" + idxType + "'"));
            }

            if (narrowed instanceof JmmArrayType at) {
                int left = at.dimension() - 1;
                narrowed = left <= 0 ? at.itemType() : JmmArrayType.of(at.itemType(), left);
            } else {
                addReport(newError(assign, "Too many indices for type '" + narrowed + "'"));
                return;
            }
        }

        var rhsType = TypeUtils.with(table).getExprType(rhs);
        if (!narrowed.equals(rhsType)) {
            addReport(newError(assign,
                    "Type mismatch in array element assignment: expected '"
                            + narrowed + "', got '" + rhsType + "'"));
        }
    }

    // Track the current element type as we walk through chained array accesses
    private Void checkArrayAccess(JmmNode postfix, SymbolTable table) {

        var children = postfix.getChildren();
        if (children.size() <= 1) return null;

        JmmType current = TypeUtils.with(table).getExprType(children.get(0));

        for (int i = 1; i < children.size(); i++) {
            var child = children.get(i);

            if (ARRAY_EXPR.check(child)) {
                var idxExpr = child.getChild(0);
                var idxType = TypeUtils.with(table).getExprType(idxExpr);

                if (!idxType.equals(TypeUtils.intType())) {
                    addReport(newError(idxExpr,
                            "Array index must be int, got '" + idxType + "'"));
                }

                if (current instanceof JmmArrayType at) {
                    int left = at.dimension() - 1;
                    current = left <= 0 ? at.itemType() : JmmArrayType.of(at.itemType(), left);
                } else {
                    addReport(newError(postfix, "Indexed access on a non-array type"));
                    break;
                }
            }
        }

        return null;
    }
}
