package pt.up.fe.comp2026.optimization;

import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ast.JmmNodeImpl;
import pt.up.fe.comp.jmm.ast.Kind;

import java.util.Set;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class NodeOptimization {
    private static final Set<Kind> literals = Set.of(TRUE_LITERAL, FALSE_LITERAL, INTEGER_LITERAL);

    public static void optimize(JmmNode root){
        // Optimize child nodes
        for (JmmNode node: root.getChildren())
            optimize(node);

        // Optimize current node
        // Since children are already optimized this becomes more trivial
        optimizeBinaryOperatorNode(root);
        optimizeUselessNode(root);
    }

    private static void optimizeBinaryOperatorNode(JmmNode node) {
        if (!node.getKind().equals(BINARY_EXPR)) return;

        var left = node.getChild(0);
        var right = node.getChild(1);

        if (!(literals.contains(left.getKind()) && literals.contains(right.getKind())))
            return;

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

                if ((op.equals("/") || op.equals("%")) && b == 0) return;

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
    }

    private static void optimizeUselessNode(JmmNode node){
        if (!isUselessNode(node)) return;

        // Remove Current node but keep the children
        var parent = node.getParent();
        if (parent == null) return;

        // Find index of the node in the parent
        int index = parent.getChildren().indexOf(node);

        // Copy children (important to avoid concurrent modification)
        var children = node.getChildren();

        // Remove the node
        node.delete();

        // Insert children in the same position
        for (int i = 0; i < children.size(); i++) {
            parent.add(children.get(i), index + i);
        }
    }

    private static boolean isUselessNode(JmmNode node){
        if (node.getKind().equals(FULL_RETURN_STMT)) return true;
        return node.getKind().equals(FULL_ASSIGN_STMT);
    }
}
