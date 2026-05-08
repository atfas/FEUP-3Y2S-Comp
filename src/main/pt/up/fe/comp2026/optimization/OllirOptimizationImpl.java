package pt.up.fe.comp2026.optimization;

import org.specs.comp.ollir.Method;
import org.specs.comp.ollir.VarScope;
import org.specs.comp.ollir.inst.Instruction;
import pt.up.fe.comp.jmm.ollir.OllirResult;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class OllirOptimizationImpl {
    private final int max_registers;

    public OllirOptimizationImpl(int max_registers){
        this.max_registers = max_registers;
    }

    public OllirResult optimizeRegisterAllocation(OllirResult ollir){
        var classUnit = ollir.getOllirClass();
        classUnit.buildCFGs();

        for (var method : classUnit.getMethods()) {
            new RegisterAllocation().allocate(method, max_registers);
        }

        return ollir;
    }


}
