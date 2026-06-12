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
import ghidra.app.util.cpp.CppItaniumVtableScan;
import ghidra.app.util.cpp.CppTypeSystemProvider;
import ghidra.app.util.cpp.CppVTableFeeder;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.ElfLoader;
import ghidra.app.util.opinion.MachoLoader;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The {@code Analyzer}-lifecycle wrapper of the Rec 37 Itanium vtable band ({@code #37-4b-4}) —
 * the ELF/Mach-O twin of the MSVC {@code CppVTableAnalyzer} (DD-0066), and the
 * {@link CppItaniumRttiAnalyzer} sibling. Runs the {@link CppItaniumVtableScan} {@code _ZTV*}
 * harvest during auto-analysis, feeding the per-program shared
 * {@link ghidra.app.util.cpp.CppTypeSystem} from {@link CppTypeSystemProvider} with named vtable
 * slots — the half {@link CppVirtualCallDriver} needs to name a recovered slot index.
 *
 * <p><b>Gate and priority match the RTTI analyzer.</b> Format-only ELF/Mach-O (the
 * {@code _ZTV*}-symbol presence is the real filter; the scan is never-wrong on a non-C++ ELF),
 * default-enabled, {@code REFERENCE_ANALYSIS.after()}. Runs late enough that the GNU Demangler
 * has named the slot functions (the slots feed by those names) and the RTTI analyzer has fed the
 * class hierarchy the vtables attach to (sibling order is irrelevant — the feeder resolves
 * placeholders; a vtable fed before its class creates the placeholder the RTTI feed fills).
 *
 * <p><b>Idempotent, advisory, never wrong.</b> Each trigger re-walks every {@code _ZTV*} symbol
 * and re-feeds (the feeder replaces a class's vtable, not appends). A vtable that declines
 * (abstract class, unnamed slot) contributes nothing.
 */
public class CppItaniumVtableAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "C++ Type System (Itanium Vtables)";
	private static final String DESCRIPTION =
		"Feeds the program's shared C++ type system with named virtual-method tables from the " +
			"Itanium-ABI vtable (_ZTV*) objects a GCC/Clang ELF or Mach-O binary publishes, so " +
			"C++-aware decompiler hints can name virtual calls. Advisory: vtables that fail to " +
			"decode (abstract classes, unnamed slots) are skipped.";

	/**
	 * Constructs a CppItaniumVtableAnalyzer.
	 */
	public CppItaniumVtableAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setSupportsOneTimeAnalysis();
		setPriority(AnalysisPriority.REFERENCE_ANALYSIS.after());
		setDefaultEnablement(true);
	}

	@Override
	public boolean canAnalyze(Program program) {
		// Format-only gate (see CppItaniumRttiAnalyzer): the compiler metadata is unreliable for
		// relocatable objects ("unknown"); the _ZTV* symbol presence is the real discriminator.
		String format = program.getExecutableFormat();
		return ElfLoader.ELF_NAME.equals(format) || MachoLoader.MACH_O_NAME.equals(format);
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		CppVTableFeeder feeder = new CppVTableFeeder(CppTypeSystemProvider.get(program));
		CppItaniumVtableScan.feedProgram(program, feeder, monitor);
		return true;
	}
}
