package pt.up.fe.comp2026.analysis;

import com.sun.jdi.PrimitiveType;
import com.sun.jdi.VoidType;
import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.Stage;
import pt.up.fe.comp2026.ast.NodeUtils;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.jmm.ast.JmmAttributes;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.METHOD_DECL;
import static pt.up.fe.comp2026.jmm.ast.JmmKind.RETURN_STMT;

public class ReturnStatementVisitor extends AnalysisVisitorWithTable {

    private MethodSymbol activeMethod;

    public ReturnStatementVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(METHOD_DECL, this::trackActiveMethod);
        addVisit(RETURN_STMT, this::checkReturn);
    }

    private Void trackActiveMethod(JmmNode method, SymbolTable table) {
        var sig = TypeUtils.with(table).getMethodDeclSignature(method);
        activeMethod = table.getMethod(sig).orElseThrow();

        // Non-void methods must have at least one return statement
        if (!activeMethod.returnType().equals(JmmPrimitiveType.VOID)
                && method.getDescendants(RETURN_STMT).isEmpty()) {
            addReport(Report.newError(
                    Stage.SEMANTIC,
                    NodeUtils.getLine(method),
                    NodeUtils.getColumn(method),
                    "Method '" + method.get("name") + "' has a non-void return type but never returns",
                    null
            ));
        }
        return null;
    }

    private Void checkReturn(JmmNode returnNode, SymbolTable table) {
        boolean returnsVoid = activeMethod.returnType().equals(JmmPrimitiveType.VOID);

        // Bare 'return;' — only valid in void methods
        if (returnNode.getNumChildren() == 0) {
            if (!returnsVoid) {
                addReport(newError(returnNode,
                        "Non-void method '" + activeMethod.name() + "' must return a value"));
            }
            return null;
        }

        // 'return <expr>;'
        var returnedExpr  = returnNode.getChild(0);
        var returnedType  = TypeUtils.with(table).getExprType(returnedExpr);
        var declaredType  = activeMethod.returnType();

        if (returnsVoid) {
            addReport(newError(returnNode,
                    "Void method '" + activeMethod.name() + "' cannot return a value"));
            return null;
        }

        if (!returnedType.equals(declaredType)
                && !TypeUtils.with(table).isAssignable(returnedType, declaredType)) {
            addReport(newError(returnNode,
                    "Return type mismatch in '" + activeMethod.name()
                            + "': declared '" + declaredType + "', got '" + returnedType + "'"));
        }

        return null;
    }
}
