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

import ghidra.app.util.cpp.CppBaseClass;
import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppRttiFeeder;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.util.ProgramMemoryUtil;
import ghidra.util.task.TaskMonitor;

/**
 * End-to-end coverage for the Rec 37 {@code #37-5-4} {@link CppMsvcRttiScan} (DD-0061): the program-wide
 * harvest that walks defined {@code RTTICompleteObjectLocator} data and feeds each entry through
 * {@link CppMsvcRttiDriver}. The fixture simulates "Ghidra's {@code RttiAnalyzer} has already run" by
 * applying {@link CreateRtti4BackgroundCmd} at the complete-flow RTTI4 addresses, exactly as the upstream
 * {@code RttiCreateCmdTest} does.
 *
 * <p>As in {@link CppMsvcRttiDriverTest}, the complete-flow fixture's RTTI1 descriptors all carry
 * {@code numContainedBases == 0}, so the harvest test writes the real subtree sizes in to express the
 * true single-inheritance chain {@code Base <- Shape <- Circle}.
 */
public class CppMsvcRttiScanTest extends AbstractRttiTest {

	// Complete-flow fixture structure addresses (Base <- Shape <- Circle).
	private static final long BASE_RTTI4 = 0x01003340L;
	private static final long SHAPE_RTTI4 = 0x01003354L;
	private static final long CIRCLE_RTTI4 = 0x01003240L;
	private static final long BASE_RTTI1 = 0x010033a8L;
	private static final long SHAPE_RTTI1 = 0x010033c4L;
	private static final long CIRCLE_RTTI1 = 0x010032a8L;

	@Test
	public void testHarvestsAllLaidDownRtti4s() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		// True single-inheritance chain Base <- Shape <- Circle.
		setNumContainedBases(builder, BASE_RTTI1, 0);
		setNumContainedBases(builder, SHAPE_RTTI1, 1);
		setNumContainedBases(builder, CIRCLE_RTTI1, 2);
		ProgramDB program = builder.getProgram();
		layDownRtti4Data(program, CIRCLE_RTTI4, BASE_RTTI4, SHAPE_RTTI4);

		CppTypeSystem typeSystem = new CppTypeSystem();
		List<CppClass> fed =
			CppMsvcRttiScan.feedProgram(program, new CppRttiFeeder(typeSystem),
				defaultValidationOptions);

		assertEquals("all three laid-down RTTI4s must be harvested", 3, fed.size());
		// Defined-data order is address order: Circle's RTTI4 (0x...3240) precedes Base's and
		// Shape's, proving the feeder's placeholder resolution tolerates derived-before-base.
		assertEquals("Circle", fed.get(0).getName());
		assertEquals("Base", fed.get(1).getName());
		assertEquals("Shape", fed.get(2).getName());

		assertTrue("Base must have no direct bases",
			typeSystem.getCppClass("Base").getBaseClasses().isEmpty());
		assertSingleBaseEdge(typeSystem, "Shape", "Base");
		assertSingleBaseEdge(typeSystem, "Circle", "Shape");
	}

	@Test
	public void testHarvestsNothingWhenNoRtti4DataLaidDown() throws Exception {
		// The RTTI bytes exist in memory, but no RTTICompleteObjectLocator data was ever created —
		// the upstream analyzer has not run. Harvest (not re-discovery) means the scan feeds nothing.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		List<CppClass> fed =
			CppMsvcRttiScan.feedProgram(program, new CppRttiFeeder(typeSystem),
				defaultValidationOptions);

		assertTrue("no laid-down RTTI4 data must mean no harvested classes", fed.isEmpty());
		assertTrue("the type system must be untouched", typeSystem.getCppClasses().isEmpty());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullProgram() {
		CppMsvcRttiScan.feedProgram(null, new CppRttiFeeder(new CppTypeSystem()),
			defaultValidationOptions);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullFeeder() throws Exception {
		ProgramBuilder builder = build32BitX86();
		CppMsvcRttiScan.feedProgram(builder.getProgram(), null, defaultValidationOptions);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullValidationOptions() throws Exception {
		ProgramBuilder builder = build32BitX86();
		CppMsvcRttiScan.feedProgram(builder.getProgram(),
			new CppRttiFeeder(new CppTypeSystem()), null);
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
