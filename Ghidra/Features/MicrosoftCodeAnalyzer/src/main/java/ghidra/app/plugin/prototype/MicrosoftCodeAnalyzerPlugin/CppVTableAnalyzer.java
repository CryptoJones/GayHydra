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
package ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin;

import ghidra.app.cmd.data.rtti.CppMsvcVftableScan;
import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.cpp.CppTypeSystemProvider;
import ghidra.app.util.cpp.CppVTableFeeder;
import ghidra.app.util.datatype.microsoft.DataValidationOptions;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The {@code Analyzer}-lifecycle wrapper of the Rec 37 vtable half ({@code #37-11c-3}): runs the
 * {@link CppMsvcVftableScan} harvest over the program during auto-analysis, feeding the per-program
 * shared {@link ghidra.app.util.cpp.CppTypeSystem} obtained from {@link CppTypeSystemProvider} —
 * the {@link CppRttiAnalyzer} twin.
 *
 * <p><b>Runs after upstream's {@link RttiAnalyzer}.</b> The harvest reads the {@code vftable}
 * symbols that analyzer's associated-vftable pass publishes (DD-0065), so this analyzer's priority
 * is {@code REFERENCE_ANALYSIS.after()} &mdash; strictly after the upstream analyzer's
 * {@code REFERENCE_ANALYSIS.before()}. Its order relative to the sibling {@link CppRttiAnalyzer} at
 * the same priority is deliberately irrelevant: both feed the same shared type system through
 * placeholder-resolving feeders, so whichever runs first creates the {@code CppClass}es the other
 * fills in. Same {@code canAnalyze} gate ({@link PEUtil#isVisualStudioOrClangPe}): where the
 * upstream analyzer cannot run, there is nothing to harvest.
 *
 * <p><b>Idempotent by construction.</b> A byte analyzer can be triggered repeatedly as analysis
 * progresses; each trigger re-walks the whole program's {@code vftable} symbols (the added set is
 * ignored &mdash; tables published outside it must still be fed) and re-feeds the shared type
 * system, which replaces each class's vtable wholesale. Advisory, never wrong: a table that no
 * longer validates or whose slots cannot be faithfully named contributes nothing (the driver's
 * per-table decline), and a program with no published vftables yields an untouched type system.
 */
public class CppVTableAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "C++ Type System (MSVC vftables)";
	private static final String DESCRIPTION =
		"Feeds the program's shared C++ type system from the vftables the Windows x86 PE RTTI " +
			"Analyzer has laid down, naming each slot from its function's symbol, so C++-aware " +
			"decompiler hints can resolve virtual calls. Advisory: tables that fail re-validation " +
			"or whose slots cannot be faithfully named are skipped.";

	private DataValidationOptions validationOptions;

	/**
	 * Constructs a CppVTableAnalyzer.
	 */
	public CppVTableAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setSupportsOneTimeAnalysis();
		// Run strictly after upstream's RttiAnalyzer (REFERENCE_ANALYSIS.before()) — the harvest
		// reads the vftable symbols its associated-vftable pass publishes.
		setPriority(AnalysisPriority.REFERENCE_ANALYSIS.after());
		setDefaultEnablement(true);
		validationOptions = new DataValidationOptions();
	}

	@Override
	public boolean canAnalyze(Program program) {
		return PEUtil.isVisualStudioOrClangPe(program);
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		CppVTableFeeder feeder = new CppVTableFeeder(CppTypeSystemProvider.get(program));
		CppMsvcVftableScan.feedProgram(program, feeder, validationOptions, monitor);
		return true;
	}
}
