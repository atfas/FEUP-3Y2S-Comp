package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.Stage;
import pt.up.fe.comp2026.ast.NodeUtils;
import pt.up.fe.comp2026.ast.TypeUtils;

import java.util.Optional;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class VariableInitializerVisitor extends AnalysisVisitorWithTable{
    private MethodSymbol currentMethod;

    public VariableInitializerVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(METHOD_DECL, this::visitMethodDecl);
        addVisit(VAR_DECL, this::wrongLiteralInitializer);
        addVisit(ASSIGN_STMT, this::varAssign);
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

    private Void wrongLiteralInitializer(JmmNode declaration, SymbolTable table){
        if (declaration.getChildren().toArray().length < 2) return null;

        var varType = TypeUtils.with(table).convertType(declaration.getChild(0));
        var assignType = TypeUtils.with(table).getExprType(declaration.getChild(1));

        if (!isValidAssign(varType, assignType)){
            addReport(newError(
                    declaration,
                    "Assign type must be the same as the variable type"
            ));
        }
        return null;
    }

    private Void varAssign(JmmNode assign, SymbolTable table){
        var varType = TypeUtils.with(table).getVarType(assign.get("var"), currentMethod);
        var assignType = TypeUtils.with(table).getExprType(assign.getChild(0));

        if (!isValidAssign(varType, assignType)){
            addReport(newError(
                    assign,
                    "Assign type must be the same as the variable type"
            ));
        }
        return null;
    }

    private boolean isExplicitlyImported(JmmClassType classType) {
        String name = classType.name();
        // Allow the current class itself
        if (name.equals(table.getClassName())) return true;
        // Allow explicitly imported classes
        if (table.getImports().stream().anyMatch(imp -> imp.equals(name) || imp.endsWith("." + name))) return true;
        // Allow java.lang.* classes (always available in Java)
        try {
            Class.forName("java.lang." + name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Boolean isValidAssign(JmmType varType, JmmType givenType){
        // If either type is an explicitly imported class, give benefit of the doubt
        if (varType instanceof JmmClassType classType && isExplicitlyImported(classType)) return true;
        if (givenType instanceof JmmClassType classType && isExplicitlyImported(classType)) return true;

        // Primitive types: must match exactly
        if (varType instanceof JmmPrimitiveType && givenType instanceof JmmPrimitiveType) {
            return varType.equals(givenType);
        }

        // Array types: must match exactly (simpler)
        if (varType instanceof JmmArrayType && givenType instanceof JmmArrayType) {
            return varType.equals(givenType);
        }

        // Class types: only allow if both are the same AND at least one is known
        if (varType instanceof JmmClassType && givenType instanceof JmmClassType) {
            if (!isExplicitlyImported((JmmClassType) varType) && !isExplicitlyImported((JmmClassType) givenType)) {
                return false; // neither is imported — reject
            }
            if (varType.equals(givenType)) return true;
            return false;
        }

        return false;
    }
}
