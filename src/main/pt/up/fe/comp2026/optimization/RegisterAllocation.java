package pt.up.fe.comp2026.optimization;

import org.specs.comp.ollir.*;
import org.specs.comp.ollir.inst.*;
import pt.up.fe.specs.util.collections.HashSetString;

import java.util.*;
import java.util.stream.Collectors;

public class RegisterAllocation {
    public void allocate(Method method, int max_reg) {
        var vt = method.getVarTable();

        // 1. Variáveis locais elegíveis
        Set<String> locals = vt.entrySet().stream()
                .filter(e -> e.getValue().getScope() == VarScope.LOCAL)
                .map(Map.Entry::getKey)
                .filter(v -> !v.equals("this"))   // ← OBRIGATÓRIO
                .collect(Collectors.toSet());

        // 2. Inicialização USE / DEF por instrução
        List<Instruction> instructions = method.getInstructions();

        Map<Instruction, Set<String>> use = new HashMap<>();
        Map<Instruction, Set<String>> def = new HashMap<>();

        for (Instruction i : instructions) {
            Set<String> u = getUse(i);
            Set<String> d = getDef(i);

            // Filtrar apenas variáveis locais
            u.retainAll(locals);
            d.retainAll(locals);

            use.put(i, u);
            def.put(i, d);
        }

        // 3. Inicializar IN / OUT
        Map<Instruction, Set<String>> in = new HashMap<>();
        Map<Instruction, Set<String>> out = new HashMap<>();

        for (Instruction i : instructions) {
            in.put(i, new HashSet<>());
            out.put(i, new HashSet<>());
        }

        // 4. Liveness Analysis (fixpoint)
        boolean changed;
        do {
            changed = false;

            // Percorrer em ordem inversa
            for (int idx = instructions.size() - 1; idx >= 0; idx--) {
                Instruction i = instructions.get(idx);

                Set<String> oldIn = new HashSet<>(in.get(i));
                Set<String> oldOut = new HashSet<>(out.get(i));

                // OUT[n] = união dos IN[s] de todos os sucessores
                Set<String> newOut = new HashSet<>();
                for (Instruction succ : i.getSuccessorsAsInst()) {
                    newOut.addAll(in.get(succ));
                }

                // IN[n] = USE[n] ∪ (OUT[n] − DEF[n])
                Set<String> newIn = new HashSet<>(use.get(i));
                Set<String> outMinusDef = new HashSet<>(newOut);
                outMinusDef.removeAll(def.get(i));
                newIn.addAll(outMinusDef);

                out.put(i, newOut);
                in.put(i, newIn);

                if (!newIn.equals(oldIn) || !newOut.equals(oldOut)) {
                    changed = true;
                }
            }

        } while (changed);

        if (max_reg == 0) {
            int maxLive = 0;
            for (Set<String> live : in.values()) {
                maxLive = Math.max(maxLive, live.size());
            }
            max_reg = maxLive;
        }

        // 5. Construção do grafo de interferência
        Map<String, Set<String>> interferenceGraph = new HashMap<>();
        for (String v : locals) {
            interferenceGraph.put(v, new HashSet<>());
        }

        for (Instruction i : instructions) {
            for (String d : def.get(i)) {
                for (String v : out.get(i)) {
                    if (!d.equals(v)) {
                        interferenceGraph.get(d).add(v);
                        interferenceGraph.get(v).add(d);
                    }
                }
            }
        }

        // 6. Coloring greedy
        Map<String, Integer> color = new HashMap<>();

        for (String v : interferenceGraph.keySet()) {
            int offset = method.isStaticMethod() ? 0 : 1;

            boolean[] used = new boolean[max_reg];

            for (String neighbor : interferenceGraph.get(v)) {
                if (color.containsKey(neighbor)) {
                    int c = color.get(neighbor);
                    if (c - offset >= 0 && c - offset < max_reg) {
                        used[c - offset] = true;
                    }
                }
            }

            int reg;
            for (reg = 0; reg < max_reg; reg++) {
                if (!used[reg]) break;
            }

            color.put(v, reg + offset);
        }

        // 7. Escrever resultado no VarTable
        for (Map.Entry<String, Integer> entry : color.entrySet()) {
            String var = entry.getKey();
            int reg = entry.getValue();
            vt.get(var).setVirtualReg(reg);
        }
    }

    private void collectVarsFromElement(Element e, Set<String> vars) {
        if (e == null) return;

        if (e.isLiteral()) return;

        if (e instanceof Operand op) {
            vars.add(op.getName());
        }
    }

    private void collectVarsFromInstruction(Instruction instr, Set<String> vars) {

        if (instr instanceof BinaryOpInstruction bin) {
            collectVarsFromElement(bin.getLeftOperand(), vars);
            collectVarsFromElement(bin.getRightOperand(), vars);

        } else if (instr instanceof UnaryOpInstruction un) {
            collectVarsFromElement(un.getOperand(), vars);

        } else if (instr instanceof CallInstruction call) {
            collectVarsFromElement(call.getCaller(), vars);
            for (Element arg : call.getArguments()) {
                collectVarsFromElement(arg, vars);
            }

        } else if (instr instanceof GetFieldInstruction gf) {
            collectVarsFromElement(gf.getObject(), vars);

        } else if (instr instanceof SingleOpInstruction so) {
            collectVarsFromElement(so.getSingleOperand(), vars);
        }
    }


    private Set<String> getUse(Instruction i) {
        Set<String> use = new HashSet<>();

        switch (i.getInstType()) {

            case ASSIGN -> {
                AssignInstruction assign = (AssignInstruction) i;
                collectVarsFromInstruction(assign.getRhs(), use);
            }

            case CALL -> {
                CallInstruction call = (CallInstruction) i;

                // objeto (em chamadas virtuais)
                collectVarsFromElement(call.getCaller(), use);

                // argumentos
                for (Element arg : call.getArguments()) {
                    collectVarsFromElement(arg, use);
                }
            }

            case RETURN -> {
                ReturnInstruction ret = (ReturnInstruction) i;
                ret.getOperand().ifPresent(op -> collectVarsFromElement(op, use));
            }

            case BRANCH -> {
                CondBranchInstruction br = (CondBranchInstruction) i;
                for (Element e : br.getOperands()) {
                    collectVarsFromElement(e, use);
                }
            }

            case PUTFIELD -> {
                PutFieldInstruction pf = (PutFieldInstruction) i;
                collectVarsFromElement(pf.getObject(), use);
                collectVarsFromElement(pf.getValue(), use);
            }

            case GETFIELD -> {
                GetFieldInstruction gf = (GetFieldInstruction) i;
                collectVarsFromElement(gf.getObject(), use);
            }

            default -> {
                // GOTO, NOP, etc.
            }
        }

        return use;
    }

    private Set<String> getDef(Instruction i) {
        Set<String> def = new HashSet<>();

        if (i instanceof AssignInstruction assign) {
            Element dest = assign.getDest();
            if (dest instanceof Operand op) {
                def.add(op.getName());
            }
        }

        return def;
    }
}
