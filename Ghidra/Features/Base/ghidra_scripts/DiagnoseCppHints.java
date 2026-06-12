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
// Headless diagnostic for the Rec 37 C++ hint pipeline: dumps the executable format + compiler
// metadata (the analyzer-gate inputs), the typeinfo/vtable symbols and _ZTI*/_ZTV* glob counts,
// the fed CppTypeSystem's class list, and — for every function whose name starts with "form_" —
// the decompiled C, recovered parameter types, each CAST/PTRSUB/PTRADD/CALL/CALLIND op with its
// output varnode datatype and inputs, and what CppBaseCastRecognizer returns on each CAST.
//
// This is the headless equivalent of stepping through the decompiler output by hand: it answers
// "why does a given form decline" at the symbol / type-system / p-code level without a GUI. It
// found the canAnalyze 'unknown'-compiler gate bug (empty type system despite present _ZTI
// symbols) and the form_upcast bare-PTRSUB recognizer gap. Point it at any binary; the "form_"
// filter is just the corpus convention (samples/hint-recall-corpus/).
// @category C++
import java.util.Iterator;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.app.util.cpp.CppBaseCastRecognizer;
import ghidra.app.util.cpp.CppBaseCastRecognizer.BaseCast;
import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.app.util.cpp.CppTypeSystemProvider;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;

public class DiagnoseCppHints extends GhidraScript {

	@Override
	public void run() throws Exception {
		println("=== ENV ===");
		println("  format=" + currentProgram.getExecutableFormat());
		println("  compiler=[" + currentProgram.getCompiler() + "]");

		println("=== TYPEINFO/VTABLE SYMBOLS (by substring) ===");
		int shown = 0;
		for (ghidra.program.model.symbol.Symbol s : currentProgram.getSymbolTable()
				.getAllSymbols(false)) {
			String n = s.getName();
			if (n.contains("ZTI") || n.contains("ZTV") || n.contains("typeinfo") ||
				n.contains("vtable")) {
				println("  " + n + " @ " + s.getAddress() + " primary=" + s.isPrimary());
				if (++shown > 30) {
					break;
				}
			}
		}
		println("  _ZTI* glob count: " + countGlob("_ZTI*"));
		println("  _ZTV* glob count: " + countGlob("_ZTV*"));

		println("=== FED TYPE SYSTEM (before manual rescan) ===");
		CppTypeSystem ts = CppTypeSystemProvider.get(currentProgram);
		for (CppClass c : ts.getCppClasses().values()) {
			println("  class " + c.getName() + " bases=" + c.getBaseClasses().size() +
				(c.getVtable() != null ? " vtable=" + c.getVtable().getSlots().size() : " vtable=none"));
		}

		DecompInterface decompiler = new DecompInterface();
		decompiler.openProgram(currentProgram);
		try {
			for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
				if (!f.getName().startsWith("form_")) {
					continue;
				}
				println("\n=== " + f.getName() + " @ " + f.getEntryPoint() + " ===");
				DecompileResults r = decompiler.decompileFunction(f, 30, monitor);
				HighFunction hf = r.getHighFunction();
				if (hf == null) {
					println("  (no HighFunction)");
					continue;
				}
				println("  C: " + (r.getDecompiledFunction() != null
						? r.getDecompiledFunction().getC().replace("\n", "\n     ")
						: "(none)"));
				println("  proto: " + hf.getFunctionPrototype().getReturnType() + " (" +
					protoParams(hf) + ")");
				Iterator<PcodeOpAST> ops = hf.getPcodeOps();
				while (ops.hasNext()) {
					PcodeOpAST op = ops.next();
					String mnem = op.getMnemonic();
					Varnode out = op.getOutput();
					String outType = out != null && out.getHigh() != null
							? out.getHigh().getDataType().getName()
							: "-";
					if (op.getOpcode() == PcodeOp.CAST || op.getOpcode() == PcodeOp.PTRSUB ||
						op.getOpcode() == PcodeOp.PTRADD || op.getOpcode() == PcodeOp.CALL ||
						op.getOpcode() == PcodeOp.CALLIND) {
						println("    " + mnem + " -> " + outType + "  " + inputs(op));
						if (op.getOpcode() == PcodeOp.CAST) {
							BaseCast bc = CppBaseCastRecognizer.recognize(op);
							println("      castRecognizer: " + (bc == null ? "DECLINE" :
								"offset=" + bc.byteOffset()));
						}
					}
				}
			}
		}
		finally {
			decompiler.dispose();
		}
	}

	private int countGlob(String glob) {
		int n = 0;
		ghidra.program.model.symbol.SymbolIterator it =
			currentProgram.getSymbolTable().getSymbolIterator(glob, true);
		while (it.hasNext()) {
			it.next();
			n++;
		}
		return n;
	}

	private String protoParams(HighFunction hf) {
		StringBuilder sb = new StringBuilder();
		int n = hf.getFunctionPrototype().getNumParams();
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(hf.getFunctionPrototype().getParam(i).getDataType().getName());
		}
		return sb.toString();
	}

	private String inputs(PcodeOp op) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < op.getNumInputs(); i++) {
			Varnode in = op.getInput(i);
			if (in == null) {
				sb.append("[null] ");
				continue;
			}
			String t = in.getHigh() != null ? in.getHigh().getDataType().getName() : "?";
			if (in.isConstant()) {
				sb.append("const:0x").append(Long.toHexString(in.getOffset())).append(' ');
			}
			else {
				String nm = in.getHigh() != null ? in.getHigh().getName() : null;
				sb.append(nm != null ? nm : "v").append(':').append(t).append(' ');
			}
		}
		return sb.toString().trim();
	}
}
