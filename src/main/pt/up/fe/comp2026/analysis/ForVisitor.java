package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class ForVisitor extends AnalysisVisitorWithTable {

    public ForVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(FOR_STMT, this::visitFor);
    }

    private Void visitFor(JmmNode forNode, SymbolTable table) {
        // FOR_STMT children: FOR_INIT, FOR_COND, FOR_UPDATE, body
        // FOR_COND wraps the condition expression as its single child
        var condNodes = forNode.getChildren(FOR_COND);
        if (condNodes.isEmpty()) return null;

        var condWrapper = condNodes.get(0);
        if (condWrapper.getNumChildren() == 0) return null;

        var condExpr = condWrapper.getChild(0);
        var condType = TypeUtils.with(table).getExprType(condExpr);

        if (!condType.equals(JmmPrimitiveType.BOOLEAN)) {
            addReport(newError(condExpr, "For condition must be of boolean type"));
        }

        return null;
    }
}