package pt.up.fe.comp2026.optimization;

import org.antlr.v4.codegen.SourceGenTriggers;
import org.specs.comp.ollir.Ollir;
import org.stringtemplate.v4.ST;
import pt.up.fe.comp.jmm.analysis.table.MethodSymbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.type.JmmType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmArrayType;
import pt.up.fe.comp.jmm.analysis.table.type.impls.JmmPrimitiveType;
import pt.up.fe.comp.jmm.ast.AJmmVisitor;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.utils.Attributes;
import pt.up.fe.comp2026.ast.AccessType;
import pt.up.fe.comp2026.ast.NodeUtils;
import pt.up.fe.comp2026.ast.TypeUtils;
import pt.up.fe.comp2026.jmm.ast.JmmAttributes;
import pt.up.fe.specs.util.utilities.StringLines;

import java.sql.Struct;
import java.util.stream.Collectors;

import static pt.up.fe.comp2026.jmm.ast.JmmKind.*;

/**
 * Generates OLLIR code from JmmNodes that are not expressions.
 */
public class OllirGeneratorVisitor extends AJmmVisitor<Void, String> {

    private static final String SPACE = " ";
    private static final String ASSIGN = ":=";
    private final String END_STMT = ";\n";
    private final String END_LABEL = ":\n";
    private final String NL = "\n";
    private final String L_BRACKET = " {\n";
    private final String R_BRACKET = "}\n";


    private final SymbolTable table;

    private final TypeUtils types;
    private final OptUtils ollirTypes;


    private final OllirExprGeneratorVisitor exprVisitor;

    private MethodSymbol currentMethod;

    public OllirGeneratorVisitor(SymbolTable table) {
        this.table = table;
        this.types = new TypeUtils(table);
        this.ollirTypes = new OptUtils(types);
        exprVisitor = new OllirExprGeneratorVisitor(table, ollirTypes);
        currentMethod = null;
    }


    @Override
    protected void buildVisitor() {

        addVisit(PROGRAM, this::visitProgram);
        addVisit(PACKAGE_DECL, this::visitPackageDecl);
        addVisit(CLASS_DECL, this::visitClass);
        addVisit(VAR_DECL, this::simpleVarDecl);
        addVisit(PARAM, this::visitParam);
        addVisit(METHOD_DECL, this::visitMethodDecl);
        addVisit(RETURN_STMT, this::visitReturn);
        addVisit(ASSIGN_STMT, this::visitAssignStmt);
        addVisit(IF_STMT, this::visitIfStmt);
        addVisit(WHILE_STMT, this::visitWhileStmt);
        addVisit(DO_STMT, this::visitDoStmt);
        addVisit(FOR_STMT, this::visitForStmt);
        setDefaultVisit(this::defaultVisit);
    }

    private String indent(String code) {
        return code.lines()
                .map(line -> line.isBlank() ? line : "   " + line)
                .collect(Collectors.joining("\n", "", "\n"));
    }


    private String visitIfStmt(JmmNode node, Void unused) {
        StringBuilder code = new StringBuilder();

        var children = node.getChildren();

        var condNode = children.get(0);
        var thenNode = children.get(1);
        var hasElse = children.size() > 2;
        var elseNode = hasElse ? children.get(2) : null;

        String trueLabel = ollirTypes.nextTemp("IF_TRUE");
        String endLabel  = ollirTypes.nextTemp("IF_END");

        // Evaluate condition
        var cond = exprVisitor.visit(condNode);
        code.append(cond.getComputation());

        // Conditional jump
        code.append("if (")
                .append(cond.getCode())
                .append(") goto ")
                .append(trueLabel)
                .append(END_STMT);

        // Else block
        if (hasElse) {
            code.append(indent(visit(elseNode)));
        }

        // Jump to end
        code.append("goto ")
                .append(endLabel)
                .append(END_STMT);

        // Then label
        code.append(trueLabel)
                .append(":\n");

        code.append(indent(visit(thenNode)));

        // End label
        code.append(endLabel)
                .append(":\n");

        return code.toString();
    }

    private String visitWhileStmt(JmmNode node, Void unused) {
        StringBuilder code = new StringBuilder();

        var children = node.getChildren();

        var condNode = children.get(0);
        var bodyNode = children.get(1);
        var cond = exprVisitor.visit(condNode);

        String condLabel = ollirTypes.nextTemp("WHILE_COND");
        String bodyLabel = ollirTypes.nextTemp("WHILE_BODY");
        String endLabel = ollirTypes.nextTemp("WHILE_END");

        code.append(condLabel)
                .append(":\n");

        // Evaluate condition
        code.append(cond.getComputation());

        // Conditional jump
        code.append("if (")
                .append(cond.getCode())
                .append(") goto ")
                .append(bodyLabel)
                .append(END_STMT);

        // Jump to end
        code.append("goto ")
                .append(endLabel)
                .append(END_STMT);

        code.append(bodyLabel)
                .append(":\n");

        // Loop body
        code.append(indent(visit(bodyNode)));

        // Jump back to condition
        code.append("goto ")
                .append(condLabel)
                .append(END_STMT);

        // End label
        code.append(endLabel)
                .append(":\n");

        return code.toString();
    }

    private String visitDoStmt(JmmNode node, Void unused) {
        StringBuilder code = new StringBuilder();

        var children = node.getChildren();

        var bodyNode = children.get(0);
        var condNode = children.get(1);
        var cond = exprVisitor.visit(condNode);

        String bodyLabel = ollirTypes.nextTemp("DO_BODY");
        String endLabel = ollirTypes.nextTemp("DO_END");

        code.append(bodyLabel)
                .append(":\n");

        // Loop body
        code.append(indent(visit(bodyNode)));

        // Evaluate condition
        code.append(cond.getComputation());

        // Conditional jump
        code.append("if (")
                .append(cond.getCode())
                .append(") goto ")
                .append(bodyLabel)
                .append(END_STMT);

        // End label
        code.append(endLabel)
                .append(":\n");

        return code.toString();
    }

    private String visitForStmt(JmmNode node, Void unused) {
        StringBuilder code = new StringBuilder();

        var children = node.getChildren();

        var initNode = (children.get(0)).getChild(0);
        var condNode = (children.get(1)).getChild(0);
        var updateNode = (children.get(2)).getChild(0);
        var bodyNode = children.get(3);

        var cond = exprVisitor.visit(condNode);

        String condLabel = ollirTypes.nextTemp("FOR_COND");
        String bodyLabel = ollirTypes.nextTemp("FOR_BODY");
        String updateLabel = ollirTypes.nextTemp("FOR_UPDATE");
        String endLabel = ollirTypes.nextTemp("FOR_END");

        // Initialization
        code.append(indent(visit(initNode)));

        code.append(condLabel)
                .append(":\n");

        // Evaluate condition
        code.append(cond.getComputation());

        // Conditional jump
        code.append("if (")
                .append(cond.getCode())
                .append(") goto ")
                .append(bodyLabel)
                .append(END_STMT);

        // Jump to end
        code.append("goto ")
                .append(endLabel)
                .append(END_STMT);

        code.append(bodyLabel)
                .append(":\n");

        // Loop body
        code.append(indent(visit(bodyNode)));

        code.append(updateLabel)
                .append(":\n");

        // Update
        code.append(indent(visit(updateNode)));

        // Jump back to condition
        code.append("goto ")
                .append(condLabel)
                .append(END_STMT);

        // End label
        code.append(endLabel)
                .append(":\n");

        return code.toString();
    }


    private String simpleVarDecl(JmmNode varDecl, Void unused) {
        StringBuilder code = new StringBuilder();

        code.append(".field ");
        code.append(ollirTypes.sanitizeId(varDecl.get("name")));

        var type = ollirTypes.toOllirType(varDecl.getChild(0));
        code.append(type);
        code.append(END_STMT);

        return code.toString();
    }


    private String visitParam(JmmNode param, Void unused) {

        String name = ollirTypes.sanitizeId(param.get("name"));

        // Get parameter type from symbol table
        var type = currentMethod.getParameter(param.get("name")).orElseThrow().type();

        return name + ollirTypes.toOllirType(type);
    }




    private String visitPackageDecl(JmmNode packageDecl, Void unused) {
        return "package " + String.join(".", packageDecl.getObjectAsList("path", String.class)) + ";\n";
    }


    private String visitAssignStmt(JmmNode node, Void unused) {
        // TODO: Several hard-coded things, should be rewritten

        var rhs = exprVisitor.visit(node.getChildren().getLast());

        // code to compute self
        // statement has type of lhs
        var varName = node.get(JmmAttributes.ASSIGN_STMT.VAR);
        JmmType thisType = types.getVarType(node.get("var"), currentMethod);


        String typeString = ollirTypes.toOllirType(thisType);
        StringBuilder varCode = new StringBuilder();
        varCode.append(ollirTypes.sanitizeId(varName))
                .append(typeString);

        // if array
        OllirExprResult lhs = null;
        if (node.getChildren().size() > 1){
            lhs = exprVisitor.visit(node.getChild(0));

            varCode.append("[")
                    .append(lhs.getCode())
                    .append("]")
                    .append(ollirTypes.toOllirType(thisType.asArray().itemType()));
        }

        var code = new StringBuilder();

        // code to compute the children
        code.append(rhs.getComputation());
        if (lhs != null)
            code.append(lhs.getComputation());

        code.append(varCode);
        code.append(SPACE);

        code.append(ASSIGN);
        code.append(typeString);
        code.append(SPACE);

        code.append(rhs.getCode());

        code.append(END_STMT);

        return code.toString();
    }

    private String visitReturn(JmmNode node, Void unused) {

        StringBuilder code = new StringBuilder();

        // Case 1: return; (void)
        if (node.getNumChildren() == 0) {
            code.append("ret.V");
            code.append(END_STMT);
            return code.toString();
        }

        // Case 2: return <expr>;
        JmmNode exprNode = node.getChild(0);

        JmmType retType = TypeUtils.with(table).getExprType(exprNode);
        var expr = exprVisitor.visit(exprNode);

        // Emit expression computation (temporaries, calls, etc.)
        code.append(expr.getComputation());

        // ret.<type> <value>;
        code.append("ret");
        code.append(ollirTypes.toOllirType(retType));
        code.append(SPACE);
        code.append(expr.getCode());
        code.append(END_STMT);

        return code.toString();
    }

    private String visitMethodDecl(JmmNode node, Void unused) {

        currentMethod = table.getMethod(TypeUtils.with(table).getMethodDeclSignature(node)).orElseThrow();

        StringBuilder code = new StringBuilder(".method ");

        boolean isPublic = NodeUtils.getBooleanAttribute(node, "isPublic", "false");

        if (isPublic) {
            code.append("public ");
        }

        if (node.getObject("isStatic", Boolean.class)) {
            code.append("static ");
        }

        // name
        var name = ollirTypes.sanitizeId(node.get("name"));
        code.append(name);

        // params

        String paramsCode = currentMethod.parameters().stream()
                .map(p -> ollirTypes.sanitizeId(p.name())
                        + ollirTypes.toOllirType(p.type()))
                .collect(Collectors.joining(", "));


        code.append("(");
        code.append(paramsCode);
        code.append(")");

        // type
        var retType = ollirTypes.toOllirType(currentMethod.returnType());//table.getReturnType(node.get("name")));
        code.append(retType);
        code.append(L_BRACKET);

        for (var child : node.getChildren()) {
            switch (child.getKind()) {
                case VISIBILITY_DECLARATION:
                case SIMPLE_TYPE:
                case PARAM:
                case VAR_DECL:
                    // metadata → skip
                    break;
                default:
                    code.append("   ").append(visit(child));
            }
        }

        code.append(NL);
        code.append(R_BRACKET);
        code.append(NL);

        currentMethod = null;

        return code.toString();
    }

    private String visitClass(JmmNode node, Void unused) {

        StringBuilder code = new StringBuilder();

        code.append(NL);
        code.append(table.getClassName());

        code.append(L_BRACKET);
        code.append(NL);
        code.append(NL);

        for (var field : node.getChildren(VAR_DECL)) {
            code.append(visit(field));
        }

        code.append(NL);

        code.append(buildConstructor());
        code.append(NL);

        for (var child : node.getChildren(METHOD_DECL)) {
            var result = visit(child);
            code.append(result);
        }

        code.append(R_BRACKET);

        return code.toString();
    }

    private String buildConstructor() {
        return """
                .construct %s().V {
                    invokespecial(this, "<init>").V;
                }
                """.formatted(table.getClassName());
    }

    private String visitProgram(JmmNode node, Void unused) {

        StringBuilder code = new StringBuilder();

        node.getChildren().stream().map(this::visit).forEach(code::append);

        return code.toString();
    }

    /**
     * Default visitor. Visits every child node and return an empty string.
     *
     * @param node
     * @param unused
     * @return
     */
    private String defaultVisit(JmmNode node, Void unused) {
        StringBuilder code = new StringBuilder();

        for (var child : node.getChildren()) {
            code.append(visit(child));
        }

        return code.toString();
    }
}
