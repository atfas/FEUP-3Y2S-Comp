package pt.up.fe.comp2026.analysis;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.Stage;
import pt.up.fe.comp2026.ast.NodeUtils;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import java.util.Optional;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class VariableInitializerVisitor extends AnalysisVisitorWithTable {

    private MethodSymbol activeMethod;

    public VariableInitializerVisitor(SymbolTable table) {
        super(table);
    }

    @Override
    protected void buildVisitor() {
        addVisit(METHOD_DECL, this::trackMethod);
        addVisit(VAR_DECL,    this::checkDeclInit);
        addVisit(ASSIGN_STMT, this::checkAssignTypes);
    }

    private Void trackMethod(JmmNode method, SymbolTable table) {
        var sig = TypeUtils.with(table).getMethodDeclSignature(method);
        activeMethod = table.getMethod(sig).orElseThrow();

        if (!activeMethod.returnType().equals(JmmPrimitiveType.VOID)
                && method.getDescendants(RETURN_STMT).isEmpty()) {
            addReport(Report.newError(
                    Stage.SEMANTIC,
                    NodeUtils.getLine(method),
                    NodeUtils.getColumn(method),
                    "Method '" + method.get("name") + "' has a non-void return type but never returns",
                    null
            ));
        }
        return null;
    }

    private Void checkDeclInit(JmmNode decl, SymbolTable table) {
        if (decl.getChildren().toArray().length < 2) return null;

        var declaredType = TypeUtils.with(table).convertType(decl.getChild(0));
        var initType     = TypeUtils.with(table).getExprType(decl.getChild(1));

        if (!typesAreCompatible(declaredType, initType)) {
            addReport(newError(decl,
                    "Initializer type '" + initType + "' does not match declared type '" + declaredType + "'"));
        }
        return null;
    }

    // Calls on imported classes are always trusted — we can't inspect their signatures
    private boolean rhsIsImportedCall(JmmNode expr, SymbolTable table) {
        if (!POSTFIX_EXPR.check(expr) || expr.getNumChildren() < 2) return false;
        if (!OBJECT_CALL_EXPR.check(expr.getChild(1))) return false;

        JmmNode base = expr.getChild(0);
        if (!VAR_REF_EXPR.check(base)) return false;

        String baseName = base.get("name");
        return table.getImports().stream()
                .anyMatch(imp -> imp.equals(baseName) || imp.endsWith("." + baseName));
    }

    private Void checkAssignTypes(JmmNode assign, SymbolTable table) {
        int lastIdx  = assign.getNumChildren() - 1;
        JmmNode rhs  = assign.getChild(lastIdx);

        if (rhsIsImportedCall(rhs, table)) return null;

        var lhsType  = TypeUtils.with(table).getVarType(assign.get("var"), activeMethod);
        var rhsType  = TypeUtils.with(table).getExprType(rhs);
        int numIndices = lastIdx;

        if (numIndices > 0) {
            for (int i = 0; i < numIndices; i++) {
                JmmNode idxNode  = assign.getChild(i);
                var idxType = TypeUtils.with(table).getExprType(idxNode);

                if (!idxType.equals(TypeUtils.intType())) {
                    addReport(newError(idxNode,
                            "Array subscript must be int, got '" + idxType + "'"));
                }

                if (lhsType instanceof JmmArrayType at) {
                    int left = at.dimension() - 1;
                    lhsType = left <= 0 ? at.itemType() : JmmArrayType.of(at.itemType(), left);
                } else {
                    addReport(newError(assign, "Subscript applied to non-array type"));
                    return null;
                }
            }
        }

        if (!typesAreCompatible(lhsType, rhsType)) {
            addReport(newError(assign,
                    "Type mismatch: '" + rhsType + "' cannot be assigned to '" + lhsType + "'"));
        }
        return null;
    }

    private boolean typesAreCompatible(JmmType target, JmmType source) {
        // Imported types are treated as opaque — skip deep checking
        if (target instanceof JmmClassType ct && isKnownImport(ct)
                && !ct.name().equals(table.getClassName())
                && !ct.name().equals(table.getFullyQualifiedName())) return true;
        if (source instanceof JmmClassType ct && isKnownImport(ct)
                && !ct.name().equals(table.getClassName())
                && !ct.name().equals(table.getFullyQualifiedName())) return true;

        if (target instanceof JmmPrimitiveType && source instanceof JmmPrimitiveType)
            return target.equals(source);

        if (target instanceof JmmArrayType && source instanceof JmmArrayType)
            return target.equals(source);

        if (target instanceof JmmClassType tClass && source instanceof JmmClassType sClass) {
            if (target.equals(source))
                return isStdLib(tClass) || tClass.name().equals(table.getClassName());

            // Allow assigning 'this' to a compatible supertype
            boolean sourceIsThis = sClass.name().equals(table.getFullyQualifiedName())
                    || sClass.name().equals(table.getClassName());

            if (sourceIsThis) {
                boolean targetIsCurrentClass = tClass.name().equals(table.getClassName())
                        || tClass.name().equals(table.getFullyQualifiedName());
                if (targetIsCurrentClass) return true;

                String superFqn = table.getSuperFullyQualifiedName();
                if (superFqn == null || superFqn.isEmpty()) return false;

                Class<?> tResolved = tryLoadClass(tClass.name());
                Class<?> sResolved = tryLoadClass(superFqn);
                return tResolved != null && sResolved != null && tResolved.isAssignableFrom(sResolved);
            }

            Class<?> tResolved = tryLoadClass(tClass.name());
            Class<?> sResolved = tryLoadClass(sClass.name());
            return tResolved != null && sResolved != null && tResolved.isAssignableFrom(sResolved);
        }

        return false;
    }

    private boolean isKnownImport(JmmClassType type) {
        String name = type.name();
        if (name.equals(table.getClassName())) return true;
        return table.getImports().stream()
                .anyMatch(imp -> imp.equals(name) || imp.endsWith("." + name));
    }

    private boolean isStdLib(JmmClassType type) {
        try { Class.forName("java.lang." + type.name()); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    private Class<?> tryLoadClass(String name) {
        var fqn = table.getImports().stream()
                .filter(imp -> imp.equals(name) || imp.endsWith("." + name))
                .findFirst();
        try {
            return fqn.isPresent() ? Class.forName(fqn.get()) : Class.forName("java.lang." + name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
