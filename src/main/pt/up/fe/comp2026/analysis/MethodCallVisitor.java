package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.Signature;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.symboltable.JmmSymbolTable;

import java.util.ArrayList;
import java.util.List;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class MethodCallVisitor extends AnalysisVisitorWithTable {

    public MethodCallVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(OBJECT_CALL_EXPR, this::visitObjectCallExpr);
        addVisit(CALL_EXPR, this::visitCallExpr);
        addVisit(NEW_OBJECT_EXPR, this::visitNewObjectExpr);
    }

    private List<JmmType> collectArgTypes(JmmNode callNode, SymbolTable table) {
        List<JmmType> argTypes = new ArrayList<>();
        for (var child : callNode.getChildren()) {
            if (EXPR_LIST.check(child)) {
                for (var arg : child.getChildren()) {
                    argTypes.add(TypeUtils.with(table).getExprType(arg));
                }
            }
        }
        return argTypes;
    }

    private Void visitObjectCallExpr(JmmNode callNode, SymbolTable table) {
        var postfixExpr = callNode.getParent();
        var base = postfixExpr.getChild(0);
        String methodName = callNode.get("name");
        List<JmmType> argTypes = collectArgTypes(callNode, table);

        if (THIS_LITERAL.check(base)) {
            validateImplicitOrThisCall(callNode, table, methodName, argTypes);
            return null;
        }

        JmmType callerType = TypeUtils.with(table).getExprType(base);
        if (!(callerType instanceof JmmClassType classType)) return null;

        String callerClassName = classType.name();

        var importedTableOpt = this.table.getImportedSymbolTable(callerClassName);
        if (importedTableOpt.isEmpty()) {
            // Try Java reflection for JDK/classpath classes
            tryReflectionValidation(callNode, table, callerClassName, methodName, argTypes);
            return null;
        }

        var importedTable = importedTableOpt.get();
        var candidates = importedTable.getMethods(methodName);

        if (candidates.isEmpty()) {
            addReport(newError(callNode,
                    "Method '" + methodName + "' not found in class '" + callerClassName + "'"));
            return null;
        }

        boolean anyMatch = candidates.stream().anyMatch(candidate ->
                typesMatch(candidate.parameters().stream()
                        .map(p -> p.type()).toList(), argTypes));

        if (!anyMatch) {
            addReport(newError(callNode,
                    "No matching overload of '" + methodName + "' in class '" + callerClassName
                            + "' for argument types " + argTypes));
        }

        return null;
    }


    private Void visitCallExpr(JmmNode callNode, SymbolTable table) {
        String methodName = callNode.get("value");
        List<JmmType> argTypes = collectArgTypes(callNode, table);
        validateImplicitOrThisCall(callNode, table, methodName, argTypes);
        return null;
    }

    private void validateImplicitOrThisCall(JmmNode callNode, SymbolTable table,
                                            String methodName, List<JmmType> argTypes) {
        // Check current class first (exact signature match)
        var candidates = table.getMethods(methodName);

        if (!candidates.isEmpty()) {
            boolean anyMatch = candidates.stream().anyMatch(candidate ->
                    typesMatch(candidate.parameters().stream()
                            .map(p -> p.type()).toList(), argTypes));
            if (!anyMatch) {
                addReport(newError(callNode,
                        "No matching overload of '" + methodName + "' in class '"
                                + table.getClassName() + "' for argument types " + argTypes));
            }
            return;
        }

        // Not in current class — walk the super hierarchy
        SearchResult result = methodExistsInHierarchy(table, methodName);
        if (result == SearchResult.NOT_FOUND) {
            addReport(newError(callNode,
                    "Cannot call method '" + methodName + "': not defined in class '"
                            + table.getClassName() + "'"));
        }
    }


    private Void visitNewObjectExpr(JmmNode newObjExpr, SymbolTable table) {
        // Collect arg types from EXPR_LIST children of the NEW_OBJECT_EXPR
        List<JmmType> argTypes = collectArgTypes(newObjExpr, table);

        // Determine the class being constructed
        var simpleTypeNodes = newObjExpr.getChildren(SIMPLE_TYPE);
        if (simpleTypeNodes.isEmpty()) return null;

        String className = simpleTypeNodes.get(0).get("name");

        // Look up the symbol table for that class
        var classTableOpt = this.table.getImportedSymbolTable(className);
        if (classTableOpt.isEmpty()) {
            // Try reflection
            tryReflectionConstructorValidation(newObjExpr, className, argTypes);
            return null;
        }

        var classTable = classTableOpt.get();
        // Constructors are represented as methods named the same as the class
        var constructors = classTable.getMethods(className);
        if (constructors.isEmpty()) {
            // No explicit constructor info — give benefit of the doubt
            return null;
        }

        boolean anyMatch = constructors.stream().anyMatch(ctor ->
                typesMatch(ctor.parameters().stream().map(p -> p.type()).toList(), argTypes));

        if (!anyMatch) {
            addReport(newError(newObjExpr,
                    "No matching constructor for '" + className
                            + "' with argument types " + argTypes));
        }

        return null;
    }

    private boolean typesMatch(List<JmmType> paramTypes, List<JmmType> argTypes) {
        if (paramTypes.size() != argTypes.size()) return false;
        for (int i = 0; i < paramTypes.size(); i++) {
            if (!paramTypes.get(i).equals(argTypes.get(i))) return false;
        }
        return true;
    }

    private void tryReflectionValidation(JmmNode callNode, SymbolTable table,
                                         String callerClassName, String methodName,
                                         List<JmmType> argTypes) {
        try {
            String fullyQualified = table.getImports().stream()
                    .filter(imp -> imp.endsWith("." + callerClassName) || imp.equals(callerClassName))
                    .findFirst()
                    .orElse(callerClassName);

            Class<?> clazz = Class.forName(fullyQualified);

            boolean anyReflectionMatch = java.util.Arrays.stream(clazz.getMethods())
                    .filter(m -> m.getName().equals(methodName))
                    .anyMatch(m -> {
                        var params = m.getParameterTypes();
                        if (params.length != argTypes.size()) return false;
                        for (int i = 0; i < params.length; i++) {
                            if (params[i].isArray()) return true;
                            String paramName = params[i].getSimpleName().toLowerCase();
                            String argName = argTypes.get(i).toString().toLowerCase();
                            if (!paramName.equals(argName)) return false;
                        }
                        return true;
                    });

            if (!anyReflectionMatch) {
                addReport(newError(callNode,
                        "No matching overload of '" + methodName + "' in class '" + callerClassName
                                + "' for argument types " + argTypes));
            }
        } catch (ClassNotFoundException e) {
            // Truly unresolvable — give benefit of the doubt
        }
    }

    private void tryReflectionConstructorValidation(JmmNode node, String className,
                                                    List<JmmType> argTypes) {
        try {
            // Try to find via imports
            String fqn = this.table.getImports().stream()
                    .filter(imp -> imp.equals(className) || imp.endsWith("." + className))
                    .findFirst()
                    .orElse(className);

            Class<?> clazz = Class.forName(fqn);
            var ctors = clazz.getConstructors();

            boolean anyMatch = java.util.Arrays.stream(ctors).anyMatch(ctor -> {
                var params = ctor.getParameterTypes();
                if (params.length != argTypes.size()) return false;
                for (int i = 0; i < params.length; i++) {
                    String paramName = params[i].getSimpleName().toLowerCase();
                    String argName = argTypes.get(i).toString().toLowerCase();
                    if (!paramName.equals(argName)) return false;
                }
                return true;
            });

            if (!anyMatch) {
                addReport(newError(node,
                        "No matching constructor for '" + className
                                + "' with argument types " + argTypes));
            }
        } catch (ClassNotFoundException e) {
            // Can't resolve — give benefit of the doubt
        }
    }

    private enum SearchResult { FOUND, NOT_FOUND, UNRESOLVABLE }

    private SearchResult methodExistsInHierarchy(SymbolTable currentTable, String methodName) {
        if (!currentTable.getMethods(methodName).isEmpty()) return SearchResult.FOUND;

        String superName = currentTable.getSuperFullyQualifiedName();
        if (superName == null || superName.isEmpty()) return SearchResult.NOT_FOUND;

        try {
            Class<?> superClass = Class.forName("java.lang." + superName);
            boolean methodExists = java.util.Arrays.stream(superClass.getMethods())
                    .anyMatch(m -> m.getName().equals(methodName));
            return methodExists ? SearchResult.FOUND : SearchResult.NOT_FOUND;
        } catch (ClassNotFoundException ignored) {}

        var parentTableOpt = this.table.getImportedSymbolTable(superName);
        if (parentTableOpt.isEmpty()) return SearchResult.UNRESOLVABLE;

        return methodExistsInHierarchy(parentTableOpt.get(), methodName);
    }
}