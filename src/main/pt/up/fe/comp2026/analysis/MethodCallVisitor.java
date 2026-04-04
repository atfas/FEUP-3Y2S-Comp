package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.ast.TypeUtils;

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
    }
    private Void visitObjectCallExpr(JmmNode callNode, SymbolTable table) {
        var postfixExpr = callNode.getParent();
        var base = postfixExpr.getChild(0);

        String methodName = callNode.get("name");

        // Collect argument types from the call site
        List<JmmType> argTypes = new ArrayList<>();
        for (var child : callNode.getChildren()) {
            if (EXPR_LIST.check(child)) {
                for (var arg : child.getChildren()) {
                    argTypes.add(TypeUtils.with(table).getExprType(arg));
                }
            }
        }

        if (THIS_LITERAL.check(base)) {
            SearchResult result = methodExistsInHierarchy(table, methodName);
            if (result == SearchResult.NOT_FOUND) {
                addReport(newError(callNode,
                        "Cannot call method '" + methodName + "': not defined in class '"
                                + table.getClassName() + "'"));
            }
            return null;
        }

        JmmType callerType = TypeUtils.with(table).getExprType(base);
        if (!(callerType instanceof JmmClassType classType)) return null;

        String callerClassName = classType.name();

        var importedTableOpt = this.table.getImportedSymbolTable(callerClassName);
        if (importedTableOpt.isEmpty()) {
            // Try Java reflection for JDK/classpath classes
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
                                String paramName = params[i].getSimpleName().toLowerCase();
                                JmmType argType = argTypes.get(i);
                                // If param is an array type, give benefit of the doubt
                                if (params[i].isArray()) return true;
                                String argName = argType.toString().toLowerCase();
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
            return null;
        }

        var importedTable = importedTableOpt.get();
        var candidates = importedTable.getMethods(methodName);

        if (candidates.isEmpty()) {
            addReport(newError(callNode,
                    "Method '" + methodName + "' not found in class '" + callerClassName + "'"));
            return null;
        }

        boolean anyMatch = candidates.stream().anyMatch(candidate -> {
            var params = candidate.parameters();
            if (params.size() != argTypes.size()) return false;
            for (int i = 0; i < params.size(); i++) {
                if (!params.get(i).type().equals(argTypes.get(i))) return false;
            }
            return true;
        });

        if (!anyMatch) {
            addReport(newError(callNode,
                    "No matching overload of '" + methodName + "' in class '" + callerClassName
                            + "' for argument types " + argTypes));
        }

        return null;
    }

    private Void visitCallExpr(JmmNode callNode, SymbolTable table) {
        String methodName = callNode.get("name");

        // Collect argument types
        List<JmmType> argTypes = new ArrayList<>();
        for (var child : callNode.getChildren()) {
            if (EXPR_LIST.check(child)) {
                for (var arg : child.getChildren()) {
                    argTypes.add(TypeUtils.with(table).getExprType(arg));
                }
            }
        }

        // Treat as implicit this call
        SearchResult result = methodExistsInHierarchy(table, methodName);
        if (result == SearchResult.NOT_FOUND) {
            addReport(newError(callNode,
                    "Cannot call method '" + methodName + "': not defined in class '"
                            + table.getClassName() + "'"));
        }

        return null;
    }
    private enum SearchResult { FOUND, NOT_FOUND, UNRESOLVABLE }

    private SearchResult methodExistsInHierarchy(SymbolTable currentTable, String methodName) {
        if (!currentTable.getMethods(methodName).isEmpty()) return SearchResult.FOUND;

        String superName = currentTable.getSuperFullyQualifiedName();
        if (superName == null || superName.isEmpty()) return SearchResult.NOT_FOUND;

        // Check if super is a java.lang.* class — treat as resolvable
        try {
            Class<?> superClass = Class.forName("java.lang." + superName);
            boolean methodExists = java.util.Arrays.stream(superClass.getMethods())
                    .anyMatch(m -> m.getName().equals(methodName));
            return methodExists ? SearchResult.FOUND : SearchResult.NOT_FOUND;
        } catch (ClassNotFoundException ignored) {}

        // Check imports

        var parentTableOpt = this.table.getImportedSymbolTable(superName);
        if (parentTableOpt.isEmpty()) return SearchResult.UNRESOLVABLE;

        return methodExistsInHierarchy(parentTableOpt.get(), methodName);
    }
}