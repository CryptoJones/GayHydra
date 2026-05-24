// Post-analysis script: decompile main.main at address 0x4a4480 (Go inlined
// main.deobfuscate into main.main, so all four XOR-decode loops appear there).
// Verifies the literal key 0x5A appears in the decompiler output.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;

public class DumpDeobfuscate extends GhidraScript {

    @Override
    public void run() throws Exception {
        println("=== DumpDeobfuscate post-script ===");

        Address target = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(0x4a4480L);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(target);
        if (f == null) {
            f = fm.getFunctionContaining(target);
        }
        if (f == null) {
            println("FAIL: no function recognized at " + target);
            return;
        }
        println("decompiling " + f.getName(true) + " @ " + f.getEntryPoint());

        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        if (!decomp.openProgram(currentProgram)) {
            println("FAIL: decompiler openProgram failed");
            return;
        }
        DecompileResults res = decomp.decompileFunction(f, 120, monitor);
        if (res == null || res.getDecompiledFunction() == null) {
            println("FAIL: decompile returned null");
            return;
        }
        String c = res.getDecompiledFunction().getC();
        java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/main_main_decompiled.c"), c);
        println("decompiled C body written to /tmp/main_main_decompiled.c (" + c.length() + " bytes)");

        int xorCount = 0;
        int lines = 0;
        for (String line : c.split("\n")) {
            lines++;
            if (line.contains("0x5a") || line.contains("0x5A") || line.contains("^ 90")) {
                xorCount++;
            }
        }
        println("SUMMARY: " + lines + " lines decompiled; "
            + xorCount + " line(s) reference XOR key 0x5A");
        if (xorCount > 0) {
            println("RESULT: PASS — XOR key 0x5A visible in decompiler output");
        } else {
            println("RESULT: FAIL — XOR key 0x5A NOT visible in decompiler output");
        }
    }
}
