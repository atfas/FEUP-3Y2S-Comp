package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.jmm.ast.JmmAttributes;
import pt.up.fe.comp2026.jmm.ast.JmmKind;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class IfElseVisitor extends AnalysisVisitorWithTable{
    public IfElseVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(IF_STMT, this::visitIF);
    }

    private Void visitIF(JmmNode ifNode, SymbolTable table){
        var exp = ifNode.getChild(0);

        if (!TypeUtils.with(table).getExprType(exp).equals(JmmPrimitiveType.BOOLEAN))
            addReport(newError(
                    exp,
                    "If condition must be of boolean type"
            ));

        return null;
    }
}
