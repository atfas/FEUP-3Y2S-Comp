package pt.up.fe.comp2026.optimization;

import org.specs.comp.ollir.*;
import org.specs.comp.ollir.inst.*;
import pt.up.fe.comp.jmm.analysis.JmmSemanticsResult;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ollir.JmmOptimization;
import pt.up.fe.comp.jmm.ollir.OllirResult;

import java.util.*;

public class JmmOptimizationImpl implements JmmOptimization {

    @Override
    public OllirResult toOllir(JmmSemanticsResult semanticsResult) {
        semanticsResult = optimize(semanticsResult);
        var visitor = new OllirGeneratorVisitor(semanticsResult.getSymbolTable());
        var ollirCode = visitor.visit(semanticsResult.getRootNode());
        return new OllirResult(semanticsResult, ollirCode, Collections.emptyList());
    }

    @Override
    public JmmSemanticsResult optimize(JmmSemanticsResult semanticsResult) {
        JmmNode root = semanticsResult.getRootNode();
        AstOptimizer opt = new AstOptimizer();

        // Always strip structural wrappers (FULL_ASSIGN_STMT etc.) — required for OLLIR gen
        opt.stripWrappers(root);

        // Run optimisation passes to fixpoint only when -o flag is set
        if ("true".equals(semanticsResult.config().getOrDefault("optimize", "false"))) {
            boolean changed = true;
            while (changed) {
                changed = opt.runPass(root);
            }
        }

        return semanticsResult;
    }

    @Override
    public OllirResult transformOllir(OllirResult ollirResult) {
        var config = ollirResult.config();

        String registerStr = config.getOrDefault("registerAllocation", "-1");
        int maxRegisters;
        try {
            maxRegisters = Integer.parseInt(registerStr);
        } catch (NumberFormatException e) {
            maxRegisters = -1;
        }

        if (maxRegisters < 0) {
            return ollirResult;
        }

        ClassUnit classUnit = ollirResult.getOllirClass();
        classUnit.buildVarTables();
        classUnit.buildCFGs();

        for (Method method : classUnit.getMethods()) {
            if (method.isConstructMethod()) continue;
            allocateRegisters(method, maxRegisters);
        }

        return ollirResult;
    }

    private void allocateRegisters(Method method, int maxRegisters) {
        List<Instruction> instructions = method.getInstructions();
        if (instructions.isEmpty()) return;

        Map<String, Descriptor> varTable = method.getVarTable();

        Set<String> localVars = new LinkedHashSet<>();
        for (Map.Entry<String, Descriptor> e : varTable.entrySet()) {
            if (e.getKey().equals("this")) continue;
            localVars.add(e.getKey());
        }

        if (localVars.isEmpty()) return;

        int n = instructions.size();
        List<Set<String>> def = new ArrayList<>();
        List<Set<String>> use = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            def.add(new LinkedHashSet<>());
            use.add(new LinkedHashSet<>());
        }

        for (int i = 0; i < n; i++) {
            computeDefUse(instructions.get(i), localVars, def.get(i), use.get(i));
        }

        List<Set<Integer>> succ = new ArrayList<>();
        for (int i = 0; i < n; i++) succ.add(new LinkedHashSet<>());

        Map<Instruction, Integer> instIndex = new HashMap<>();
        for (int i = 0; i < n; i++) instIndex.put(instructions.get(i), i);

        for (int i = 0; i < n; i++) {
            Node node = instructions.get(i);
            for (Node s : node.getSuccessors()) {
                if (s instanceof Instruction si) {
                    Integer idx = instIndex.get(si);
                    if (idx != null) succ.get(i).add(idx);
                }
            }
            if (succ.get(i).isEmpty() && i + 1 < n) succ.get(i).add(i + 1);
        }

        List<Set<String>> liveIn = new ArrayList<>();
        List<Set<String>> liveOut = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            liveIn.add(new LinkedHashSet<>());
            liveOut.add(new LinkedHashSet<>());
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                Set<String> newOut = new LinkedHashSet<>();
                for (int s : succ.get(i)) newOut.addAll(liveIn.get(s));

                Set<String> newIn = new LinkedHashSet<>(use.get(i));
                Set<String> outMinusDef = new LinkedHashSet<>(newOut);
                outMinusDef.removeAll(def.get(i));
                newIn.addAll(outMinusDef);

                if (!newIn.equals(liveIn.get(i)) || !newOut.equals(liveOut.get(i))) {
                    liveIn.set(i, newIn);
                    liveOut.set(i, newOut);
                    changed = true;
                }
            }
        }

        Map<String, Set<String>> interference = new LinkedHashMap<>();
        for (String v : localVars) interference.put(v, new LinkedHashSet<>());

        for (int i = 0; i < n; i++) {
            Set<String> live = liveOut.get(i);
            Set<String> defs = def.get(i);

            List<String> liveList = new ArrayList<>(live);
            for (int a = 0; a < liveList.size(); a++) {
                for (int b = a + 1; b < liveList.size(); b++) {
                    String va = liveList.get(a);
                    String vb = liveList.get(b);
                    if (localVars.contains(va) && localVars.contains(vb)) {
                        interference.get(va).add(vb);
                        interference.get(vb).add(va);
                    }
                }
            }

            for (String d : defs) {
                if (!localVars.contains(d)) continue;
                for (String l : live) {
                    if (!localVars.contains(l) || l.equals(d)) continue;
                    interference.get(d).add(l);
                    interference.get(l).add(d);
                }
            }
        }

        int paramCount = method.getParams().size();
        int firstLocalSlot = method.isStaticMethod() ? paramCount : paramCount + 1;

        // Pre-color parameters
        Map<String, Integer> coloring = new HashMap<>();
        int paramSlot = method.isStaticMethod() ? 0 : 1;
        List<Element> params = method.getParams();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i) instanceof Operand op) {
                String pname = op.getName();
                if (localVars.contains(pname)) {
                    coloring.put(pname, paramSlot + i);
                }
            }
        }

        // Non-param locals to color
        Set<String> nonParams = new LinkedHashSet<>(localVars);
        for (Element p : params) {
            if (p instanceof Operand op) nonParams.remove(op.getName());
        }

        // Determine how many local color slots to allow.
        // For maxRegisters > 0: try the requested budget; if infeasible, fall back to minimum.
        // For maxRegisters == 0: use minimum coloring.
        int localColors;
        if (maxRegisters == 0) {
            localColors = computeMinColors(nonParams, interference, coloring, firstLocalSlot);
        } else {
            int requested = maxRegisters - firstLocalSlot;
            if (requested <= 0) requested = 0;
            // Try with the requested budget; if infeasible, use minimum needed
            Map<String, Integer> probe = new HashMap<>(coloring);
            boolean fits = requested > 0 && colorGraph(nonParams, interference, probe, requested, firstLocalSlot);
            if (fits) {
                coloring.putAll(probe);
                applyColoring(coloring, varTable);
                return;
            }
            // Fall back to minimum coloring
            localColors = computeMinColors(nonParams, interference, coloring, firstLocalSlot);
        }

        colorGraph(nonParams, interference, coloring, localColors, firstLocalSlot);
        applyColoring(coloring, varTable);
    }

    /** Greedy minimum coloring: returns the minimum number of colors needed and populates coloring. */
    private int computeMinColors(Set<String> nonParams, Map<String, Set<String>> interference,
                                 Map<String, Integer> coloring, int firstLocalSlot) {
        int maxSlot = firstLocalSlot - 1;
        for (String var : nonParams) {
            Set<Integer> used = new LinkedHashSet<>();
            for (String nb : interference.get(var)) {
                if (coloring.containsKey(nb)) used.add(coloring.get(nb));
            }
            int slot = firstLocalSlot;
            while (used.contains(slot)) slot++;
            coloring.put(var, slot);
            if (slot > maxSlot) maxSlot = slot;
        }
        return (maxSlot < firstLocalSlot) ? 0 : (maxSlot - firstLocalSlot + 1);
    }

    private void applyColoring(Map<String, Integer> coloring, Map<String, Descriptor> varTable) {
        for (Map.Entry<String, Integer> entry : coloring.entrySet()) {
            Descriptor desc = varTable.get(entry.getKey());
            if (desc != null) desc.setVirtualReg(entry.getValue());
        }
    }

    private boolean colorGraph(Set<String> vars, Map<String, Set<String>> interference,
                               Map<String, Integer> coloring, int maxColors, int startSlot) {
        for (String var : vars) {
            Set<Integer> usedColors = new LinkedHashSet<>();
            for (String neighbor : interference.get(var)) {
                if (coloring.containsKey(neighbor)) usedColors.add(coloring.get(neighbor));
            }

            int color = startSlot;
            while (usedColors.contains(color)) color++;

            if (color >= startSlot + maxColors) {
                return false;
            }

            coloring.put(var, color);
        }
        return true;
    }

    private void computeDefUse(Instruction inst, Set<String> localVars,
                               Set<String> def, Set<String> use) {
        switch (inst.getInstType()) {
            case ASSIGN -> {
                AssignInstruction assign = (AssignInstruction) inst;
                addUsesFromInstruction(assign.getRhs(), localVars, use);
                Element dest = assign.getDest();
                if (dest instanceof ArrayOperand arrOp) {
                    if (localVars.contains(arrOp.getName())) use.add(arrOp.getName());
                    for (Element idx : arrOp.getIndexOperands()) addUse(idx, localVars, use);
                } else if (dest instanceof Operand op && localVars.contains(op.getName())) {
                    def.add(op.getName());
                }
            }
            case CALL -> {
                CallInstruction call = (CallInstruction) inst;
                addUse(call.getCaller(), localVars, use);
                if (call.getArguments() != null)
                    for (Element arg : call.getArguments()) addUse(arg, localVars, use);
            }
            case RETURN -> {
                ReturnInstruction ret = (ReturnInstruction) inst;
                ret.getOperand().ifPresent(op -> addUse(op, localVars, use));
            }
            case PUTFIELD -> {
                PutFieldInstruction pf = (PutFieldInstruction) inst;
                addUse(pf.getObject(), localVars, use);
                addUse(pf.getValue(), localVars, use);
            }
            case GETFIELD -> {
                GetFieldInstruction gf = (GetFieldInstruction) inst;
                addUse(gf.getObject(), localVars, use);
            }
            case BRANCH -> {
                CondBranchInstruction branch = (CondBranchInstruction) inst;
                addUsesFromInstruction(branch.getCondition(), localVars, use);
            }
            default -> addUsesFromInstruction(inst, localVars, use);
        }
    }

    private void addUsesFromInstruction(Instruction inst, Set<String> localVars, Set<String> use) {
        if (inst == null) return;
        switch (inst.getInstType()) {
            case NOPER -> addUse(((SingleOpInstruction) inst).getSingleOperand(), localVars, use);
            case UNARYOPER -> addUse(((UnaryOpInstruction) inst).getOperand(), localVars, use);
            case BINARYOPER -> {
                BinaryOpInstruction bop = (BinaryOpInstruction) inst;
                addUse(bop.getLeftOperand(), localVars, use);
                addUse(bop.getRightOperand(), localVars, use);
            }
            case CALL -> {
                CallInstruction call = (CallInstruction) inst;
                addUse(call.getCaller(), localVars, use);
                if (call.getArguments() != null)
                    for (Element arg : call.getArguments()) addUse(arg, localVars, use);
            }
            case GETFIELD -> addUse(((GetFieldInstruction) inst).getObject(), localVars, use);
            default -> {}
        }
    }

    private void addUse(Element elem, Set<String> localVars, Set<String> use) {
        if (elem instanceof ArrayOperand arrOp) {
            if (localVars.contains(arrOp.getName())) use.add(arrOp.getName());
            for (Element idx : arrOp.getIndexOperands()) addUse(idx, localVars, use);
        } else if (elem instanceof Operand op) {
            if (localVars.contains(op.getName())) use.add(op.getName());
        }
    }
}