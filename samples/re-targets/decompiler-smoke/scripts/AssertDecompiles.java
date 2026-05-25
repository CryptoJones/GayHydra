/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Decompiler-smoke post-analysis script.
//
// Contract: take the imported program (a tiny statically-linked C
// binary produced from samples/re-targets/decompiler-smoke/main.c),
// pick the first user-defined function the decompiler can reach
// (preference order: `main`, `add_one`, then any non-external
// function), run the decompiler on it, and assert the result
// completed without error AND emitted a non-empty C body.
//
// This is deliberately a low-specificity smoke test — the goal is
// to catch catastrophic regressions in the analyzer / headless
// launcher / decompiler pipeline (a build that headlessly crashes,
// a decompiler that throws on a trivial function, an analyzer that
// refuses to create function bounds), not to assert that the
// decompiled output contains any specific text. The prior dropper
// gate's hard-coded `0x5A` constant-match was exactly the kind of
// over-specific assertion this script avoids.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;

public class AssertDecompiles extends GhidraScript {

    @Override
    public void run() throws Exception {
        println("=== AssertDecompiles post-script ===");

        FunctionManager fm = currentProgram.getFunctionManager();
        Function target = pickTarget(fm);
        if (target == null) {
            println("RESULT: FAIL — no candidate function found (analyzer never created any function bounds)");
            return;
        }
        println("target function: " + target.getName(true) + " @ " + target.getEntryPoint());

        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        if (!decomp.openProgram(currentProgram)) {
            println("RESULT: FAIL — decompiler openProgram() returned false (decompile binary missing or unreadable?)");
            return;
        }
        DecompileResults res;
        try {
            res = decomp.decompileFunction(target, 120, monitor);
        } finally {
            decomp.dispose();
        }

        if (res == null) {
            println("RESULT: FAIL — decompileFunction returned null");
            return;
        }
        if (!res.decompileCompleted()) {
            println("RESULT: FAIL — decompileCompleted() returned false; error: "
                + res.getErrorMessage());
            return;
        }
        if (res.getDecompiledFunction() == null) {
            println("RESULT: FAIL — getDecompiledFunction() returned null");
            return;
        }
        String c = res.getDecompiledFunction().getC();
        if (c == null || c.isEmpty()) {
            println("RESULT: FAIL — decompiled C body is empty");
            return;
        }
        println("decompiled body: " + c.length() + " chars, "
            + c.split("\n", -1).length + " line(s)");
        println("RESULT: PASS");
    }

    private Function pickTarget(FunctionManager fm) {
        // Preference order: main, add_one, first non-external user function.
        Function byName = firstByName(fm, "main");
        if (byName != null) return byName;
        byName = firstByName(fm, "add_one");
        if (byName != null) return byName;
        FunctionIterator it = fm.getFunctions(true);
        while (it.hasNext()) {
            Function f = it.next();
            if (!f.isExternal() && !f.isThunk()) {
                return f;
            }
        }
        return null;
    }

    private Function firstByName(FunctionManager fm, String name) {
        FunctionIterator it = fm.getFunctions(true);
        while (it.hasNext()) {
            Function f = it.next();
            if (name.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }
}
