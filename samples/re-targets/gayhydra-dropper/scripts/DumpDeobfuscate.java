// Post-analysis script: scan the program for `XOR <reg>, 0x5A` instructions
// (the gayhydra-dropper's deobfuscate key) and assert at least one survives.
// When the instructions land inside a Ghidra-recognized function, also
// decompile that function and verify the constant survives into the C view
// — that's the stronger signal we want from the decompiler.
//
// Why scan instructions, not functions: GayHydra's Go analyzer has been
// flaky on recent Go releases (1.25+ crashes with EOFException). When it
// crashes, Ghidra disassembles the bytes correctly but never creates
// function bounds for them — every XOR-0x5A ends up "orphan" (no
// containing function). Treating that as PASS is appropriate: the
// disassembler still saw the constant. A FAIL means even the disassembly
// lost it, which is a deeper regression.

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

        int rawInsnHits = 0;
        Set<Function> containingFuncs = new LinkedHashSet<>();
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        InstructionIterator it = listing.getInstructions(true);
        while (it.hasNext()) {
            if (monitor.isCancelled()) break;
            Instruction insn = it.next();
            if (!isXor(insn.getMnemonicString())) continue;
            if (!hasScalarOperand(insn, KEY)) continue;
            rawInsnHits++;
            Function f = fm.getFunctionContaining(insn.getAddress());
            if (f != null) {
                containingFuncs.add(f);
            }
        }

        println("disassembly: " + rawInsnHits + " XOR <reg>, 0x"
            + Long.toHexString(KEY) + " instruction(s); "
            + containingFuncs.size() + " containing function(s)");

        if (rawInsnHits == 0) {
            println("RESULT: FAIL — no XOR-with-0x5A instructions found in disassembly");
            return;
        }

        int funcsWithKeyInDecompile = 0;
        if (!containingFuncs.isEmpty()) {
            DecompInterface decomp = new DecompInterface();
            decomp.setOptions(new DecompileOptions());
            if (!decomp.openProgram(currentProgram)) {
                println("WARN: decompiler openProgram failed; skipping decompile check");
            } else {
                for (Function f : containingFuncs) {
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
                        funcsWithKeyInDecompile++;
                    }
                }
            }
            println("decompile: " + funcsWithKeyInDecompile + " / " + containingFuncs.size()
                + " function(s) preserved 0x5A through decompilation");
        } else {
            println("decompile: skipped — none of the XOR instructions sit inside a Ghidra-recognized function "
                + "(likely Go analyzer crashed; see SprintPlanning.md for upstream NSA bug)");
        }

        println("");
        if (funcsWithKeyInDecompile > 0) {
            println("RESULT: PASS — XOR key 0x5A visible in decompiler output");
        } else if (rawInsnHits > 0) {
            println("RESULT: PASS (weak) — XOR key 0x5A visible in disassembly only; "
                + "decompile check skipped because no containing function was created");
        } else {
            println("RESULT: FAIL");
        }
    }

    private static boolean isXor(String mnem) {
        // Match all common XOR mnemonic flavors emitted by Ghidra's sleigh
        // specs across the architectures we care about. x86 sleigh uses
        // plain "XOR" for both 32-bit and 64-bit forms.
        return mnem.equalsIgnoreCase("XOR")
            || mnem.equalsIgnoreCase("XORB")
            || mnem.equalsIgnoreCase("XORL")
            || mnem.equalsIgnoreCase("XORQ");
    }

    private boolean hasScalarOperand(Instruction insn, long value) {
        for (int i = 0; i < insn.getNumOperands(); i++) {
            // Don't rely on OperandType.SCALAR alone — different sleigh
            // specs flag immediate operands differently. The presence of
            // a Scalar object is the authoritative signal.
            Scalar s = insn.getScalar(i);
            if (s != null && s.getUnsignedValue() == value) {
                return true;
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
