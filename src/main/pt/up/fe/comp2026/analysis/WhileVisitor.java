package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.symboltable.JmmSymbolTable;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.IF_STMT;
import static pt.up.fe.comp2026.jmm.ast.JmmKind.WHILE_STMT;

public class WhileVisitor extends AnalysisVisitorWithTable{
    public WhileVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(WHILE_STMT, this::visitWhile);
    }

    private Void visitWhile(JmmNode whileNode, SymbolTable table){
        var exp = whileNode.getChild(0);

        if (!TypeUtils.with(table).getExprType(exp).equals(JmmPrimitiveType.BOOLEAN))
            addReport(newError(
                    exp,
                    "While condition must be of boolean type"
            ));

        return null;
    }
}
