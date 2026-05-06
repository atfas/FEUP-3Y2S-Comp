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
        addVisit(POSTFIX_EXPR, this::visitPostfixExpr);
    }

    private Void visitPostfixExpr(JmmNode postfixExpr, SymbolTable table) {
        // Handles implicit calls: foo() where POSTFIX_EXPR has a single CALL_EXPR child with no name
        if (postfixExpr.getNumChildren() == 1) {
            var child = postfixExpr.getChild(0);
            if (CALL_EXPR.check(child) && child.getOptional("name").isEmpty()) {
                // The method name must be on the POSTFIX_EXPR itself or its VAR_REF parent
                // Check parent PRIMARY_EXPR -> look for name on the POSTFIX_EXPR
                var nameOpt = postfixExpr.getOptional("name");
                if (nameOpt.isEmpty()) {
                    // Try getting it from the CALL_EXPR's attributes list
                    var attrs = child.getAttributes();
                    System.out.println("CALL_EXPR attrs: " + attrs);
                }
            }
        }
        return null;
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

        if (classType.isImported()) {
            return null;
        }


        return null;
    }


    private Void visitCallExpr(JmmNode callNode, SymbolTable table) {
        var nameOpt = callNode.getOptional("name");

        // For standalone implicit calls like fxx(), the name may be on the parent POSTFIX_EXPR
        if (nameOpt.isEmpty()) {
            var parent = callNode.getParent();
            nameOpt = parent.getOptional("name");
        }

        if (nameOpt.isEmpty()) return null;

        String methodName = nameOpt.get();
        List<JmmType> argTypes = collectArgTypes(callNode, table);
        validateImplicitOrThisCall(callNode, table, methodName, argTypes);
        return null;
    }

    private void validateImplicitOrThisCall(JmmNode callNode, SymbolTable table,
                                            String methodName, List<JmmType> argTypes) {
        String superName = table.getSuperFullyQualifiedName();

        List<MethodSymbol> allCandidates = new ArrayList<>(table.getMethods(methodName));

        if (superName != null && !superName.isEmpty()) {
            collectFromHierarchy(superName, methodName, allCandidates);
        }

        if (allCandidates.isEmpty()) {
            if (superName != null && !superName.isEmpty()) {
                var superTableOpt = this.table.getImportedSymbolTable(superName);
                if (superTableOpt.isEmpty()) {
                    // Try reflection to verify the method actually exists in the JDK super hierarchy
                    if (methodExistsInJdkHierarchy(superName, methodName)) return;
                    addReport(newError(callNode,
                            "Cannot call method '" + methodName + "': not defined in class '"
                                    + table.getClassName() + "'"));
                    return;
                }
            }
            addReport(newError(callNode,
                    "Cannot call method '" + methodName + "': not defined in class '"
                            + table.getClassName() + "'"));
            return;
        }

        boolean anyMatch = allCandidates.stream().anyMatch(candidate ->
                typesMatch(candidate.parameters().stream()
                        .map(p -> p.type()).toList(), argTypes));

        if (!anyMatch) {
            if (superName != null && !superName.isEmpty()) {
                var superTableOpt = this.table.getImportedSymbolTable(superName);
                if (superTableOpt.isEmpty()) {
                    if (methodExistsInJdkHierarchy(superName, methodName)) return;
                }
            }
            addReport(newError(callNode,
                    "No matching overload of '" + methodName + "' in class '"
                            + table.getClassName() + "' for argument types " + argTypes));
        }
    }

    private boolean methodExistsInJdkHierarchy(String className, String methodName) {
        // Try java.lang package first, then bare class name
        String[] candidates = { "java.lang." + className, className };
        for (String fqn : candidates) {
            try {
                Class<?> clazz = Class.forName(fqn);
                boolean found = java.util.Arrays.stream(clazz.getMethods())
                        .anyMatch(m -> m.getName().equals(methodName));
                if (found) return true;
            } catch (ClassNotFoundException ignored) {}
        }
        // Also check imports
        var importedFqn = this.table.getImports().stream()
                .filter(imp -> imp.equals(className) || imp.endsWith("." + className))
                .findFirst();
        if (importedFqn.isPresent()) {
            try {
                Class<?> clazz = Class.forName(importedFqn.get());
                return java.util.Arrays.stream(clazz.getMethods())
                        .anyMatch(m -> m.getName().equals(methodName));
            } catch (ClassNotFoundException ignored) {}
        }
        return false; // can't resolve — give benefit of the doubt
    }

    private void collectFromHierarchy(String className, String methodName, List<MethodSymbol> collected) {
        var tableOpt = this.table.getImportedSymbolTable(className);
        if (tableOpt.isEmpty()) return;
        var t = tableOpt.get();
        collected.addAll(t.getMethods(methodName));
        String superName = t.getSuperFullyQualifiedName();
        if (superName != null && !superName.isEmpty()) {
            collectFromHierarchy(superName, methodName, collected);
        }
    }


    private Void visitNewObjectExpr(JmmNode newObjExpr, SymbolTable table) {
        List<JmmType> argTypes = collectArgTypes(newObjExpr, table);

        // Get class name - either from 'name' attribute directly or from SIMPLE_TYPE child
        String className;
        var simpleTypeNodes = newObjExpr.getChildren(SIMPLE_TYPE);
        if (!simpleTypeNodes.isEmpty()) {
            className = simpleTypeNodes.get(0).get("name");
        } else if (newObjExpr.hasAttribute("name")) {
            className = newObjExpr.get("name");
        } else {
            return null;
        }

        System.out.println("visitNewObjectExpr className: " + className);
        var classTableOpt = this.table.getImportedSymbolTable(className);
        System.out.println("classTableOpt present: " + classTableOpt.isPresent());
        if (classTableOpt.isPresent()) {
            System.out.println("constructors: " + classTableOpt.get().getMethods(className));
        }

        if (classTableOpt.isEmpty()) {
            tryReflectionConstructorValidation(newObjExpr, className, argTypes);
            return null;
        }

        var classTable = classTableOpt.get();
        var constructors = classTable.getMethods(className);
        if (constructors.isEmpty()) {
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