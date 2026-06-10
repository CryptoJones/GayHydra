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

import ghidra.app.util.cpp.CppBaseClass;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.util.ProgramMemoryUtil;
import ghidra.util.task.TaskMonitor;

/**
 * Shared fixture helpers for the Rec 37 {@code Cpp*} suites in this package — the extraction the
 * per-suite twins earned at three users (rule of three; see DD-0066). A fork-owned intermediate
 * class rather than additions to the upstream {@link AbstractRttiTest}, so upstream merges stay
 * clean.
 */
abstract class AbstractCppRttiTest extends AbstractRttiTest {

	/**
	 * Applies {@link CreateRtti4BackgroundCmd} at each RTTI4 address, simulating the upstream
	 * {@code RttiAnalyzer}'s data creation (the same application pattern as the upstream
	 * {@code RttiCreateCmdTest}) — including its associated-vftable pass, which publishes the
	 * {@code vftable} symbols the vftable harvest anchors on.
	 *
	 * @param program the fixture program
	 * @param rtti4Addresses the RTTI4 addresses to apply
	 * @throws Exception if the transaction fails
	 */
	protected void layDownRtti4Data(ProgramDB program, long... rtti4Addresses) throws Exception {
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

	/**
	 * Overwrites the {@code numContainedBases} dword (RTTI1 + 4) of one shared base class
	 * descriptor, letting a test express the true inheritance chain the complete-flow fixture's
	 * all-zero subtree sizes leave implicit.
	 *
	 * @param builder the fixture builder
	 * @param rtti1Address the base class descriptor's address
	 * @param numContainedBases the subtree size to write
	 * @throws Exception if the bytes cannot be set
	 */
	protected void setNumContainedBases(ProgramBuilder builder, long rtti1Address,
			int numContainedBases) throws Exception {
		boolean bigEndian =
			builder.getProgram().getCompilerSpec().getDataOrganization().isBigEndian();
		builder.setBytes(builder.addr(rtti1Address + 4).toString(),
			getIntAsByteString(numContainedBases, bigEndian));
	}

	/**
	 * Asserts {@code derived} has exactly one direct base edge: non-virtual, public, offset 0,
	 * pointing at the resolved {@code base} {@code CppClass}.
	 *
	 * @param typeSystem the fed type system
	 * @param derived the derived class's name
	 * @param base the expected base class's name
	 */
	protected static void assertSingleBaseEdge(CppTypeSystem typeSystem, String derived,
			String base) {
		List<CppBaseClass> bases = typeSystem.getCppClass(derived).getBaseClasses();
		assertEquals(derived + " must have exactly one direct base", 1, bases.size());
		CppBaseClass edge = bases.get(0);
		assertSame("the edge must point at the resolved base CppClass",
			typeSystem.getCppClass(base), edge.getBaseClass());
		assertEquals(0, edge.getOffset());
		assertFalse(edge.isVirtual());
		assertTrue(edge.isPublic());
	}
}
