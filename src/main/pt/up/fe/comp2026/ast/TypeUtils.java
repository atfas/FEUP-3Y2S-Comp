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
            case INTEGER_LITERAL -> intType();
            case TRUE_LITERAL, FALSE_LITERAL -> boolType();
            case BINARY_EXPR -> getBinExprType(expr);
            case VAR_REF_EXPR -> getVarExprType(expr);
            case PRIMARY_EXPR -> getExprType(expr.getChild(0));
            case POSTFIX_EXPR -> getPostFixExprType(expr);
            case NEW_ARRAY_EXPR -> getArrayExprType(expr);
            case NEW_OBJECT_EXPR -> getNewObjectExprType(expr);
            default ->
                    throw new UnsupportedOperationException("Can't compute type for expression kind '" + expr.getKind() + "'");
        };
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

        // If not found → error (should not happen if previous pass works)
        throw new RuntimeException("Variable '" + name + "' not found in scope");
    }

    private JmmType getArrayExprType(JmmNode array){
        return JmmArrayType.of(convertType(array.getChildren(SIMPLE_TYPE).getFirst()));
    }

    private JmmType getPostFixExprType(JmmNode expr){
        var children = expr.getChildren();

        // 1️⃣ Simple wrapper (e.g., literal, var)
        if (children.size() == 1) {
            return getExprType(children.getFirst());
        }

        // 2️⃣ Object method call: obj.method()
        if (children.size() == 2 && OBJECT_CALL_EXPR.check(children.get(1))) {
            return getObjectCallType(children.get(0), children.get(1));
        }

        // ❌ Unknown structure
        throw new RuntimeException(
                "Unsupported POSTFIX_EXPR structure with " + children.size() + " children: " + expr
        );
    }

    private JmmType getObjectCallType(JmmNode caller, JmmNode funcCall){
        String methodName = funcCall.get("name");

        // TODO: allow for method call with parameters
        Signature signature = new Signature(methodName, new ArrayList<JmmType>());

        var method = table.getMethod(signature).orElseThrow(
                () -> new RuntimeException("Method not found: " + signature)
        );

        return method.returnType();
    }

    private JmmType getNewObjectExprType(JmmNode object){
        if (object.getChildren(SIMPLE_TYPE).isEmpty()) {
            return convertType(object);
        }else{
            return convertType(object.getChildren(SIMPLE_TYPE).getFirst());
        }
    }
}
