// Post-analysis script: scan the program for `XOR <reg>, 0x5A` instructions
// (the gayhydra-dropper's deobfuscate key), then decompile each containing
// function and assert the literal `0x5a` survives into the decompiler view.
//
// Why scan instead of looking up by name: GayHydra's Go analyzer has been
// flaky on recent Go releases (1.26 crashes with EOFException) and leaves
// every function as FUN_<addr>. Why scan instead of using a hard-coded
// address: Go inlines main.deobfuscate, and the resulting addresses shift
// with every Go compiler release. Scanning the disassembly for the key
// constant is robust to both.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.lang.OperandType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;

import java.util.LinkedHashSet;
import java.util.Set;

public class DumpDeobfuscate extends GhidraScript {

    private static final long KEY = 0x5AL;

    @Override
    public void run() throws Exception {
        println("=== DumpDeobfuscate post-script ===");

        Set<Function> xorFuncs = findFunctionsWithXorKey();
        println("found " + xorFuncs.size() + " function(s) with XOR <reg>, 0x"
            + Long.toHexString(KEY) + " instructions");
        if (xorFuncs.isEmpty()) {
            println("RESULT: FAIL — no XOR-with-0x5A instructions found in disassembly");
            return;
        }

        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        if (!decomp.openProgram(currentProgram)) {
            println("RESULT: FAIL — decompiler openProgram failed");
            return;
        }

        int functionsWithKeyInDecompile = 0;
        for (Function f : xorFuncs) {
            DecompileResults res = decomp.decompileFunction(f, 120, monitor);
            if (res == null || res.getDecompiledFunction() == null) {
                println("  (skipped: " + f.getName(true) + " @ " + f.getEntryPoint() + " — decompile failed)");
                continue;
            }
            String c = res.getDecompiledFunction().getC();
            int hits = countOccurrences(c, "0x5a") + countOccurrences(c, "0x5A");
            println("  " + f.getName(true) + " @ " + f.getEntryPoint()
                + " — " + hits + " line(s) of decompiled C contain 0x5a");
            if (hits > 0) {
                functionsWithKeyInDecompile++;
            }
        }

        println("");
        println("SUMMARY: " + functionsWithKeyInDecompile + " / " + xorFuncs.size()
            + " function(s) preserved the 0x5A constant through decompilation");
        if (functionsWithKeyInDecompile > 0) {
            println("RESULT: PASS — XOR key 0x5A visible in decompiler output");
        } else {
            println("RESULT: FAIL — disassembly sees the XOR key but decompiler hides it");
        }
    }

    private Set<Function> findFunctionsWithXorKey() {
        Set<Function> hits = new LinkedHashSet<>();
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        InstructionIterator it = listing.getInstructions(true);
        while (it.hasNext()) {
            if (monitor.isCancelled()) break;
            Instruction insn = it.next();
            String mnemonic = insn.getMnemonicString();
            if (!mnemonic.equalsIgnoreCase("XOR") && !mnemonic.equalsIgnoreCase("XORB")
                && !mnemonic.equalsIgnoreCase("XORL")) {
                continue;
            }
            if (!hasScalarOperand(insn, KEY)) {
                continue;
            }
            Function f = fm.getFunctionContaining(insn.getAddress());
            if (f != null) {
                hits.add(f);
            }
        }
        return hits;
    }

    private boolean hasScalarOperand(Instruction insn, long value) {
        for (int i = 0; i < insn.getNumOperands(); i++) {
            int t = insn.getOperandType(i);
            if ((t & OperandType.SCALAR) != 0) {
                Scalar s = insn.getScalar(i);
                if (s != null && s.getUnsignedValue() == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countOccurrences(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
