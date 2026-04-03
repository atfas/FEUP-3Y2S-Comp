package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.ast.JmmNode;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.IMPORT_DECL;
import static pt.up.fe.comp2026.jmm.ast.JmmKind.CLASS_DECL;

public class ImportValidator extends AnalysisVisitor {

    @Override
    protected void buildVisitor() {
        addVisit(IMPORT_DECL, this::visitImport);
        addVisit(CLASS_DECL, this::visitClassDecl);
    }

    private Void visitImport(JmmNode importNode, SymbolTable table) {
        String path = importNode.get("path");
        String fqn = path.substring(1, path.length() - 1).replace(", ", ".");

        try {
            Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            addReport(newError(importNode,
                    "Imported class '" + fqn + "' does not exist"));
        }

        return null;
    }

    private Void visitClassDecl(JmmNode classDecl, SymbolTable table) {
        System.out.println("visitClassDecl called, attributes: " + classDecl.getAttributes());
        System.out.println("has parent: " + classDecl.hasAttribute("parent"));
        if (!classDecl.hasAttribute("parent")) return null;

        String parent = classDecl.get("parent");

        // Check if parent is explicitly imported
        boolean isImported = table.getImports().stream()
                .anyMatch(imp -> imp.equals(parent) || imp.endsWith("." + parent));
        if (isImported) return null;

        // Check if parent is a java.lang.* class
        try {
            Class.forName("java.lang." + parent);
            return null;
        } catch (ClassNotFoundException ignored) {}

        addReport(newError(classDecl,
                "Parent class '" + parent + "' is not imported"));

        return null;
    }
}