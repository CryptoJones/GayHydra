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

import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.app.util.cpp.CppVTable;
import ghidra.app.util.cpp.CppVTableFeeder;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Coverage for the Rec 37 {@code #37-11c-2} {@link CppMsvcVftableScan} (DD-0065): the program-wide
 * vftable harvest that walks {@code vftable}-named symbols and feeds each table through
 * {@link CppMsvcVftableDriver}. The fixture simulates "upstream's RTTI machinery has run" by applying
 * {@link CreateRtti4BackgroundCmd}, whose associated-vftable pass lays down the vftable data and
 * publishes the {@code vftable} symbols this scan anchors on; the slot functions are then named the
 * way the demangler would (the shared {@link AbstractCppRttiTest} helpers).
 */
public class CppMsvcVftableScanTest extends AbstractCppRttiTest {

	// Complete-flow fixture RTTI4 addresses (Base <- Shape <- Circle).
	private static final long BASE_RTTI4 = 0x01003340L;
	private static final long SHAPE_RTTI4 = 0x01003354L;
	private static final long CIRCLE_RTTI4 = 0x01003240L;
	// Each class's two slot functions, in vftable layout order.
	private static final long[] BASE_SLOTS = { 0x01001200L, 0x01001280L };
	private static final long[] SHAPE_SLOTS = { 0x01001214L, 0x01001230L };
	private static final long[] CIRCLE_SLOTS = { 0x01001260L, 0x010012a0L };

	@Test
	public void testHarvestsAllLaidDownVftables() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();
		layDownRtti4Data(program, CIRCLE_RTTI4, BASE_RTTI4, SHAPE_RTTI4);
		nameSlots(builder, BASE_SLOTS);
		nameSlots(builder, SHAPE_SLOTS);
		nameSlots(builder, CIRCLE_SLOTS);

		CppTypeSystem typeSystem = new CppTypeSystem();
		List<CppVTable> fed = CppMsvcVftableScan.feedProgram(program,
			new CppVTableFeeder(typeSystem), defaultValidationOptions);

		assertEquals("all three laid-down vftables must be harvested", 3, fed.size());
		for (String className : new String[] { "Base", "Shape", "Circle" }) {
			CppVTable vtable = typeSystem.getCppClass(className).getVtable();
			assertNotNull(className + " must have a fed vtable", vtable);
			assertEquals(className + "'s vtable must have both slots", 2, vtable.getSlotCount());
			assertEquals("draw", vtable.getSlot(0).getName());
			assertEquals("area", vtable.getSlot(1).getName());
			assertNotNull(className + "'s table address must be set", vtable.getTableAddress());
		}
	}

	@Test
	public void testHarvestsNothingWhenNoVftablesLaidDown() throws Exception {
		// The vftable bytes exist in memory, but upstream's RTTI machinery never ran — no vftable
		// symbols were published. Harvest (not re-discovery) means the scan feeds nothing.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();
		nameSlots(builder, CIRCLE_SLOTS);

		CppTypeSystem typeSystem = new CppTypeSystem();
		List<CppVTable> fed = CppMsvcVftableScan.feedProgram(program,
			new CppVTableFeeder(typeSystem), defaultValidationOptions);

		assertTrue("no published vftable symbols must mean no harvested tables", fed.isEmpty());
		assertTrue("the type system must be untouched", typeSystem.getCppClasses().isEmpty());
	}

	@Test
	public void testUnnamedSlotsDeclinePerTableNotPerProgram() throws Exception {
		// Only Circle's slot functions are named — its table feeds; Base's and Shape's decline
		// individually (the driver's whole-table gate), not the whole harvest.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();
		layDownRtti4Data(program, CIRCLE_RTTI4, BASE_RTTI4, SHAPE_RTTI4);
		nameSlots(builder, CIRCLE_SLOTS);

		CppTypeSystem typeSystem = new CppTypeSystem();
		List<CppVTable> fed = CppMsvcVftableScan.feedProgram(program,
			new CppVTableFeeder(typeSystem), defaultValidationOptions);

		assertEquals("only the nameable table must feed", 1, fed.size());
		assertSame(fed.get(0), typeSystem.getCppClass("Circle").getVtable());
		assertEquals("declined tables must not create classes", 1,
			typeSystem.getCppClasses().size());
	}

	@Test
	public void testCancelledMonitorAbortsTheHarvest() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();
		layDownRtti4Data(program, CIRCLE_RTTI4, BASE_RTTI4, SHAPE_RTTI4);
		nameSlots(builder, CIRCLE_SLOTS);
		TaskMonitorAdapter monitor = new TaskMonitorAdapter(true);
		monitor.cancel();

		CppTypeSystem typeSystem = new CppTypeSystem();
		try {
			CppMsvcVftableScan.feedProgram(program, new CppVTableFeeder(typeSystem),
				defaultValidationOptions, monitor);
			fail("a cancelled monitor must abort the harvest with CancelledException");
		}
		catch (CancelledException e) {
			// expected
		}
		assertTrue("a cancelled harvest must not have fed the type system",
			typeSystem.getCppClasses().isEmpty());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullProgram() throws Exception {
		CppMsvcVftableScan.feedProgram(null, new CppVTableFeeder(new CppTypeSystem()),
			defaultValidationOptions);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullFeeder() throws Exception {
		ProgramBuilder builder = build32BitX86();
		CppMsvcVftableScan.feedProgram(builder.getProgram(), null, defaultValidationOptions);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullValidationOptions() throws Exception {
		ProgramBuilder builder = build32BitX86();
		CppMsvcVftableScan.feedProgram(builder.getProgram(),
			new CppVTableFeeder(new CppTypeSystem()), null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullMonitor() throws Exception {
		ProgramBuilder builder = build32BitX86();
		CppMsvcVftableScan.feedProgram(builder.getProgram(),
			new CppVTableFeeder(new CppTypeSystem()), defaultValidationOptions, null);
	}

	// Names a class's two slot functions the way the demangler would (a primary symbol each).
	private void nameSlots(ProgramBuilder builder, long[] slotFunctions) throws Exception {
		builder.createLabel("0x" + Long.toHexString(slotFunctions[0]), "draw");
		builder.createLabel("0x" + Long.toHexString(slotFunctions[1]), "area");
	}
}
