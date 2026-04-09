package pt.up.fe.comp2026.optimization;

import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp2026.jmm.ast.JmmAttributes;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class NodeOptimization {
    public static void optimize(JmmNode root){
        // Optimize child nodes
        for (JmmNode node: root.getChildren())
            optimize(node);

        // Optimize current node
        // Since children are already optimized this becomes more trivial
        optimizeBinaryOperatorNode(root);
        optimizeUselessNode(root);
    }

    private static void optimizeBinaryOperatorNode(JmmNode node){
        if (!node.getKind().equals(BINARY_EXPR)) return;

        var op = node.get("op");
        // TODO: Finish implementation
        // Check for each operator if both values are literals
        // if so compute the result and replace the current node
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
