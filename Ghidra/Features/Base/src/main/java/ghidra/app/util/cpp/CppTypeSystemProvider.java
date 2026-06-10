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
package ghidra.app.util.cpp;

import ghidra.app.plugin.core.analysis.TransientProgramProperties;
import ghidra.app.plugin.core.analysis.TransientProgramProperties.SCOPE;
import ghidra.program.model.listing.Program;

/**
 * Where a program's {@link CppTypeSystem} lives (Rec 37 integration, {@code #37-11a}): a per-program
 * get-or-create provider over {@link TransientProgramProperties}, so every Rec 37 contributor and
 * consumer shares <em>one</em> type system per open program.
 *
 * <p><b>The provider answers location only — it does not feed.</b> Contributors (the demangling,
 * RTTI, and vtable feeders, and module-specific scans such as the MSVC RTTI harvest) call
 * {@link #get} and feed classes into the shared instance; consumers (the recognition drivers behind
 * {@link CppDecompilerHints}) call {@link #get} and read. This split keeps the module dependency
 * direction clean: the provider lives in Base, and a contributor in a downstream module (e.g.
 * MicrosoftCodeAnalyzer's MSVC RTTI scan) reaches it without Base knowing the contributor exists.
 * Feeding is idempotent by the model's own contracts ({@link CppTypeSystem#defineClass} returns the
 * existing projection; base resolution fills placeholders), so contributors may run repeatedly and in
 * any order.
 *
 * <p><b>Lifetime and rollback.</b> The instance is {@link SCOPE#PROGRAM}-scoped: created on first
 * request, the same instance on every later request, released when the program is closed. PROGRAM
 * scope (not ANALYSIS_SESSION) is deliberate — the decompiler hints are consumed interactively long
 * after auto-analysis ends, which is exactly when a session-scoped value would have been discarded.
 * The rollback caveat on PROGRAM-scoped properties does not bite here: the model holds plain
 * projection data — placeholder classes are standalone {@code StructureDataType}s, not program-DB
 * data types — so a transaction rollback cannot dangle it. The bound {@link
 * ghidra.program.model.data.DataTypeManager} is the program's, held as a lookup handle, not as
 * stored data-type state.
 */
public final class CppTypeSystemProvider {

	private CppTypeSystemProvider() {
		// static provider utility
	}

	/**
	 * {@return the one {@link CppTypeSystem} for the given program, creating it bound to the
	 * program's {@link ghidra.program.model.data.DataTypeManager} on first request}
	 *
	 * @param program the program; must not be null
	 */
	public static CppTypeSystem get(Program program) {
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		return TransientProgramProperties.getProperty(program, CppTypeSystem.class, SCOPE.PROGRAM,
			CppTypeSystem.class, () -> new CppTypeSystem(program.getDataTypeManager()));
	}
}
