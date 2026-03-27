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

public class ReturnStatementVisitor extends AnalysisVisitorWithTable{
    private MethodSymbol currentMethod;

    public ReturnStatementVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(METHOD_DECL, this::visitMethodDecl);
        addVisit(RETURN_STMT, this::visitReturn);
    }

    private Void visitMethodDecl(JmmNode method, SymbolTable table) {
        var signature = TypeUtils.with(table).getMethodDeclSignature(method);
        currentMethod = table.getMethod(signature).orElseThrow();

        // If return type not null, return must exist
        if (!currentMethod.returnType().equals(JmmPrimitiveType.VOID)
                && method.getDescendants(RETURN_STMT).isEmpty())
            addReport(Report.newError(
                    Stage.SEMANTIC,
                    NodeUtils.getLine(method),
                    NodeUtils.getColumn(method),
                    "Method '" + method.get("name") + "' must return a value",
                    null
            ));
        return null;
    }

    private Void visitReturn(JmmNode returnNode, SymbolTable table){
        var expr = returnNode.getChild(0);

        var exprType = TypeUtils.with(table).getExprType(expr);
        var returnType = currentMethod.returnType();

        if (exprType.equals(returnType)) return null;

        addReport(newError(returnNode,
                String.format("Return type mismatch: expected '%s' but got '%s'",
                        returnType, exprType)));

        return null;
    }
}
