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

import ghidra.app.cmd.data.rtti.CppMsvcRttiScan;
import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.cpp.CppRttiFeeder;
import ghidra.app.util.cpp.CppTypeSystemProvider;
import ghidra.app.util.datatype.microsoft.DataValidationOptions;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The {@code Analyzer}-lifecycle wrapper of the Rec 37 {@code #37-5} MSVC RTTI band
 * ({@code #37-11b}): runs the {@link CppMsvcRttiScan} harvest over the program during
 * auto-analysis, feeding the per-program shared {@link ghidra.app.util.cpp.CppTypeSystem}
 * obtained from {@link CppTypeSystemProvider}.
 *
 * <p><b>Runs after upstream's {@link RttiAnalyzer}.</b> The harvest reads the defined
 * {@code RTTICompleteObjectLocator} data that analyzer lays down (DD-0061's harvest-not-re-discovery
 * posture), so this analyzer's priority is {@code REFERENCE_ANALYSIS.after()} &mdash; strictly after
 * the upstream analyzer's {@code REFERENCE_ANALYSIS.before()}. Same {@code canAnalyze} gate
 * ({@link PEUtil#isVisualStudioOrClangPe}): where the upstream analyzer cannot run, there is nothing
 * to harvest.
 *
 * <p><b>Idempotent by construction.</b> A byte analyzer can be triggered repeatedly as analysis
 * progresses; each trigger re-walks the whole program's defined data (the added set is ignored
 * &mdash; RTTI4s laid down outside it must still be fed) and re-feeds the shared type system, which
 * the feeder's own contracts make a no-op for already-fed classes. Advisory, never wrong: an entry
 * that no longer validates contributes nothing, and a program with no laid-down RTTI yields an
 * untouched type system.
 */
public class CppRttiAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "C++ Type System (MSVC RTTI)";
	private static final String DESCRIPTION =
		"Feeds the program's shared C++ type system from the RTTI metadata structures the " +
			"Windows x86 PE RTTI Analyzer has laid down, so C++-aware decompiler hints can " +
			"resolve class hierarchies. Advisory: structures that fail re-validation are skipped.";

	private DataValidationOptions validationOptions;

	/**
	 * Constructs a CppRttiAnalyzer.
	 */
	public CppRttiAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setSupportsOneTimeAnalysis();
		// Run strictly after upstream's RttiAnalyzer (REFERENCE_ANALYSIS.before()) — the harvest
		// reads the RTTICompleteObjectLocator data that analyzer creates.
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
		CppRttiFeeder feeder = new CppRttiFeeder(CppTypeSystemProvider.get(program));
		CppMsvcRttiScan.feedProgram(program, feeder, validationOptions, monitor);
		return true;
	}
}
