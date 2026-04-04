package pt.up.fe.comp2026.ast;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.Signature;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.symboltable.JmmSymbolTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;


/**
 * Utility methods regarding types.
 */
public class TypeUtils {

    private final JmmSymbolTable table;

    public TypeUtils(SymbolTable table) {
        this.table = (JmmSymbolTable) table;
    }

    public static TypeUtils with(SymbolTable table) {
        return new TypeUtils(table);
    }

    public static JmmPrimitiveType intType() {
        return JmmPrimitiveType.INT;
    }

    public static JmmPrimitiveType boolType() { return JmmPrimitiveType.BOOLEAN; }

    public static JmmType convertType(JmmNode typeNode, String className, String fullyQualifiedName, List<String> imports) {
        var name = typeNode.get("name");
        var primitiveType = JmmPrimitiveType.fromString(name);
        JmmType type;

        if (primitiveType.isPresent()) {
            type = primitiveType.get();
        } else if (name.equals(className)) {
            type = JmmClassType.ofInstance(fullyQualifiedName, false);
        } else {
            var importName = imports.stream().filter(i -> i.equals(name) || i.endsWith("." + name)).findFirst();
            if (importName.isPresent()) {
                type = JmmClassType.ofInstance(importName.get(), true);
            } else {
                type = JmmClassType.ofInstance(name, false);
            }
        }

        if (ARRAY_TYPE.check(typeNode)) {
            int colStart = Integer.parseInt(typeNode.get("colStart"));
            int colEnd = Integer.parseInt(typeNode.get("colEnd"));
            int spanLength = colEnd - colStart + 1;
            int dims = (spanLength - name.length()) / 2;
            if (dims < 1) dims = 1;

            for (int i = 0; i < dims; i++) {
                type = JmmArrayType.of(type);
            }
        }

        return type;
    }

    public JmmType convertType(JmmNode typeNode) {
        return convertType(typeNode, table.getClassName(), table.getFullyQualifiedName(), table.getImports());
    }

    public JmmType getVarType(String name, MethodSymbol method) {
        var local = method.getLocalVariable(name);
        if (local.isPresent()) return local.get().type();

        var param = method.getParameter(name);
        if (param.isPresent()) return param.get().type();

        var fieldOpt = table.getField(name);
        if (fieldOpt.isPresent()) return fieldOpt.get().type();

        throw new RuntimeException("Undeclared variable: " + name);
    }

    /**
     * Gets the {@link JmmType} of an arbitrary expression.
     */
    public JmmType getExprType(JmmNode expr) {
        return switch (expr.getKind()) {
            case THIS_LITERAL -> JmmClassType.ofInstance(table.getFullyQualifiedName(), false);
            case INTEGER_LITERAL -> intType();
            case TRUE_LITERAL, FALSE_LITERAL, NOT_EXPR -> boolType();
            case BINARY_EXPR -> getBinExprType(expr);
            case VAR_REF_EXPR -> getVarExprType(expr);
            case PRIMARY_EXPR -> getExprType(expr.getChild(0));
            case POSTFIX_EXPR -> getPostFixExprType(expr);
            case NEW_ARRAY_EXPR -> getArrayExprType(expr);
            case NEW_ARRAY_LITERAL_EXPR -> getArrayLiteralExprType(expr);
            case NEW_OBJECT_EXPR -> getNewObjectExprType(expr);
            case PAREN_EXPR -> getParenExpr(expr);
            case UNARY_EXPR -> intType();
            case CALL_EXPR -> getCallExprType(expr);
            default ->
                    throw new UnsupportedOperationException("Can't compute type for expression kind '" + expr.getKind() + "'");
        };
    }

    private JmmType getParenExpr(JmmNode parentExpr) {
        return getExprType(parentExpr.getChild(0));
    }

    public Signature getMethodDeclSignature(JmmNode methodDecl) {
        METHOD_DECL.check(methodDecl);

        var methodName = methodDecl.get("name");
        var params = new ArrayList<JmmType>();

        for (var param : methodDecl.getChildren(PARAM)) {
            var typeNode = param.getChild(0);
            params.add(convertType(typeNode));
        }

        return new Signature(methodName, params);
    }

    private JmmType getBinExprType(JmmNode binaryExpr) {
        String operator = binaryExpr.get("op");
        return switch (operator) {
            case "+", "*", "/", "%", "-" -> intType();
            case "&&", "||", "<", ">", "<=", ">=", "==", "!=" -> boolType();
            default ->
                    throw new RuntimeException("Unknown operator '" + operator + "' of expression '" + binaryExpr + "'");
        };
    }

    private JmmType getVarExprType(JmmNode varRefExpr) {
        var name = varRefExpr.get("name");

        var methodNode = varRefExpr.getAncestor(METHOD_DECL).orElseThrow();
        var signature = TypeUtils.with(table).getMethodDeclSignature(methodNode);
        var currentMethod = table.getMethod(signature).orElseThrow();

        var param = currentMethod.getParameter(name);
        if (param.isPresent()) return param.get().type();

        var local = currentMethod.getLocalVariable(name);
        if (local.isPresent()) return local.get().type();

        var field = table.getField(name);
        if (field.isPresent()) return field.get().type();

        if (name.equals(table.getClassName())) {
            return JmmClassType.ofInstance(table.getFullyQualifiedName(), false);
        }

        var importedFqn = table.getImports().stream()
                .filter(imp -> imp.equals(name) || imp.endsWith("." + name))
                .findFirst();
        if (importedFqn.isPresent()) {
            return JmmClassType.ofInstance(importedFqn.get(), true);
        }

        throw new RuntimeException("Variable '" + name + "' not found in scope");
    }

    private JmmType getArrayExprType(JmmNode array) {
        // Get base element type from SIMPLE_TYPE child
        var simpleTypeNodes = array.getChildren(SIMPLE_TYPE);
        JmmType baseType;
        if (!simpleTypeNodes.isEmpty()) {
            baseType = convertType(simpleTypeNodes.get(0));
        } else {
            // Fallback: first TYPE child
            baseType = convertType(array.getChildren(TYPE).getFirst());
        }

        // Count dimensions: every non-SIMPLE_TYPE / non-TYPE child is a dimension bracket
        long dimCount = array.getChildren().stream()
                .filter(c -> !SIMPLE_TYPE.check(c) && !TYPE.check(c))
                .count();

        if (dimCount == 0) dimCount = 1; // safety

        // Wrap baseType in dimCount layers of JmmArrayType
        JmmType result = baseType;
        for (long i = 0; i < dimCount; i++) {
            result = JmmArrayType.of(result);
        }

        return result;
    }

    private JmmType getArrayLiteralExprType(JmmNode arrayLiteral) {
        var typeNode = arrayLiteral.getChildren(TYPE).getFirst();
        var elementType = convertType(typeNode);
        var exprLists = arrayLiteral.getChildren(EXPR_LIST);

        if (!exprLists.isEmpty()) {
            var exprList = exprLists.getFirst();
            for (var child : exprList.getChildren()) {
                var elementExprType = getExprType(child);
                if (!isValidArrayElementType(elementType, elementExprType)) {
                    throw new RuntimeException("Array initializer element type must match array element type: "
                            + elementType + " != " + elementExprType);
                }
            }
        }

        return JmmArrayType.of(elementType);
    }

    private boolean isValidArrayElementType(JmmType targetType, JmmType exprType) {
        if (targetType instanceof JmmPrimitiveType && exprType instanceof JmmPrimitiveType) {
            return targetType.equals(exprType);
        }
        if (targetType instanceof JmmArrayType && exprType instanceof JmmArrayType) {
            return targetType.equals(exprType);
        }
        if (targetType instanceof JmmClassType && exprType instanceof JmmClassType) {
            return targetType.equals(exprType);
        }
        return false;
    }

    private JmmType getCallExprType(JmmNode callExpr) {
        String methodName = callExpr.get("name");
        List<JmmType> argTypes = new ArrayList<>();

        for (var child : callExpr.getChildren()) {
            if (EXPR_LIST.check(child)) {
                for (var arg : child.getChildren()) {
                    argTypes.add(getExprType(arg));
                }
            }
        }

        Signature signature = new Signature(methodName, argTypes);
        var method = table.getMethod(signature);
        if (method.isPresent()) return method.get().returnType();

        // Check super hierarchy
        var superMethod = getMethodInHierarchy(signature);
        if (superMethod.isPresent()) return superMethod.get().returnType();

        return JmmPrimitiveType.VOID;
    }

    private JmmType getPostFixExprType(JmmNode expr) {
        var children = expr.getChildren();

        if (children.size() == 1) {
            return getExprType(children.getFirst());
        }

        // Handle chains: base [op1] [op2] ...
        JmmType currentType = getExprType(children.get(0));

        for (int i = 1; i < children.size(); i++) {
            var child = children.get(i);

            if (OBJECT_CALL_EXPR.check(child)) {
                currentType = getObjectCallType(children.get(0), child);
            } else if (ARRAY_EXPR.check(child)) {
                JmmNode indexExpr = child.getChild(0);
                JmmType indexType = getExprType(indexExpr);
                if (!indexType.equals(intType())) {
                    throw new RuntimeException("Array index must be of type int, got: " + indexType);
                }
                if (currentType instanceof JmmArrayType arrayType) {
                    currentType = arrayType.itemType();
                } else {
                    return intType();
                }
            } else if (FIELD_ACCESS_EXPR.check(child)) {
                String fieldName = child.get("name");
                if ("length".equals(fieldName) && currentType.isArray()) {
                    currentType = intType();
                } else if (currentType instanceof JmmClassType classType) {
                    boolean isImported = table.getImports().stream()
                            .anyMatch(imp -> imp.equals(classType.name()) || imp.endsWith("." + classType.name()));
                    if (isImported) {
                        // give benefit of the doubt
                    } else {
                        throw new RuntimeException("Unsupported field access: " + fieldName + " on " + currentType);
                    }
                } else {
                    throw new RuntimeException("Unsupported field access: " + fieldName + " on " + currentType);
                }
            }
        }

        return currentType;
    }

    private JmmType getObjectCallType(JmmNode caller, JmmNode funcCall) {
        String methodName = funcCall.get("name");

        List<JmmType> argTypes = new ArrayList<>();
        Optional<JmmNode> exprListOpt = funcCall.getChildren().stream()
                .filter(c -> c.getKind() == EXPR_LIST)
                .findFirst();

        if (exprListOpt.isPresent()) {
            for (JmmNode arg : exprListOpt.get().getChildren()) {
                argTypes.add(getExprType(arg));
            }
        }

        Signature signature = new Signature(methodName, argTypes);
        var method = getMethodInHierarchy(signature);
        if (method.isPresent()) return method.get().returnType();

        // Give benefit of the doubt for imported class calls
        JmmType callerType = getExprType(caller);
        if (callerType instanceof JmmClassType classType) {
            boolean isImported = table.getImports().stream()
                    .anyMatch(imp -> imp.equals(classType.name()) || imp.endsWith("." + classType.name()));
            if (isImported) {
                try {
                    String fqn = table.getImports().stream()
                            .filter(imp -> imp.equals(classType.name()) || imp.endsWith("." + classType.name()))
                            .findFirst().orElse(classType.name());
                    Class<?> clazz = Class.forName(fqn);
                    var matchingMethod = java.util.Arrays.stream(clazz.getMethods())
                            .filter(m -> m.getName().equals(methodName))
                            .findFirst();
                    if (matchingMethod.isPresent() && matchingMethod.get().getReturnType().isArray()) {
                        return JmmArrayType.of(intType());
                    }
                } catch (ClassNotFoundException ignored) {}
                return callerType;
            }
        }

        throw new RuntimeException("Method not found: " + signature);
    }

    private Optional<MethodSymbol> getMethodInHierarchy(Signature signature) {
        var method = table.getMethod(signature);
        if (method.isPresent()) return method;

        String superName = table.getSuperFullyQualifiedName();
        if (superName != null && !superName.isEmpty()) {
            var superTableOpt = table.getImportedSymbolTable(superName);
            if (superTableOpt.isPresent()) {
                return getMethodInHierarchyRecursive(superTableOpt.get(), signature);
            }
        }

        return Optional.empty();
    }

    private Optional<MethodSymbol> getMethodInHierarchyRecursive(SymbolTable currentTable, Signature signature) {
        var method = currentTable.getMethod(signature);
        if (method.isPresent()) return method;

        String superName = currentTable.getSuperFullyQualifiedName();
        if (superName != null && !superName.isEmpty() && currentTable instanceof JmmSymbolTable jmmTable) {
            var superTableOpt = jmmTable.getImportedSymbolTable(superName);
            if (superTableOpt.isPresent()) {
                return getMethodInHierarchyRecursive(superTableOpt.get(), signature);
            }
        }

        return Optional.empty();
    }

    private JmmType getNewObjectExprType(JmmNode object) {
        if (object.getChildren(SIMPLE_TYPE).isEmpty()) {
            return convertType(object);
        } else {
            return convertType(object.getChildren(SIMPLE_TYPE).getFirst());
        }
    }
}