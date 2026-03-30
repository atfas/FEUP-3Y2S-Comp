package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.jmm.ast.JmmAttributes;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.VAR_DECL;

public class VariableInitializerVisitor extends AnalysisVisitorWithTable{
    public VariableInitializerVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(VAR_DECL, this::wrongLiteralInitializer);
    }

    private Void wrongLiteralInitializer(JmmNode declaration, SymbolTable table){
        if (declaration.getChildren().toArray().length < 2) return null;
        
        var varType = TypeUtils.with(table).convertType(declaration.getChild(0));
        var assignType = TypeUtils.with(table).getExprType(declaration.getChild(1));

        if (!varType.equals(assignType)){
            addReport(newError(
                    declaration,
                    "Assign type must be the same as the variable type"
            ));
        }
        return null;
    }
}
