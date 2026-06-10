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
// Recovers C++ idiom hints (virtual calls, new/delete, constructors, destructors, casts) for every
// function and writes each as a "C++: ..." PRE comment at its site. The hints come from the Rec 37
// pipeline: the C++ Type System analyzers (default-on for Visual Studio / Clang PEs) feed a shared
// per-program type system during auto-analysis, this script decompiles each function and collects
// what the recognition drivers can faithfully render. Re-running never duplicates a comment and
// never clobbers an existing one (hint lines are appended). Functions with no recognized idioms
// contribute nothing — an annotated count of zero on a C binary is normal, not a failure.
// @category C++
import java.util.List;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.app.util.cpp.CppHintsCollector;
import ghidra.app.util.cpp.CppHintsCollector.CppHint;
import ghidra.app.util.cpp.CppHintsCommenter;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.HighFunction;

public class RecoverCppHintsScript extends GhidraScript {

	private static final int DECOMPILE_TIMEOUT_SECONDS = 30;

	@Override
	public void run() throws Exception {
		if (currentProgram == null) {
			printerr("This script requires an open program.");
			return;
		}
		DecompInterface decompiler = new DecompInterface();
		try {
			if (!decompiler.openProgram(currentProgram)) {
				printerr("Decompiler failed to open: " + decompiler.getLastMessage());
				return;
			}
			int decompiled = 0;
			int collected = 0;
			int written = 0;
			for (Function function : currentProgram.getFunctionManager().getFunctions(true)) {
				monitor.checkCancelled();
				monitor.setMessage("Collecting C++ hints: " + function.getName());
				DecompileResults results =
					decompiler.decompileFunction(function, DECOMPILE_TIMEOUT_SECONDS, monitor);
				HighFunction highFunction = results.getHighFunction();
				if (highFunction == null) {
					continue;
				}
				decompiled++;
				List<CppHint> hints = CppHintsCollector.collect(highFunction);
				collected += hints.size();
				written += CppHintsCommenter.annotate(currentProgram, hints);
			}
			println("RecoverCppHints: " + decompiled + " function(s) decompiled, " + collected +
				" hint(s) collected, " + written + " comment line(s) written.");
		}
		finally {
			decompiler.dispose();
		}
	}
}
