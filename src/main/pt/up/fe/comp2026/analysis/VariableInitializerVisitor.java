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
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
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

        // If this is an array element assignment (arr[i] = val), use element type
        if (assign.getNumChildren() > 1 && varType.isArray()) {
            if (varType instanceof JmmArrayType arrayType) {
                varType = arrayType.itemType();
                // The value being assigned is the last child
                assignType = TypeUtils.with(table).getExprType(assign.getChild(assign.getNumChildren() - 1));
            }
        }

        if (!isValidAssign(varType, assignType)){
            addReport(newError(
                    assign,
                    "Assign type must be the same as the variable type"
            ));
        }
        return null;
    }

    private Boolean isValidAssign(JmmType varType, JmmType givenType){
        System.out.println("=== isValidAssign ===");
        System.out.println("varType: " + varType + " class: " + varType.getClass().getSimpleName());
        System.out.println("givenType: " + givenType + " class: " + givenType.getClass().getSimpleName());

        // If either type is explicitly imported (not java.lang, not 'this')
        if (varType instanceof JmmClassType classType && isExplicitlyImported(classType)
                && !classType.name().equals(table.getClassName())
                && !classType.name().equals(table.getFullyQualifiedName())) return true;
        if (givenType instanceof JmmClassType classType && isExplicitlyImported(classType)
                && !classType.name().equals(table.getClassName())
                && !classType.name().equals(table.getFullyQualifiedName())) return true;
        // Primitive types: must match exactly
        if (varType instanceof JmmPrimitiveType && givenType instanceof JmmPrimitiveType) {
            return varType.equals(givenType);
        }

        // Array types: must match exactly
        if (varType instanceof JmmArrayType && givenType instanceof JmmArrayType) {
            return varType.equals(givenType);
        }

        // Class types
        if (varType instanceof JmmClassType varClass && givenType instanceof JmmClassType givenClass) {
            System.out.println("varClass.name(): " + varClass.name());
            System.out.println("givenClass.name(): " + givenClass.name());
            System.out.println("table.getFullyQualifiedName(): " + table.getFullyQualifiedName());
            System.out.println("table.getClassName(): " + table.getClassName());
            if (varType.equals(givenType)) {
                return isJavaLang(varClass) || varClass.name().equals(table.getClassName());
            }

            // Handle 'this' assigned to a type
            if (givenClass.name().equals(table.getFullyQualifiedName()) ||
                    givenClass.name().equals(table.getClassName())) {
                System.out.println("IN THIS BLOCK");
                System.out.println("varClass.name(): " + varClass.name());
                System.out.println("superName: " + table.getSuper());
                if (varClass.name().equals(table.getClassName()) ||
                        varClass.name().equals(table.getFullyQualifiedName())) return true;

                String superName = table.getSuper();
                if (superName == null || superName.isEmpty()) return false;

                Class<?> varClazz = resolveClass(varClass.name());
                Class<?> superClazz = resolveClass(superName);
                if (varClazz != null && superClazz != null) {
                    return varClazz.isAssignableFrom(superClazz);
                }
                return false;
            }

            // Different types — check subtype via reflection
            Class<?> varClazz = resolveClass(varClass.name());
            Class<?> givenClazz = resolveClass(givenClass.name());
            if (varClazz != null && givenClazz != null) {
                return varClazz.isAssignableFrom(givenClazz);
            }

            return false;
        }

        return false;
    }

    private boolean isExplicitlyImported(JmmClassType classType) {
        String name = classType.name();
        if (name.equals(table.getClassName())) return true;
        return table.getImports().stream()
                .anyMatch(imp -> imp.equals(name) || imp.endsWith("." + name));
    }

    private boolean isJavaLang(JmmClassType classType) {
        try {
            Class.forName("java.lang." + classType.name());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Class<?> resolveClass(String name) {
        // Try explicit import
        var fqn = table.getImports().stream()
                .filter(imp -> imp.equals(name) || imp.endsWith("." + name))
                .findFirst();
        try {
            if (fqn.isPresent()) return Class.forName(fqn.get());
            // Try java.lang
            return Class.forName("java.lang." + name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
