package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;
import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class UndeclaredVariable extends AnalysisVisitorWithTable{
    private MethodSymbol currentMethod;

    public UndeclaredVariable(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(METHOD_DECL, this::visitMethodDecl);
        addVisit(VAR_REF_EXPR, this::visitVarRefExpr);
    }

    private Void visitMethodDecl(JmmNode method, SymbolTable table) {
        var signature = TypeUtils.with(table).getMethodDeclSignature(method);
        currentMethod = table.getMethod(signature).orElseThrow();
        return null;
    }

    private Void visitVarRefExpr(JmmNode varRefExpr, SymbolTable table) {
        var name = varRefExpr.get("name");

        if (currentMethod.getParameter(name).isPresent()) return null;
        if (currentMethod.getLocalVariable(name).isPresent()) return null;

        addReport(newError(varRefExpr,
                String.format("Variable '%s' does not exist.", name)));

        return null;
    }

}
