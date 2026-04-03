package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
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
        addVisit(ASSIGN_STMT, this::arrayHasNewInit);
    }

    private Void arrayHasNewInit(JmmNode varAssign, SymbolTable table) {
        var methodNode = varAssign.getAncestor(METHOD_DECL).orElseThrow();

        var signature = TypeUtils.with(table).getMethodDeclSignature(methodNode);
        var method = table.getMethod(signature).orElseThrow();

        var varType = TypeUtils.with(table).getVarType(varAssign.get("var"), method);

        var assignType = TypeUtils.with(table).getExprType(varAssign.getChild(0));

        if (varType.isArray() && !assignType.isArray())
            addReport(
                    newError(varAssign,
                            "Array variable must be assigned an array")
            );
        if (!varType.isArray() && assignType.isArray()){
            addReport(
                    newError(varAssign,
                    "Array type cannot be assigned to a non array variable")
            );
        }

        return null;
    }
}
