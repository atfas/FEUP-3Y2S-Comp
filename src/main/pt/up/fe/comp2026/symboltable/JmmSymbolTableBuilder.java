package pt.up.fe.comp2026.symboltable;

import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.Symbol;
import pt.up.fe.comp.jmm.analysis.table.Visibility;
import pt.up.fe.comp.jmm.analysis.table.reflection.Importer;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmClassType;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.Stage;
import pt.up.fe.comp.jmm.utils.Attributes;
import pt.up.fe.comp2026.ast.NodeUtils;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.jmm.ast.JmmAttributes;
import pt.up.fe.specs.util.SpecsCheck;

import java.util.*;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

public class JmmSymbolTableBuilder {

    private final JmmNode root;
    private final Importer importer;
    public String className;
    private final List<Report> reports;
    private final List<String> imports;
    private final Map<String, String> declaredClasses;
    private String fullyQualifiedName;

    /**
     * Only build() can create new instances, this ensures that each instance is used only once,
     * and we do not have to worry about "cleaning state".
     */
    private JmmSymbolTableBuilder(JmmNode root) {
        this.root = root;
        reports = new ArrayList<>();
        imports = new ArrayList<>();
        declaredClasses = new HashMap<>();
        this.importer = Importer.fromThisClassPath();
    }

    private static Report newError(JmmNode node, String message) {
        return Report.newError(
                Stage.SEMANTIC,
                NodeUtils.getLine(node),
                NodeUtils.getColumn(node),
                message,
                null);
    }

    public static SymbolTableBuilderResult build(JmmNode root) {
        return new JmmSymbolTableBuilder(root).buildInternal(root);
    }

    private SymbolTableBuilderResult buildInternal(JmmNode root) {

        var packagePath = buildPackage(root);
        buildImports(root);

        var classDecl = root.getObject("classNode", JmmNode.class);
        SpecsCheck.checkArgument(CLASS_DECL.check(classDecl), () -> "Expected a class declaration: " + classDecl);

        this.className = classDecl.get("name");
        this.fullyQualifiedName = packagePath + "." + className;

        // Check if className is available
        if (declaredClasses.containsKey(className)) {
            reports.add(newError(root, "'" + className + "' is already defined in this compilation unit"));
        }
        declaredClasses.put(className, fullyQualifiedName);

        String superQualifiedName = classDecl.getOptional("parent").orElse("Object");

        if (classDecl.hasAttribute("parent")) {
            var parentName = classDecl.get("parent");

            superQualifiedName = imports.stream()
                    .filter(i -> i.equals(parentName) || i.endsWith("." + parentName))
                    .findFirst()
                    .orElse(parentName);

            // Validate that parent is imported or implicitly available (java.lang.*)
            boolean isImported = imports.stream()
                    .anyMatch(i -> i.equals(parentName) || i.endsWith("." + parentName));
            boolean isImplicit = importer.isImplicitImport(parentName);

            if (!isImported && !isImplicit) {
                reports.add(newError(classDecl,
                        "Parent class '" + parentName + "' is not imported"));
            }
        }

        // Check if class has same name as super class
        if (superQualifiedName != null && superQualifiedName.equals(className)) {
            reports.add(newError(classDecl, "Class cannot extend itself: " + className));
        }

        var methods = buildMethods(classDecl);

        var fields = buildFields(classDecl);

        var symbolTable = new JmmSymbolTable(imports, fullyQualifiedName, superQualifiedName, fields, methods, importer);

        return new SymbolTableBuilderResult(symbolTable, reports);
    }

    private List<Symbol> buildParams(JmmNode method) {
        List<Symbol> params = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (var param : method.getChildren(PARAM)) {
            var typeNode = param.getChildren().stream().filter(TYPE::check).findFirst().orElseThrow();
            var type = TypeUtils.convertType(typeNode, className, fullyQualifiedName, imports);
            String name = param.get(JmmAttributes.PARAM.NAME);

            if (type.equals(JmmPrimitiveType.VOID)) {
                reports.add(newError(param, "Parameter '" + name + "' cannot have type void"));
            }
            if (!names.add(name)) {
                reports.add(newError(param, "Duplicate parameter name: " + name));
            }
            params.add(new Symbol(type, name));
        }

        return params;
    }

    private List<Symbol> buildLocals(JmmNode method) {
        List<Symbol> locals = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (var local : method.getChildren(VAR_DECL)) {
            var typeNode = local.getChildren().stream().filter(TYPE::check).findFirst().orElseThrow();
            var type = TypeUtils.convertType(typeNode, className, fullyQualifiedName, imports);
            String name = local.get(JmmAttributes.VAR_DECL.NAME);

            if (type.equals(JmmPrimitiveType.VOID)) {
                reports.add(newError(local, "Local variable '" + name + "' cannot have type void"));
            }
            if (!names.add(name)) {
                reports.add(newError(local, "Duplicate local variable: " + name));
            }
            locals.add(new Symbol(type, name));
        }

        return locals;
    }

    private static String buildPackage(JmmNode root) {
        var packageDecl = root.getChildren(PACKAGE_DECL).getFirst();
        var packagePathList = packageDecl.getObjectAsList("path", String.class);

        return String.join(".", packagePathList);
    }

    private void buildImports(JmmNode root) {
        for (var importDecl : root.getChildren(IMPORT_DECL)) {

            var importPathList = importDecl.getObjectAsList("path", String.class);

            if (importPathList.size() < 2) {
                reports.add(newError(importDecl, "Import must be fully qualified (e.g. 'import java.util.List')"));
                continue;
            }

            var path = String.join(".", importPathList);

            if (!imports.contains(path)) {
                imports.add(path);
            }
        }
    }

    private Visibility buildVisibility(JmmNode method) {
        Visibility visibility = Visibility.PACKAGE_PROTECTED;

        for (var visibilityDecl : method.getChildren(VISIBILITY_DECLARATION)) {
            var vis = visibilityDecl.get(JmmAttributes.VISIBILITY_DECLARATION.VISIBILITY);

            visibility = switch (vis) {
                case "public" -> Visibility.PUBLIC;
                case "private" -> Visibility.PRIVATE;
                case "protected" -> Visibility.PROTECTED;
                default -> visibility;
            };
        }

    return visibility;
    }

    private List<Symbol> buildFields(JmmNode classDecl) {
        List<Symbol> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (var child : classDecl.getChildren()) {
            if (!VAR_DECL.check(child)) {
                continue;
            }

            var typeNode = child.getChildren().stream().filter(TYPE::check).findFirst().orElseThrow();
            var type = TypeUtils.convertType(typeNode, className, fullyQualifiedName, imports);
            var name = child.get(JmmAttributes.VAR_DECL.NAME);

            if (type.equals(JmmPrimitiveType.VOID)) {
                reports.add(newError(child, "Field '" + name + "' cannot have type void"));
            }
            if (!names.add(name)) {
                reports.add(newError(child, "Duplicate field name: " + name));
            }
            fields.add(new Symbol(type, name));
        }

        return fields;
    }


    private List<MethodSymbol> buildMethods(JmmNode classDecl) {
        Set<String> signatures = new HashSet<>();
        List<MethodSymbol> methods = new ArrayList<>();

        for (var methodNode : classDecl.getChildren(METHOD_DECL)) {
            var method = buildMethod(methodNode);
            String sig = method.name() + "(" +
                    method.parameters().stream()
                            .map(p -> p.type().toString())
                            .collect(java.util.stream.Collectors.joining(","))
                    + ")";
            if (!signatures.add(sig)) {
                reports.add(newError(methodNode, "Duplicate method signature: " + sig));
            }
            methods.add(method);
        }

        return methods;
    }

    private MethodSymbol buildMethod(JmmNode method) {
        var methodName = method.get("name");

        var returnType = TypeUtils.convertType(method.getObject("returnType", JmmNode.class), className, fullyQualifiedName, imports);

        var params = buildParams(method);

        var locals = buildLocals(method);

        Set<String> names = new HashSet<>();

        for (var param : params){
            names.add(param.name());
        }
        for (var local : locals){
            if (!names.add(local.name())){
                reports.add(newError(method, "Duplicate variable: " + local.name()));
            }
        }

        var visibility =  buildVisibility(method);

        var isStatic = method.getBoolean(JmmAttributes.METHOD_DECL.IS_STATIC, false);
        return new MethodSymbol(methodName, returnType, params, locals, isStatic, visibility);
    }

}
