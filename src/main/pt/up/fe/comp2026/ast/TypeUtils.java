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
            case PRIMARY_EXPR, POSTFIX_EXPR -> getExprType(expr.getChild(0));
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
}
