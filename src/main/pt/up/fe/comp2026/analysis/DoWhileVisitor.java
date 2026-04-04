package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class DoWhileVisitor extends AnalysisVisitorWithTable{
    public DoWhileVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(DO_STMT, this::visitDoWhile);
    }

    private Void visitDoWhile(JmmNode doWhileNode, SymbolTable table){
        var exp = doWhileNode.getChild(1);

        if (!TypeUtils.with(table).getExprType(exp).equals(JmmPrimitiveType.BOOLEAN))
            addReport(newError(
                    exp,
                    "While condition must be of boolean type"
            ));

        return null;
    }
}
