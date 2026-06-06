package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.ast.JmmNode;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.CLASS_DECL;
import static pt.up.fe.comp2026.jmm.ast.JmmKind.METHOD_DECL;

public class OverrideCheckVisitor extends AnalysisVisitorWithTable {

    public OverrideCheckVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(CLASS_DECL, this::checkOverrides);
    }

    private Void checkOverrides(JmmNode classDecl, SymbolTable table) {
        String superName = table.getSuperFullyQualifiedName();
        if (superName == null || superName.isEmpty()
                || superName.equals("Object") || superName.equals("java.lang.Object")) {
            return null;
        }

        var superTableOpt = this.table.getImportedSymbolTable(superName);
        if (superTableOpt.isEmpty()) return null;

        var superTable = superTableOpt.get();

        for (var method : table.getMethods()) {
            for (var parentMethod : superTable.getMethods(method.name())) {
                if (!signaturesMatch(method, parentMethod)) continue;

                if (!method.returnType().equals(parentMethod.returnType())) {
                    var methodNode = classDecl.getChildren(METHOD_DECL).stream()
                            .filter(n -> n.get("name").equals(method.name()))
                            .findFirst()
                            .orElse(classDecl);

                    addReport(newError(methodNode,
                            "'" + method.name() + "' overrides '" + superName + "." + method.name()
                                    + "' with a different return type: parent declares '"
                                    + parentMethod.returnType() + "', child declares '"
                                    + method.returnType() + "'"));
                }
            }
        }
        return null;
    }

    private boolean signaturesMatch(MethodSymbol a, MethodSymbol b) {
        if (a.parameters().size() != b.parameters().size()) return false;
        for (int i = 0; i < a.parameters().size(); i++) {
            if (!a.parameters().get(i).type().equals(b.parameters().get(i).type())) return false;
        }
        return true;
    }
}
