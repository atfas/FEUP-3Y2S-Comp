package pt.up.fe.comp2026.ast;

import org.specs.comp.ollir.type.ClassType;
import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.Signature;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ast.NodeAttribute;
import pt.up.fe.comp2026.JavammParser;
import pt.up.fe.comp2026.jmm.ast.JmmKind;
import pt.up.fe.comp2026.symboltable.JmmSymbolTable;
import pt.up.fe.specs.util.SpecsCheck;
import pt.up.fe.specs.util.exceptions.NotImplementedException;

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
            return JmmArrayType.of(type);
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
     *
     * @param expr
     * @return
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
            case NEW_OBJECT_EXPR -> getNewObjectExprType(expr);
            case PAREN_EXPR -> getParenExpr(expr);
            default ->
                    throw new UnsupportedOperationException("Can't compute type for expression kind '" + expr.getKind() + "'");
        };
    }

    private JmmType getParenExpr(JmmNode parentExpr){
        return getExprType(parentExpr.getChild(0));
    }


    public Signature getMethodDeclSignature(JmmNode methodDecl) {
        METHOD_DECL.check(methodDecl);

        var methodName = methodDecl.get("name");

        var params = new ArrayList<JmmType>();

        // For every Param
        for (var param : methodDecl.getChildren(PARAM)) {
            // Get the type from "Type PramName"
            var typeNode = param.getChild(0);
            params.add(convertType(typeNode));
        }

        return new Signature(methodName, params);
    }


    private JmmType getBinExprType(JmmNode binaryExpr) {

        // Get operator
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

        // Lookup variable in method parameters
        var param = currentMethod.getParameter(name);
        if (param.isPresent()) return param.get().type();

        // Lookup variable in local variables
        var local = currentMethod.getLocalVariable(name);
        if (local.isPresent()) return local.get().type();

        // Lookup variable in class fields
        var field = table.getField(name);
        if (field.isPresent()) return field.get().type();

        // Allow current class name used as static reference (e.g. N.zero())
        if (name.equals(table.getClassName())) {
            return JmmClassType.ofInstance(table.getFullyQualifiedName(), false);
        }

        // Allow imported class names used as static references
        var importedFqn = table.getImports().stream()
                .filter(imp -> imp.equals(name) || imp.endsWith("." + name))
                .findFirst();
        if (importedFqn.isPresent()) {
            return JmmClassType.ofInstance(importedFqn.get(), true);
        }

        throw new RuntimeException("Variable '" + name + "' not found in scope");
    }

    private JmmType getArrayExprType(JmmNode array){
        return JmmArrayType.of(convertType(array.getChildren(SIMPLE_TYPE).getFirst()));
    }

    private JmmType getArrayAccessType(JmmNode base, JmmNode arrayExpr) {
        JmmType baseType = getExprType(base);
        if (!(baseType instanceof JmmArrayType arrayType)) {
            throw new RuntimeException("Cannot perform array access on non-array type: " + baseType);
        }
        // Assuming JmmArrayType has a method to get the element type
        // Common methods: getElementType(), getType(), getComponentType()
        try {
            return (JmmType) arrayType.getClass().getMethod("getType").invoke(arrayType);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get element type from array type: " + baseType, e);
        }
    }

    private JmmType getFieldAccessType(JmmNode base, JmmNode fieldExpr) {
        JmmType baseType = getExprType(base);
        String fieldName = fieldExpr.get("name");

        // Special case for array.length
        if (baseType instanceof JmmArrayType && "length".equals(fieldName)) {
            return intType();
        }

        if (!(baseType instanceof JmmClassType classType)) {
            throw new RuntimeException("Cannot access field '" + fieldName + "' on non-class type: " + baseType);
        }

        // Try to find the field in the current class
        var field = table.getField(fieldName);
        if (field.isPresent()) {
            return field.get().type();
        }

        // For imported classes, we give benefit of the doubt
        if (classType.isImported()) {
            return baseType; // Return the base type as a fallback
        }

        throw new RuntimeException("Field '" + fieldName + "' not found in class '" + classType.name() + "'");
    }

    private JmmType getPostFixExprType(JmmNode expr){
        var children = expr.getChildren();

        if (children.size() == 1) {
            return getExprType(children.getFirst());
        }

        if (children.size() == 2 && OBJECT_CALL_EXPR.check(children.get(1))) {
            return getObjectCallType(children.get(0), children.get(1));
        }

        if (children.size() == 2 && ARRAY_EXPR.check(children.get(1))) {
            // Validate array index must be int
            JmmNode indexExpr = children.get(1).getChild(0);
            JmmType indexType = getExprType(indexExpr);
            if (!indexType.equals(intType())) {
                throw new RuntimeException("Array index must be of type int, got: " + indexType);
            }

            JmmType baseType = getExprType(children.get(0));
            if (baseType instanceof JmmArrayType arrayType) {
                return arrayType.itemType();
            }
            return intType();
        }

        throw new RuntimeException(
                "Unsupported POSTFIX_EXPR structure with " + children.size() + " children: " + expr
        );
    }
    private JmmType getObjectCallType(JmmNode caller, JmmNode funcCall){
        String methodName = funcCall.get("name");

        // Extract argument types
        List<JmmType> argTypes = new ArrayList<>();
        Optional<JmmNode> exprListOpt = funcCall.getChildren().stream()
                .filter(c -> c.getKind() == EXPR_LIST)
                .findFirst();

        if (exprListOpt.isPresent()) {
            JmmNode exprList = exprListOpt.get();
            for (JmmNode arg : exprList.getChildren()) {
                argTypes.add(getExprType(arg));
            }
        }

        // Lookup method by name + parameter types
        Signature signature = new Signature(methodName, argTypes);

        var method = table.getMethod(signature);

        // If found in local symbol table, return its return type
        if (method.isPresent()) {
            return method.get().returnType();
        }

        // If not found, check if caller is an imported class — give benefit of the doubt
        JmmType callerType = getExprType(caller);
        if (callerType instanceof JmmClassType classType) {
            boolean isImported = table.getImports().stream()
                    .anyMatch(imp -> imp.equals(classType.name()) || imp.endsWith("." + classType.name()));
            if (isImported) {
                return callerType;
            }
        }

        throw new RuntimeException("Method not found: " + signature);
    }

    private JmmType getNewObjectExprType(JmmNode object){
        if (object.getChildren(SIMPLE_TYPE).isEmpty()) {
            return convertType(object);
        }else{
            return convertType(object.getChildren(SIMPLE_TYPE).getFirst());
        }
    }
}
