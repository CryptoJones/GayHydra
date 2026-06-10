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
import ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin.RttiAnalyzer;
import ghidra.app.util.cpp.CppBaseClass;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.app.util.cpp.CppTypeSystemProvider;
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
 * Coverage for the Rec 37 {@code #37-11b} {@link CppRttiAnalyzer} (DD-0063): the
 * {@code Analyzer}-lifecycle wrapper that runs the DD-0061 {@link CppMsvcRttiScan} harvest through
 * the shared per-program {@link CppTypeSystemProvider} type system. The fixture simulates
 * "upstream's {@code RttiAnalyzer} has already run" the same way {@link CppMsvcRttiScanTest} does:
 * by applying {@link CreateRtti4BackgroundCmd} at the complete-flow RTTI4 addresses.
 *
 * <p>The fixture helpers are per-suite twins of {@link CppMsvcRttiScanTest}'s (rule of three; a
 * third user earns the {@link AbstractRttiTest} extraction).
 */
public class CppRttiAnalyzerTest extends AbstractRttiTest {

	// Complete-flow fixture structure addresses (Base <- Shape <- Circle).
	private static final long BASE_RTTI4 = 0x01003340L;
	private static final long SHAPE_RTTI4 = 0x01003354L;
	private static final long CIRCLE_RTTI4 = 0x01003240L;
	private static final long BASE_RTTI1 = 0x010033a8L;
	private static final long SHAPE_RTTI1 = 0x010033c4L;
	private static final long CIRCLE_RTTI1 = 0x010032a8L;

	@Test
	public void testRunsAfterUpstreamRttiAnalyzer() {
		// The harvest reads the data upstream's RttiAnalyzer lays down, so this analyzer must be
		// ordered strictly after it.
		assertTrue("CppRttiAnalyzer must run after the upstream RttiAnalyzer",
			new CppRttiAnalyzer().getPriority().priority() >
				new RttiAnalyzer().getPriority().priority());
	}

	@Test
	public void testCanAnalyzeRequiresVisualStudioOrClangPe() throws Exception {
		// The fixture builds a Visual Studio PE (the same shape upstream's RttiAnalyzer accepts).
		ProgramBuilder builder = build32BitX86();
		ProgramDB program = builder.getProgram();
		CppRttiAnalyzer analyzer = new CppRttiAnalyzer();

		assertTrue("the fixture's Visual Studio PE must be accepted", analyzer.canAnalyze(program));

		setExecutableFormatAndCompiler(program, PeLoader.PE_NAME, CompilerEnum.GCC.toString());
		assertFalse("a non-VS/Clang PE must be declined", analyzer.canAnalyze(program));

		setExecutableFormatAndCompiler(program, BinaryLoader.BINARY_NAME,
			CompilerEnum.VisualStudio.toString());
		assertFalse("a non-PE program must be declined", analyzer.canAnalyze(program));
	}

	@Test
	public void testFeedsSharedTypeSystemFromLaidDownRtti4s() throws Exception {
		ProgramDB program = buildAnalyzedCompleteFlowProgram();

		boolean result = new CppRttiAnalyzer().added(program, program.getMemory(),
			TaskMonitor.DUMMY, new MessageLog());

		assertTrue(result);
		CppTypeSystem typeSystem = CppTypeSystemProvider.get(program);
		assertEquals("all three laid-down RTTI4s must be fed into the shared type system", 3,
			typeSystem.getCppClasses().size());
		assertTrue("Base must have no direct bases",
			typeSystem.getCppClass("Base").getBaseClasses().isEmpty());
		assertSingleBaseEdge(typeSystem, "Shape", "Base");
		assertSingleBaseEdge(typeSystem, "Circle", "Shape");
	}

	@Test
	public void testRepeatedTriggersLeaveTypeSystemCorrect() throws Exception {
		// A byte analyzer can be triggered repeatedly as analysis progresses; re-feeding the shared
		// type system must be a no-op, not a duplication or a corruption.
		ProgramDB program = buildAnalyzedCompleteFlowProgram();
		CppRttiAnalyzer analyzer = new CppRttiAnalyzer();

		analyzer.added(program, program.getMemory(), TaskMonitor.DUMMY, new MessageLog());
		analyzer.added(program, program.getMemory(), TaskMonitor.DUMMY, new MessageLog());

		CppTypeSystem typeSystem = CppTypeSystemProvider.get(program);
		assertEquals("a second trigger must not duplicate classes", 3,
			typeSystem.getCppClasses().size());
		assertSingleBaseEdge(typeSystem, "Shape", "Base");
		assertSingleBaseEdge(typeSystem, "Circle", "Shape");
	}

	@Test
	public void testFeedsNothingWhenUpstreamAnalyzerHasNotRun() throws Exception {
		// RTTI bytes exist in memory but no RTTICompleteObjectLocator data was created — harvest
		// (not re-discovery) means the analyzer feeds nothing.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();

		new CppRttiAnalyzer().added(program, program.getMemory(), TaskMonitor.DUMMY,
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
			new CppRttiAnalyzer().added(program, program.getMemory(), monitor, new MessageLog());
			fail("a cancelled monitor must abort the harvest with CancelledException");
		}
		catch (CancelledException e) {
			// expected
		}
		assertTrue("a cancelled harvest must not have fed the type system",
			CppTypeSystemProvider.get(program).getCppClasses().isEmpty());
	}

	// Builds the complete-flow fixture with the true single-inheritance chain
	// Base <- Shape <- Circle and the three RTTI4s laid down, simulating a program the upstream
	// RttiAnalyzer has already processed.
	private ProgramDB buildAnalyzedCompleteFlowProgram() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		// True single-inheritance chain Base <- Shape <- Circle.
		setNumContainedBases(builder, BASE_RTTI1, 0);
		setNumContainedBases(builder, SHAPE_RTTI1, 1);
		setNumContainedBases(builder, CIRCLE_RTTI1, 2);
		ProgramDB program = builder.getProgram();
		layDownRtti4Data(program, CIRCLE_RTTI4, BASE_RTTI4, SHAPE_RTTI4);
		return program;
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

	// Applies CreateRtti4BackgroundCmd at each RTTI4 address, simulating the upstream RttiAnalyzer's
	// data creation (the same application pattern as the upstream RttiCreateCmdTest).
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

	private static void assertSingleBaseEdge(CppTypeSystem typeSystem, String derived, String base) {
		List<CppBaseClass> bases = typeSystem.getCppClass(derived).getBaseClasses();
		assertEquals(derived + " must have exactly one direct base", 1, bases.size());
		CppBaseClass edge = bases.get(0);
		assertSame("the edge must point at the resolved base CppClass",
			typeSystem.getCppClass(base), edge.getBaseClass());
		assertEquals(0, edge.getOffset());
		assertFalse(edge.isVirtual());
		assertTrue(edge.isPublic());
	}

	// Overwrites the numContainedBases dword (RTTI1 + 4) of one shared base class descriptor.
	private void setNumContainedBases(ProgramBuilder builder, long rtti1Address, int numContainedBases)
			throws Exception {
		boolean bigEndian =
			builder.getProgram().getCompilerSpec().getDataOrganization().isBigEndian();
		builder.setBytes(builder.addr(rtti1Address + 4).toString(),
			getIntAsByteString(numContainedBases, bigEndian));
	}
}
