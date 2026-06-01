package pt.up.fe.comp2026.optimization;

import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ast.JmmNodeImpl;
import pt.up.fe.comp.jmm.ast.Kind;

import java.util.*;
import java.util.stream.Collectors;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class AstOptimizer {

    private boolean changed;

    private static final Set<Kind> LITERALS    = Set.of(TRUE_LITERAL, FALSE_LITERAL, INTEGER_LITERAL);
    private static final Set<Kind> LOOP_KINDS  = Set.of(WHILE_STMT, DO_STMT, FOR_STMT);
    private static final Set<Kind> CALL_KINDS  = Set.of(OBJECT_CALL_EXPR, CALL_EXPR, NEW_OBJECT_EXPR);

    // ── Always: strip structural wrappers needed before OLLIR gen ─────────────

    public void stripWrappers(JmmNode node) {
        for (int i = 0; i < node.getNumChildren(); i++)
            stripWrappers(node.getChild(i));
        Kind k = node.getKind();
        // Strip statement wrappers and expr wrappers with exactly 1 child
        boolean isWrapper = (k == FULL_ASSIGN_STMT || k == FULL_RETURN_STMT || k == PAREN_EXPR
                || k == PRIMARY_EXPR
                || (k == POSTFIX_EXPR && node.getNumChildren() == 1));
        if (isWrapper && node.getNumChildren() == 1)
            node.replace(node.getChild(0));
    }

    // ── One optimisation pass; returns true if AST was modified ──────────────

    public boolean runPass(JmmNode root) {
        changed = false;
        foldNode(root);
        for (JmmNode method : collectMethodDecls(root)) {
            Set<String> locals = localVarNames(method);
            Set<String> params = paramVarNames(method);
            propagateStmts(methodStmts(method), new HashMap<>(), locals);
            dceStmtList(methodStmts(method), new HashSet<>(), locals, params);
        }
        elimBranchesRec(root);
        return changed;
    }

    // ── Constant folding (postorder) ──────────────────────────────────────────

    private void foldNode(JmmNode n) {
        for (int i = 0; i < n.getNumChildren(); i++) foldNode(n.getChild(i));
        Kind k = n.getKind();
        if      (k == BINARY_EXPR) foldBin(n);
        else if (k == UNARY_EXPR)  foldUnary(n);
        else if (k == NOT_EXPR)    foldNot(n);
    }

    private void foldBin(JmmNode n) {
        JmmNode l = n.getChild(0), r = n.getChild(1);
        if (!LITERALS.contains(l.getKind()) || !LITERALS.contains(r.getKind())) return;
        String op = n.get("op");
        Kind nk; String nv;
        switch (op) {
            case "+": case "-": case "*": case "/": case "%": {
                int a = intOf(l), b = intOf(r);
                if ((op.equals("/") || op.equals("%")) && b == 0) return;
                int res = switch (op) { case "+"->a+b; case "-"->a-b; case "*"->a*b; case "/"->a/b; default->a%b; };
                nk = INTEGER_LITERAL; nv = String.valueOf(res); break;
            }
            case "&&": { boolean res = boolOf(l) && boolOf(r); nk = res?TRUE_LITERAL:FALSE_LITERAL; nv = String.valueOf(res); break; }
            case "||": { boolean res = boolOf(l) || boolOf(r); nk = res?TRUE_LITERAL:FALSE_LITERAL; nv = String.valueOf(res); break; }
            case "<":  { boolean res = intOf(l) < intOf(r);  nk = res?TRUE_LITERAL:FALSE_LITERAL; nv = String.valueOf(res); break; }
            case ">":  { boolean res = intOf(l) > intOf(r);  nk = res?TRUE_LITERAL:FALSE_LITERAL; nv = String.valueOf(res); break; }
            case "<=": { boolean res = intOf(l) <= intOf(r); nk = res?TRUE_LITERAL:FALSE_LITERAL; nv = String.valueOf(res); break; }
            case ">=": { boolean res = intOf(l) >= intOf(r); nk = res?TRUE_LITERAL:FALSE_LITERAL; nv = String.valueOf(res); break; }
            case "==": { boolean eq = l.getKind()==INTEGER_LITERAL ? intOf(l)==intOf(r) : l.getKind()==r.getKind(); nk=eq?TRUE_LITERAL:FALSE_LITERAL; nv=String.valueOf(eq); break; }
            case "!=": { boolean ne = l.getKind()==INTEGER_LITERAL ? intOf(l)!=intOf(r) : l.getKind()!=r.getKind(); nk=ne?TRUE_LITERAL:FALSE_LITERAL; nv=String.valueOf(ne); break; }
            default: return;
        }
        n.replace(mkLit(nk, nv)); changed = true;
    }

    private void foldUnary(JmmNode n) {
        JmmNode c = n.getChild(0); if (!LITERALS.contains(c.getKind())) return;
        String op = n.get("op"); Kind nk; String nv;
        switch (op) {
            case "+": nk = INTEGER_LITERAL; nv = c.get("value"); break;
            case "-": nk = INTEGER_LITERAL; nv = String.valueOf(-intOf(c)); break;
            case "!": { boolean b = boolOf(c); nk = b?FALSE_LITERAL:TRUE_LITERAL; nv = String.valueOf(!b); break; }
            default: return;
        }
        n.replace(mkLit(nk, nv)); changed = true;
    }

    private void foldNot(JmmNode n) {
        JmmNode c = n.getChild(0);
        if (c.getKind()!=TRUE_LITERAL && c.getKind()!=FALSE_LITERAL) return;
        boolean b = boolOf(c);
        n.replace(mkLit(b?FALSE_LITERAL:TRUE_LITERAL, String.valueOf(!b))); changed = true;
    }

    // ── Constant propagation (forward per method) ─────────────────────────────

    private void propagateStmts(List<JmmNode> stmts, Map<String,JmmNode> consts, Set<String> locals) {
        for (JmmNode s : new ArrayList<>(stmts)) propStmt(s, consts, locals);
    }

    private void propStmt(JmmNode s, Map<String,JmmNode> consts, Set<String> locals) {
        Kind k = s.getKind();
        if (k == ASSIGN_STMT) {
            for (JmmNode ch : new ArrayList<>(s.getChildren())) propExpr(ch, consts);
            String var = s.get("var");
            JmmNode rhs = s.getChildren().getLast();
            if (locals.contains(var)) {
                if (LITERALS.contains(rhs.getKind())) consts.put(var, rhs);
                else consts.remove(var);
            }
        } else if (k == RETURN_STMT || k == SIMPLE_STMT) {
            for (JmmNode ch : new ArrayList<>(s.getChildren())) propExpr(ch, consts);
        } else if (k == IF_STMT) {
            List<JmmNode> ch = s.getChildren();
            propExpr(ch.get(0), consts);
            Map<String,JmmNode> tc = new HashMap<>(consts), ec = new HashMap<>(consts);
            propagateStmts(branchStmts(ch.get(1)), tc, locals);
            if (ch.size() > 2) propagateStmts(branchStmts(ch.get(2)), ec, locals);
            // After if: keep only consts with equal value in both branches
            consts.entrySet().removeIf(e -> !litEq(tc.get(e.getKey()), ec.get(e.getKey())));
        } else if (k == COMPLEX_STMT) {
            propagateStmts(new ArrayList<>(s.getChildren()), consts, locals);
        } else if (LOOP_KINDS.contains(k)) {
            collectModVars(s).forEach(consts::remove);
        }
    }

    private void propExpr(JmmNode n, Map<String,JmmNode> consts) {
        if (n.getKind() == VAR_REF_EXPR) {
            JmmNode lit = consts.get(n.get("name"));
            if (lit != null) { n.replace(copyLit(lit)); changed = true; }
            return;
        }
        for (JmmNode ch : new ArrayList<>(n.getChildren())) propExpr(ch, consts);
    }

    // ── Branch elimination (postorder) ────────────────────────────────────────

    private void elimBranchesRec(JmmNode n) {
        for (JmmNode ch : new ArrayList<>(n.getChildren())) elimBranchesRec(ch);
        if (n.getKind() == IF_STMT) tryElim(n);
    }

    private void tryElim(JmmNode ifNode) {
        JmmNode cond = ifNode.getChild(0);
        if (cond.getKind() != TRUE_LITERAL && cond.getKind() != FALSE_LITERAL) return;

        boolean takeThen = cond.getKind() == TRUE_LITERAL;
        JmmNode parent = ifNode.getParent();
        int idx = parent.removeChild(ifNode);

        JmmNode block = null;
        if (takeThen) { if (ifNode.getNumChildren() > 1) block = ifNode.getChild(1); }
        else          { if (ifNode.getNumChildren() > 2) block = ifNode.getChild(2); }

        if (block != null) {
            for (JmmNode s : new ArrayList<>(branchStmts(block))) {
                if (block.getKind() == COMPLEX_STMT) block.removeChild(s);
                parent.add(s, idx++);
            }
        }
        changed = true;
    }

    // ── Dead-code elimination (backward per method) ───────────────────────────

    private void dceStmtList(List<JmmNode> stmts, Set<String> live,
                              Set<String> locals, Set<String> params) {
        List<JmmNode> rev = new ArrayList<>(stmts);
        Collections.reverse(rev);
        for (JmmNode s : rev) dceStmt(s, live, locals, params);
    }

    private void dceStmt(JmmNode s, Set<String> live, Set<String> locals, Set<String> params) {
        Kind k = s.getKind();
        if (k == ASSIGN_STMT) {
            String var = s.get("var");
            if (locals.contains(var) && !live.contains(var) && !hasSideEffects(s)) {
                s.getParent().removeChild(s); changed = true; return;
            }
            live.remove(var);
            addRefs(s.getChildren(), live);
        } else if (k == RETURN_STMT || k == SIMPLE_STMT) {
            addRefs(s.getChildren(), live);
        } else if (k == IF_STMT) {
            List<JmmNode> ch = s.getChildren();
            Set<String> tl = new HashSet<>(live), el = new HashSet<>(live);
            if (ch.size() > 2) dceStmtList(new ArrayList<>(branchStmts(ch.get(2))), el, locals, params);
            dceStmtList(new ArrayList<>(branchStmts(ch.get(1))), tl, locals, params);
            live.addAll(tl); live.addAll(el);
            addRefs(List.of(ch.get(0)), live);
        } else if (k == COMPLEX_STMT) {
            dceStmtList(new ArrayList<>(s.getChildren()), live, locals, params);
        } else if (LOOP_KINDS.contains(k)) {
            addRefs(List.of(s), live);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<JmmNode> collectMethodDecls(JmmNode root) {
        return root.getDescendantsAndSelfStream()
                .filter(n -> n.getKind() == METHOD_DECL)
                .collect(Collectors.toList());
    }

    private List<JmmNode> methodStmts(JmmNode method) {
        return method.getChildren().stream()
                .filter(c -> c.getKind() != VAR_DECL && c.getKind() != PARAM
                        && c.getKind() != VISIBILITY_DECLARATION && !TYPE.check(c))
                .collect(Collectors.toList());
    }

    private List<JmmNode> branchStmts(JmmNode block) {
        return block.getKind() == COMPLEX_STMT
                ? new ArrayList<>(block.getChildren())
                : List.of(block);
    }

    private Set<String> localVarNames(JmmNode method) {
        return method.getChildren(VAR_DECL).stream()
                .map(n -> n.get("name")).collect(Collectors.toSet());
    }

    private Set<String> paramVarNames(JmmNode method) {
        return method.getChildren(PARAM).stream()
                .map(n -> n.get("name")).collect(Collectors.toSet());
    }

    private boolean hasSideEffects(JmmNode node) {
        return node.getDescendantsAndSelfStream()
                .anyMatch(n -> CALL_KINDS.contains(n.getKind()));
    }

    private void addRefs(List<JmmNode> nodes, Set<String> live) {
        for (JmmNode n : nodes)
            n.getDescendantsAndSelfStream()
                    .filter(x -> x.getKind() == VAR_REF_EXPR)
                    .map(x -> x.get("name"))
                    .forEach(live::add);
    }

    private Set<String> collectModVars(JmmNode node) {
        return node.getDescendantsAndSelfStream()
                .filter(n -> n.getKind() == ASSIGN_STMT)
                .map(n -> n.get("var"))
                .collect(Collectors.toSet());
    }

    private JmmNode copyLit(JmmNode lit) {
        JmmNode n = new JmmNodeImpl(lit.getKind());
        n.put("value", lit.getKind() == INTEGER_LITERAL
                ? lit.get("value")
                : String.valueOf(lit.getKind() == TRUE_LITERAL));
        return n;
    }

    private boolean litEq(JmmNode a, JmmNode b) {
        if (a == null || b == null) return false;
        if (a.getKind() != b.getKind()) return false;
        return a.getKind() != INTEGER_LITERAL || a.get("value").equals(b.get("value"));
    }

    private JmmNode mkLit(Kind k, String v) {
        JmmNode n = new JmmNodeImpl(k); n.put("value", v); return n;
    }

    private int intOf(JmmNode n) { return Integer.parseInt(n.get("value")); }
    private boolean boolOf(JmmNode n) { return n.getKind() == TRUE_LITERAL; }
}
