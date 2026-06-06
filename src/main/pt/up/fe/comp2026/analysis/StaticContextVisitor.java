package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class StaticContextVisitor extends AnalysisVisitorWithTable {

    private MethodSymbol activeMethod;

    public StaticContextVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(METHOD_DECL,    this::trackCurrentMethod);
        addVisit(THIS_LITERAL,   this::rejectThisInStatic);
        addVisit(VAR_REF_EXPR,   this::rejectInstanceFieldInStatic);
        addVisit(OBJECT_CALL_EXPR, this::rejectInstanceCallOnClass);
    }

    private Void trackCurrentMethod(JmmNode method, SymbolTable table) {
        var sig = TypeUtils.with(table).getMethodDeclSignature(method);
        activeMethod = table.getMethod(sig).orElseThrow();
        return null;
    }

    // 'this' has no meaning inside a static method
    private Void rejectThisInStatic(JmmNode thisNode, SymbolTable table) {
        if (activeMethod != null && activeMethod.isStatic()) {
            addReport(newError(thisNode,
                    "'this' is not defined inside static method '" + activeMethod.name() + "'"));
        }
        return null;
    }

    // Instance fields belong to objects, not to the class itself
    private Void rejectInstanceFieldInStatic(JmmNode varRef, SymbolTable table) {
        if (activeMethod == null || !activeMethod.isStatic()) return null;

        var varName = varRef.get("name");

        boolean declaredLocally = activeMethod.getLocalVariable(varName).isPresent();
        boolean declaredAsParam = activeMethod.getParameter(varName).isPresent();
        boolean isInstanceField = table.getField(varName).isPresent();

        if (!declaredLocally && !declaredAsParam && isInstanceField) {
            addReport(newError(varRef,
                    "Field '" + varName + "' belongs to an instance and cannot be accessed from static context '"
                            + activeMethod.name() + "'"));
        }
        return null;
    }

    // Calling a non-static method using the class name as receiver is illegal
    private Void rejectInstanceCallOnClass(JmmNode callNode, SymbolTable table) {
        var postfix = callNode.getParent();
        if (postfix == null || postfix.getNumChildren() == 0) return null;

        var receiver = postfix.getChild(0);
        if (!VAR_REF_EXPR.check(receiver)) return null;

        var receiverName = receiver.get("name");
        var calledMethod  = callNode.get("name");

        boolean receiverIsClassName = receiverName.equals(table.getClassName());

        boolean receiverIsVariable = activeMethod != null && (
                activeMethod.getParameter(receiverName).isPresent()
                || activeMethod.getLocalVariable(receiverName).isPresent()
                || table.getField(receiverName).isPresent());

        if (!receiverIsClassName || receiverIsVariable) return null;

        boolean hasNonStaticOverload = table.getMethods(calledMethod).stream()
                .anyMatch(m -> !m.isStatic());

        if (hasNonStaticOverload) {
            addReport(newError(callNode,
                    "'" + calledMethod + "' is an instance method and cannot be called on class '"
                            + receiverName + "'"));
        }

        return null;
    }
}
