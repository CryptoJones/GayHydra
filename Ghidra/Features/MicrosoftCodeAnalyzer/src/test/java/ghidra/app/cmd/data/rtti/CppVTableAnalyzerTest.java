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
package ghidra.app.cmd.data.rtti;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin.CppRttiAnalyzer;
import ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin.CppVTableAnalyzer;
import ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin.RttiAnalyzer;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.app.util.cpp.CppTypeSystemProvider;
import ghidra.app.util.cpp.CppVTable;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.BinaryLoader;
import ghidra.app.util.opinion.PeLoader;
import ghidra.app.util.opinion.PeLoader.CompilerOpinion.CompilerEnum;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.util.ProgramMemoryUtil;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Coverage for the Rec 37 {@code #37-11c-3} {@link CppVTableAnalyzer} (DD-0066): the
 * {@code Analyzer}-lifecycle wrapper that runs the DD-0065 {@link CppMsvcVftableScan} harvest
 * through the shared per-program {@link CppTypeSystemProvider} type system — the
 * {@link CppRttiAnalyzer} twin. The fixture and helpers are per-suite twins of
 * {@link CppRttiAnalyzerTest}'s and {@link CppMsvcVftableScanTest}'s (rule of three).
 */
public class CppVTableAnalyzerTest extends AbstractRttiTest {

	// Complete-flow fixture RTTI4 addresses (Base <- Shape <- Circle).
	private static final long BASE_RTTI4 = 0x01003340L;
	private static final long SHAPE_RTTI4 = 0x01003354L;
	private static final long CIRCLE_RTTI4 = 0x01003240L;
	// Each class's two slot functions, in vftable layout order.
	private static final long[] BASE_SLOTS = { 0x01001200L, 0x01001280L };
	private static final long[] SHAPE_SLOTS = { 0x01001214L, 0x01001230L };
	private static final long[] CIRCLE_SLOTS = { 0x01001260L, 0x010012a0L };

	@Test
	public void testRunsAfterUpstreamRttiAnalyzer() {
		// The harvest reads the vftable symbols upstream's RttiAnalyzer publishes, so this
		// analyzer must be ordered strictly after it.
		assertTrue("CppVTableAnalyzer must run after the upstream RttiAnalyzer",
			new CppVTableAnalyzer().getPriority().priority() >
				new RttiAnalyzer().getPriority().priority());
	}

	@Test
	public void testCanAnalyzeRequiresVisualStudioOrClangPe() throws Exception {
		// The fixture builds a Visual Studio PE (the same shape upstream's RttiAnalyzer accepts).
		ProgramBuilder builder = build32BitX86();
		ProgramDB program = builder.getProgram();
		CppVTableAnalyzer analyzer = new CppVTableAnalyzer();

		assertTrue("the fixture's Visual Studio PE must be accepted", analyzer.canAnalyze(program));

		setExecutableFormatAndCompiler(program, PeLoader.PE_NAME, CompilerEnum.GCC.toString());
		assertFalse("a non-VS/Clang PE must be declined", analyzer.canAnalyze(program));

		setExecutableFormatAndCompiler(program, BinaryLoader.BINARY_NAME,
			CompilerEnum.VisualStudio.toString());
		assertFalse("a non-PE program must be declined", analyzer.canAnalyze(program));
	}

	@Test
	public void testFeedsSharedTypeSystemFromPublishedVftables() throws Exception {
		ProgramDB program = buildAnalyzedCompleteFlowProgram();

		boolean result = new CppVTableAnalyzer().added(program, program.getMemory(),
			TaskMonitor.DUMMY, new MessageLog());

		assertTrue(result);
		CppTypeSystem typeSystem = CppTypeSystemProvider.get(program);
		assertEquals("all three classes must be fed into the shared type system", 3,
			typeSystem.getCppClasses().size());
		for (String className : new String[] { "Base", "Shape", "Circle" }) {
			CppVTable vtable = typeSystem.getCppClass(className).getVtable();
			assertNotNull(className + " must have a fed vtable", vtable);
			assertEquals(className + "'s vtable must have both slots", 2, vtable.getSlotCount());
			assertEquals("draw", vtable.getSlot(0).getName());
			assertEquals("area", vtable.getSlot(1).getName());
		}
	}

	@Test
	public void testComposesWithCppRttiAnalyzerOnTheSameSharedClasses() throws Exception {
		// The two fork analyzers feed the same provider instance: RTTI contributes the base edges,
		// the vftable pass contributes the vtables, both on the same CppClass objects.
		ProgramDB program = buildAnalyzedCompleteFlowProgram();
		TaskMonitor monitor = TaskMonitor.DUMMY;

		new CppRttiAnalyzer().added(program, program.getMemory(), monitor, new MessageLog());
		new CppVTableAnalyzer().added(program, program.getMemory(), monitor, new MessageLog());

		CppTypeSystem typeSystem = CppTypeSystemProvider.get(program);
		assertEquals("both feeds must share one set of classes", 3,
			typeSystem.getCppClasses().size());
		assertFalse("the RTTI feed must have contributed Circle's base edges",
			typeSystem.getCppClass("Circle").getBaseClasses().isEmpty());
		assertNotNull("the vftable feed must have contributed Circle's vtable",
			typeSystem.getCppClass("Circle").getVtable());
	}

	@Test
	public void testRepeatedTriggersLeaveTypeSystemCorrect() throws Exception {
		ProgramDB program = buildAnalyzedCompleteFlowProgram();
		CppVTableAnalyzer analyzer = new CppVTableAnalyzer();

		analyzer.added(program, program.getMemory(), TaskMonitor.DUMMY, new MessageLog());
		analyzer.added(program, program.getMemory(), TaskMonitor.DUMMY, new MessageLog());

		CppTypeSystem typeSystem = CppTypeSystemProvider.get(program);
		assertEquals("a second trigger must not duplicate classes", 3,
			typeSystem.getCppClasses().size());
		assertEquals("a second trigger must not duplicate slots", 2,
			typeSystem.getCppClass("Circle").getVtable().getSlotCount());
	}

	@Test
	public void testFeedsNothingWhenUpstreamAnalyzerHasNotRun() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();

		new CppVTableAnalyzer().added(program, program.getMemory(), TaskMonitor.DUMMY,
			new MessageLog());

		assertTrue("an unanalyzed program must leave the shared type system untouched",
			CppTypeSystemProvider.get(program).getCppClasses().isEmpty());
	}

	@Test
	public void testCancelledMonitorAbortsTheHarvest() throws Exception {
		ProgramDB program = buildAnalyzedCompleteFlowProgram();
		TaskMonitorAdapter monitor = new TaskMonitorAdapter(true);
		monitor.cancel();

		try {
			new CppVTableAnalyzer().added(program, program.getMemory(), monitor, new MessageLog());
			fail("a cancelled monitor must abort the harvest with CancelledException");
		}
		catch (CancelledException e) {
			// expected
		}
		assertTrue("a cancelled harvest must not have fed the type system",
			CppTypeSystemProvider.get(program).getCppClasses().isEmpty());
	}

	// Builds the complete-flow fixture with the RTTI4s applied (whose associated-vftable pass
	// publishes the vftable symbols) and every slot function named the way the demangler would.
	private ProgramDB buildAnalyzedCompleteFlowProgram() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();
		layDownRtti4Data(program, CIRCLE_RTTI4, BASE_RTTI4, SHAPE_RTTI4);
		nameSlots(builder, BASE_SLOTS);
		nameSlots(builder, SHAPE_SLOTS);
		nameSlots(builder, CIRCLE_SLOTS);
		return program;
	}

	private void nameSlots(ProgramBuilder builder, long[] slotFunctions) throws Exception {
		builder.createLabel("0x" + Long.toHexString(slotFunctions[0]), "draw");
		builder.createLabel("0x" + Long.toHexString(slotFunctions[1]), "area");
	}

	private void setExecutableFormatAndCompiler(ProgramDB program, String format, String compiler) {
		int txID = program.startTransaction("Setting format and compiler");
		try {
			program.setExecutableFormat(format);
			program.setCompiler(compiler);
		}
		finally {
			program.endTransaction(txID, true);
		}
	}

	private void layDownRtti4Data(ProgramDB program, long... rtti4Addresses) throws Exception {
		List<MemoryBlock> rtti4Blocks = ProgramMemoryUtil.getMemoryBlocksStartingWithName(program,
			program.getMemory(), ".rdata", TaskMonitor.DUMMY);
		int txID = program.startTransaction("Creating RTTI");
		boolean commit = false;
		try {
			for (long rtti4Address : rtti4Addresses) {
				CreateRtti4BackgroundCmd cmd =
					new CreateRtti4BackgroundCmd(addr(program, rtti4Address), rtti4Blocks,
						defaultValidationOptions, defaultApplyOptions);
				assertTrue("RTTI4 data must apply at 0x" + Long.toHexString(rtti4Address),
					cmd.applyTo(program));
			}
			commit = true;
		}
		finally {
			program.endTransaction(txID, commit);
		}
	}
}
