package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class StaticContextVisitor extends AnalysisVisitorWithTable {

    private MethodSymbol currentMethod;

    public StaticContextVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(METHOD_DECL, this::visitMethodDecl);
        addVisit(THIS_LITERAL, this::visitThisLiteral);
        addVisit(VAR_REF_EXPR, this::visitVarRefExpr);
        addVisit(OBJECT_CALL_EXPR, this::visitObjectCallExpr);
    }

    private Void visitMethodDecl(JmmNode method, SymbolTable table) {
        var signature = TypeUtils.with(table).getMethodDeclSignature(method);
        currentMethod = table.getMethod(signature).orElseThrow();
        return null;
    }

    // #5 and #6: reject 'this' (and therefore 'this.method()') inside static methods
    private Void visitThisLiteral(JmmNode thisNode, SymbolTable table) {
        if (currentMethod != null && currentMethod.isStatic()) {
            addReport(newError(thisNode, "Cannot use 'this' in static method '" + currentMethod.name() + "'"));
        }
        return null;
    }

    // #7: reject instance field access inside static methods
    private Void visitVarRefExpr(JmmNode varRef, SymbolTable table) {
        if (currentMethod == null || !currentMethod.isStatic()) return null;

        var name = varRef.get("name");

        // Only reject if the name resolves to a field (not a local, param, or class reference)
        boolean isLocal = currentMethod.getLocalVariable(name).isPresent();
        boolean isParam = currentMethod.getParameter(name).isPresent();
        boolean isField = table.getField(name).isPresent();

        if (!isLocal && !isParam && isField) {
            addReport(newError(varRef,
                    "Cannot access instance field '" + name + "' from static method '" + currentMethod.name() + "'"));
        }
        return null;
    }

    // #4: reject calling a non-static method via a class name
    private Void visitObjectCallExpr(JmmNode callNode, SymbolTable table) {
        var postfixExpr = callNode.getParent();
        if (postfixExpr == null || postfixExpr.getNumChildren() == 0) return null;

        var base = postfixExpr.getChild(0);
        if (!VAR_REF_EXPR.check(base)) return null;

        var baseName = base.get("name");
        String methodName = callNode.get("name");

        // Determine if base is the current class used as a static reference
        boolean isCurrentClass = baseName.equals(table.getClassName());

        // It must NOT be an instance variable for it to be a class-level reference
        boolean isInstanceVar = currentMethod != null && (
                currentMethod.getParameter(baseName).isPresent() ||
                currentMethod.getLocalVariable(baseName).isPresent() ||
                table.getField(baseName).isPresent());

        if (!isCurrentClass || isInstanceVar) return null;

        // base is the current class name used as a static receiver — reject non-static methods
        boolean anyNonStatic = table.getMethods(methodName).stream().anyMatch(m -> !m.isStatic());
        if (anyNonStatic) {
            addReport(newError(callNode,
                    "Cannot call instance method '" + methodName + "' on class '" + baseName + "'"));
        }

        return null;
    }
}
