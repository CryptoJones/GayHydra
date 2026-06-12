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
package ghidra.app.plugin.core.analysis;

import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.cpp.CppItaniumRttiScan;
import ghidra.app.util.cpp.CppRttiFeeder;
import ghidra.app.util.cpp.CppTypeSystemProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.ElfLoader;
import ghidra.app.util.opinion.MachoLoader;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The {@code Analyzer}-lifecycle wrapper of the Rec 37 Itanium RTTI band ({@code #37-4b-3}) —
 * the ELF/Mach-O twin of the MSVC {@code CppRttiAnalyzer} (DD-0063). Runs the
 * {@link CppItaniumRttiScan} {@code _ZTI*} harvest over the program during auto-analysis,
 * feeding the per-program shared {@link ghidra.app.util.cpp.CppTypeSystem} from
 * {@link CppTypeSystemProvider}.
 *
 * <p><b>Gated on Itanium-ABI binaries.</b> {@code canAnalyze} requires an ELF or Mach-O
 * executable format with a GCC/Clang ({@code gcc} or {@code default}) compiler spec — the
 * targets whose typeinfo follows the Itanium C++ ABI the {@code #37-4b-1} decoder reads. A
 * Windows PE is the MSVC analyzer's job; a stripped binary publishes no {@code _ZTI} symbols
 * and so yields an untouched type system.
 *
 * <p><b>No upstream-analyzer dependency.</b> Unlike the MSVC harvest (which reads RTTI4 data
 * upstream's {@code RttiAnalyzer} lays down), the Itanium harvest anchors on linker-published
 * {@code _ZTI*} symbols that exist from load — so the priority need only be late enough that
 * symbols and references are settled ({@code REFERENCE_ANALYSIS.after()}, matching the MSVC
 * twin for consistency).
 *
 * <p><b>Idempotent, advisory, never wrong.</b> Each trigger re-walks every {@code _ZTI*}
 * symbol and re-feeds; the feeder makes re-feeding a no-op for already-fed classes. A symbol
 * whose typeinfo declines (template encoding, virtual base) contributes nothing.
 */
public class CppItaniumRttiAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "C++ Type System (Itanium RTTI)";
	private static final String DESCRIPTION =
		"Feeds the program's shared C++ type system from the Itanium-ABI typeinfo (_ZTI*) " +
			"objects a GCC/Clang ELF or Mach-O binary publishes, so C++-aware decompiler " +
			"hints can resolve class hierarchies. Advisory: typeinfo that fails to decode " +
			"(templates, virtual bases) is skipped.";

	/**
	 * Constructs a CppItaniumRttiAnalyzer.
	 */
	public CppItaniumRttiAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setSupportsOneTimeAnalysis();
		setPriority(AnalysisPriority.REFERENCE_ANALYSIS.after());
		setDefaultEnablement(true);
	}

	@Override
	public boolean canAnalyze(Program program) {
		// Format-only gate: ELF or Mach-O. The compiler *metadata* string is
		// NOT checked — a relocatable .o (and many linked binaries) reports
		// "unknown", which an earlier compiler-string gate wrongly rejected,
		// so the analyzer never ran (the bug the hint-recall probe caught).
		// The real discriminator is the presence of `_ZTI*` typeinfo symbols,
		// which are Itanium-ABI by definition; the scan reads exactly those
		// and is never-wrong on a non-C++ ELF (no symbols -> nothing fed), so
		// running on every ELF/Mach-O is correct and costs only a symbol walk.
		// (A Windows PE -- the MSVC analyzer's job -- still declines here.)
		String format = program.getExecutableFormat();
		return ElfLoader.ELF_NAME.equals(format) || MachoLoader.MACH_O_NAME.equals(format);
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		CppRttiFeeder feeder = new CppRttiFeeder(CppTypeSystemProvider.get(program));
		CppItaniumRttiScan.feedProgram(program, feeder, monitor);
		return true;
	}
}
