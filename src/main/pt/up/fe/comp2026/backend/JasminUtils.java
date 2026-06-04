package pt.up.fe.comp2026.backend;

import org.specs.comp.ollir.*;
import org.specs.comp.ollir.type.*;
import pt.up.fe.comp.jmm.analysis.table.reflection.Importer;
import pt.up.fe.comp.jmm.ollir.OllirResult;
import pt.up.fe.specs.util.SpecsCheck;
import pt.up.fe.specs.util.exceptions.NotImplementedException;

import java.util.HashMap;
import java.util.Map;

public class JasminUtils {

    private final OllirResult ollirResult;

    private final Map<String, String> fullClassnames;

    private final Importer importer;

    public JasminUtils(OllirResult ollirResult) {
        this.ollirResult = ollirResult;
        this.importer = Importer.fromThisClassPath();
        // Build imports table
        fullClassnames = new HashMap<>();

        // Predefined classnames
        fullClassnames.put("this", ollirResult.getOllirClass().getClassName());
        // This will be get caught as STRING OLLIR element type.
        // And classes cannot be named String, since it is an OLLIR reserved keyword
        //imports.put("String", "java/lang/String");

        for (var fullImport : ollirResult.getOllirClass().getImports()) {
            var splitted = fullImport.split("\\.");

            // Last element will be the key
            var key = splitted[splitted.length - 1];
            fullClassnames.put(key, fullImport.replace('.', '/'));
        }
    }



    public String getTypePrefix(Type type) {
        if (type instanceof ArrayType) {
            return "a";
        }
        if (type instanceof BuiltinType builtinType) {
            return switch (builtinType.getKind()) {
                case INT32, BOOLEAN -> "i";
                case VOID -> "";
                default -> "a";
            };
        }
        return "a";
    }

    public String getTypeDescriptor(Type type) {

        if (type instanceof ArrayType arrayType) {
            return "[".repeat(arrayType.getNumDimensions()) + getTypeDescriptor(arrayType.getElementType());
        }

        if (type instanceof BuiltinType builtinType) {
            return switch (builtinType.getKind()) {
                case INT32 -> "I";
                case BOOLEAN -> "Z";
                case VOID -> "V";
                case STRING -> "Ljava/lang/String;";
                default ->
                        throw new RuntimeException("Not implemented for element type '" + builtinType.getKind() + "'");
            };
        }

        // Class type
        if (type instanceof ClassType classType) {
            return "L" + getFullClassName(classType.getName()) + ";";
        }

        return "L" + getFullClassName(type.getNodeName()) + ";";
    }




    public String getModifier(AccessModifier accessModifier) {
        if (accessModifier == AccessModifier.DEFAULT) return "";
        return accessModifier.name().toLowerCase() + " ";
    }

    public String getFieldModifier(AccessModifier accessModifier) {
        // Jasmin requires at least one access keyword; DEFAULT maps to public (package-private unsupported)
        return switch (accessModifier) {
            case PRIVATE -> "private";
            case PROTECTED -> "protected";
            default -> "public"; // PUBLIC and DEFAULT both use "public"
        };
    }

    public String getLoad(Descriptor reg) {
        var prefix = getTypePrefix(reg.getVarType());
        var value = reg.getVirtualReg();

        return prefix + "load " + value;
    }

    public String getStore(Descriptor reg) {
        var prefix = getTypePrefix(reg.getVarType());
        var value = reg.getVirtualReg();

        return prefix + "store " + value;
    }

    public String getFullClassName(String className) {
        // Already a dotted qualified name (e.g. "java.lang.Object" from superClass)
        if (className.contains(".")) {
            return className.replace('.', '/');
        }

        // 'this' class
        if (className.equals(
                ollirResult.getOllirClass().getClassName())) {

            return ollirResult.getOllirClass()
                    .getClassFullyQualifiedName()
                    .replace('.', '/');
        }

        // java.lang implicit classes
        switch (className) {
            case "Object":
                return "java/lang/Object";
            case "Exception":
                return "java/lang/Exception";
            case "RuntimeException":
                return "java/lang/RuntimeException";
            case "String":
                return "java/lang/String";
        }

        // imported classes
        for (String imp : ollirResult.getOllirClass().getImports()) {

            // Example:
            // import java.util.List;
            // simpleName = List
            String simpleName =
                    imp.substring(imp.lastIndexOf('.') + 1);

            if (simpleName.equals(className)) {
                return imp.replace('.', '/');
            }
        }

        // fallback: assume same package / already qualified
        return className.replace('.', '/');
    }

}