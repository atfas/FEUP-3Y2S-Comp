package pt.up.fe.comp2026.optimization;

import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ast.JmmNodeImpl;
import pt.up.fe.comp.jmm.ast.Kind;
import pt.up.fe.comp.jmm.ast.PostorderJmmVisitor;

import java.util.Set;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class NodeOptimizationVisitor extends PostorderJmmVisitor<SymbolTable, Void> {
    private static final Set<Kind> literals = Set.of(TRUE_LITERAL, FALSE_LITERAL, INTEGER_LITERAL);

    @Override
    protected void buildVisitor() {
        setDefaultVisit(this::defaultVisit);

        addVisit(FULL_ASSIGN_STMT, this::uselessNodeVisit);
        addVisit(FULL_RETURN_STMT, this::uselessNodeVisit);
        addVisit(PAREN_EXPR, this::uselessNodeVisit);

        addVisit(BINARY_EXPR, this::binaryExprVisit);

        addVisit(UNARY_EXPR, this::unaryExprVisit);
        addVisit(NOT_EXPR, this::notVisit);
    }

    private Void defaultVisit(JmmNode node, SymbolTable table) {
        return null;
    }

    private Void notVisit(JmmNode node, SymbolTable table) {
        var child = node.getChild(0);

        // Only fold if it's a boolean literal
        if (!(child.getKind() == TRUE_LITERAL || child.getKind() == FALSE_LITERAL))
            return null;

        boolean value = child.getKind() == TRUE_LITERAL;
        boolean result = !value;

        Kind newKind = result ? TRUE_LITERAL : FALSE_LITERAL;

        JmmNode newNode = new JmmNodeImpl(newKind);
        newNode.put("value", Boolean.toString(result));

        node.replace(newNode);

        return null;
    }



    private Void unaryExprVisit(JmmNode node, SymbolTable table) {
        var child = node.getChild(0);
        if (!(literals.contains(child.getKind()))) return null;

        var op = node.get("op");

        Kind newKind;
        String value;

        switch (op){
            case "+": {
                // Unary plus does nothing
                newKind = INTEGER_LITERAL;
                value = child.get("value");
                break;
            }

            case "-": {
                int v = Integer.parseInt(child.get("value"));
                int res = -v;

                newKind = INTEGER_LITERAL;
                value = Integer.toString(res);
                break;
            }

            case "++": {
                int v = Integer.parseInt(child.get("value"));
                int res = v + 1;

                newKind = INTEGER_LITERAL;
                value = Integer.toString(res);
                break;
            }

            case "--": {
                int v = Integer.parseInt(child.get("value"));
                int res = v - 1;

                newKind = INTEGER_LITERAL;
                value = Integer.toString(res);
                break;
            }

            case "!": {
                boolean v = child.getKind() == TRUE_LITERAL;
                boolean res = !v;

                newKind = res ? TRUE_LITERAL : FALSE_LITERAL;
                value = Boolean.toString(res);
                break;
            }
            default:
                throw new RuntimeException("Operator not supported: " + op);
        }

        JmmNode newNode = new JmmNodeImpl(newKind);
        newNode.put("value", value);

        node.replace(newNode);
        return null;
    }

    private Void binaryExprVisit(JmmNode node, SymbolTable table) {
        var left = node.getChild(0);
        var right = node.getChild(1);

        if (!(literals.contains(left.getKind()) && literals.contains(right.getKind())))
            return null;

        var op = node.get("op");

        Kind newKind;
        String value;

        switch (op) {

            case "+":
            case "-":
            case "*":
            case "/":
            case "%": {
                int a = Integer.parseInt(left.get("value"));
                int b = Integer.parseInt(right.get("value"));

                if ((op.equals("/") || op.equals("%")) && b == 0) return null;

                int res = switch (op) {
                    case "+" -> a + b;
                    case "-" -> a - b;
                    case "*" -> a * b;
                    case "/" -> a / b;
                    case "%" -> a % b;
                    default -> 0;
                };

                newKind = INTEGER_LITERAL;
                value = Integer.toString(res);
                break;
            }

            case "==":
            case "!=": {
                boolean res;

                if (left.getKind() == INTEGER_LITERAL) {
                    int a = Integer.parseInt(left.get("value"));
                    int b = Integer.parseInt(right.get("value"));
                    res = op.equals("==") == (a == b);
                } else {
                    res = op.equals("==") == (left.getKind() == right.getKind());
                }

                newKind = res ? TRUE_LITERAL : FALSE_LITERAL;
                value = Boolean.toString(res);
                break;
            }

            case "<":
            case ">":
            case "<=":
            case ">=": {
                int a = Integer.parseInt(left.get("value"));
                int b = Integer.parseInt(right.get("value"));

                boolean res = switch (op) {
                    case "<" -> a < b;
                    case ">" -> a > b;
                    case "<=" -> a <= b;
                    case ">=" -> a >= b;
                    default -> false;
                };

                newKind = res ? TRUE_LITERAL : FALSE_LITERAL;
                value = Boolean.toString(res);
                break;
            }

            case "&&": {
                boolean a = left.getKind() == TRUE_LITERAL;
                boolean b = right.getKind() == TRUE_LITERAL;

                boolean res = a && b;

                newKind = res ? TRUE_LITERAL : FALSE_LITERAL;
                value = Boolean.toString(res);
                break;
            }

            case "||": {
                boolean a = left.getKind() == TRUE_LITERAL;
                boolean b = right.getKind() == TRUE_LITERAL;

                boolean res = a || b;

                newKind = res ? TRUE_LITERAL : FALSE_LITERAL;
                value = Boolean.toString(res);
                break;
            }

            default:
                throw new RuntimeException("Operator not supported: " + op);
        }

        JmmNode newNode = new JmmNodeImpl(newKind);
        newNode.put("value", value);

        node.replace(newNode);
        return null;
    }

    private Void uselessNodeVisit(JmmNode node, SymbolTable table) {
        if (node.getNumChildren() != 1) {
            throw new RuntimeException(
                    "Expected wrapper node with exactly one child: " + node.getKind()
            );
        }

        JmmNode child = node.getChild(0);
        node.replace(child);

        return null;
    }
}
