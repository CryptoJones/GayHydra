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
//Decompiles one function and reports whether kuna's core produced it. kuna_ghidra
//stamps its output with a "/* Kuna v<version> */" comment; the stock decompiler
//does not. Used by scripts/kuna-backend.sh verify (see docs/decompiler/KUNA_BACKEND.md).
//
//Optional script argument: the function name to decompile (a leading underscore,
//as Mach-O symbols carry, is ignored). Default: the first non-thunk function.
//
//Prints exactly one result line: KUNA_MARKER_FOUND <function> <marker>,
//KUNA_MARKER_ABSENT <function>, or KUNA_CHECK_ERROR <reason>.
//@category GayHydra
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;

public class KunaMarkerCheck extends GhidraScript {

	private static final Pattern MARKER = Pattern.compile("/\\* Kuna v[^*]*\\*/");

	@Override
	public void run() throws Exception {
		println("decompiler executable: " + DecompileProcessFactory.getExecutablePath());
		String[] args = getScriptArgs();
		String want = args.length > 0 ? strip(args[0]) : null;

		Function target = null;
		for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
			if (f.isThunk() || f.isExternal()) {
				continue;
			}
			if (want == null || want.equals(strip(f.getName()))) {
				target = f;
				break;
			}
		}
		if (target == null) {
			println("KUNA_CHECK_ERROR no function" + (want != null ? " named " + want : ""));
			return;
		}

		DecompInterface ifc = new DecompInterface();
		try {
			if (!ifc.openProgram(currentProgram)) {
				println("KUNA_CHECK_ERROR openProgram: " + ifc.getLastMessage());
				return;
			}
			DecompileResults res = ifc.decompileFunction(target, 120, monitor);
			if (!res.decompileCompleted() || res.getDecompiledFunction() == null) {
				println("KUNA_CHECK_ERROR decompile: " + res.getErrorMessage());
				return;
			}
			String c = res.getDecompiledFunction().getC();
			println(c);
			Matcher m = MARKER.matcher(c);
			if (m.find()) {
				println("KUNA_MARKER_FOUND " + target.getName() + " " + m.group());
			}
			else {
				println("KUNA_MARKER_ABSENT " + target.getName());
			}
		}
		finally {
			ifc.dispose();
		}
	}

	private static String strip(String name) {
		return name.startsWith("_") ? name.substring(1) : name;
	}
}
