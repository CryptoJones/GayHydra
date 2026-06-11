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
// Counts Rec 37 C++ hint recall over the current program and prints one
// machine-readable line per idiom form (plus a total):
//
//   RECALL VIRTUAL_CALL=1 CONSTRUCTION=0 ... TOTAL=3
//
// This is the measurement half of the hint-recall corpus
// (samples/hint-recall-corpus/): scripts/hint-recall.sh runs it headlessly
// over the committed corpus binaries and diffs the counts against
// baseline.json, so a recall regression (upstream decompiler idiom drift, a
// driver change, an analyzer feed break) fails CI instead of passing
// silently. Zero counts are data, not errors — they quantify exactly which
// (compiler × arch × opt) cells the grounded-on-x86-64 pipeline reaches.
// @category C++
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.app.util.cpp.CppHintsCollector;
import ghidra.app.util.cpp.CppHintsCollector.CppHint;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.HighFunction;

public class CountCppHintRecallScript extends GhidraScript {

	private static final int DECOMPILE_TIMEOUT_SECONDS = 30;

	@Override
	public void run() throws Exception {
		if (currentProgram == null) {
			printerr("This script requires an open program.");
			return;
		}
		Map<CppHintsCollector.Kind, Integer> counts =
			new EnumMap<>(CppHintsCollector.Kind.class);
		for (CppHintsCollector.Kind kind : CppHintsCollector.Kind.values()) {
			counts.put(kind, 0);
		}
		int total = 0;
		DecompInterface decompiler = new DecompInterface();
		try {
			if (!decompiler.openProgram(currentProgram)) {
				printerr("Decompiler failed to open: " + decompiler.getLastMessage());
				return;
			}
			for (Function function : currentProgram.getFunctionManager().getFunctions(true)) {
				monitor.checkCancelled();
				DecompileResults results =
					decompiler.decompileFunction(function, DECOMPILE_TIMEOUT_SECONDS, monitor);
				HighFunction highFunction = results.getHighFunction();
				if (highFunction == null) {
					continue;
				}
				List<CppHint> hints = CppHintsCollector.collect(highFunction);
				for (CppHint hint : hints) {
					counts.merge(hint.kind(), 1, Integer::sum);
					total++;
					println("RECALL_SITE " + hint.kind() + " " + hint.site() + " " +
						hint.rendering());
				}
			}
		}
		finally {
			decompiler.dispose();
		}
		StringBuilder line = new StringBuilder("RECALL");
		for (Map.Entry<CppHintsCollector.Kind, Integer> entry : counts.entrySet()) {
			line.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
		}
		line.append(" TOTAL=").append(total);
		println(line.toString());
	}
}
