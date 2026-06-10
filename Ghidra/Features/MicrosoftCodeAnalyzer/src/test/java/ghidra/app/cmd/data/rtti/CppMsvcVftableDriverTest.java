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

import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppMethod;
import ghidra.app.util.cpp.CppRttiFeeder;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.app.util.cpp.CppVTable;
import ghidra.app.util.cpp.CppVTableFeeder;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;

/**
 * Coverage for the Rec 37 {@code #37-11c-1} {@link CppMsvcVftableDriver} (DD-0064): one located MSVC
 * {@code vftable} ({@link VfTableModel}) recovered into a {@link CppVTable} on its owning class. The
 * complete-flow fixture lays each class's vftable with a meta pointer at {@code table - 4} and two
 * slot entries; the tests name the slot functions the way the demangler would (a primary symbol per
 * function) and assert the recovered table, the decline gates, and the attachment to the same
 * {@link CppClass} the RTTI feed resolves.
 */
public class CppMsvcVftableDriverTest extends AbstractRttiTest {

	// Complete-flow fixture addresses (32-bit). The setupVfTable_32 address is the META pointer;
	// the vftable's own data (what VfTableModel models) starts one pointer later.
	private static final long CIRCLE_VFTABLE = 0x010031f4L;
	private static final long CIRCLE_SLOT_0_FUNCTION = 0x01001260L;
	private static final long CIRCLE_SLOT_1_FUNCTION = 0x010012a0L;
	private static final long CIRCLE_RTTI4 = 0x01003240L;

	@Test
	public void testFeedsVtableFromLaidDownVftable() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_0_FUNCTION), "draw");
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_1_FUNCTION), "area");
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppVTable fed = CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, CIRCLE_VFTABLE), defaultValidationOptions),
			new CppVTableFeeder(typeSystem));

		assertNotNull("the laid-down vftable must feed", fed);
		assertSame("the table must be attached to the owning class",
			fed, typeSystem.getCppClass("Circle").getVtable());
		assertEquals("the table address must be set for downstream consumers",
			addr(program, CIRCLE_VFTABLE), fed.getTableAddress());
		List<CppMethod> slots = fed.getSlots();
		assertEquals("both slots must be recovered in layout order", 2, slots.size());
		assertEquals("draw", slots.get(0).getName());
		assertEquals("area", slots.get(1).getName());
		for (CppMethod slot : slots) {
			assertTrue("a vtable slot is virtual dispatch", slot.isVirtual());
			assertFalse("no fixture slot is pure-virtual", slot.isPureVirtual());
		}
	}

	@Test
	public void testAttachesToTheSameClassTheRttiFeedResolves() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_0_FUNCTION), "draw");
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_1_FUNCTION), "area");
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppClass rttiFed = CppMsvcRttiDriver.feedClass(
			new Rtti4Model(program, addr(program, CIRCLE_RTTI4), defaultValidationOptions),
			new CppRttiFeeder(typeSystem));
		CppVTable vtableFed = CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, CIRCLE_VFTABLE), defaultValidationOptions),
			new CppVTableFeeder(typeSystem));

		assertNotNull(rttiFed);
		assertNotNull(vtableFed);
		assertSame("both feeds must land on the same CppClass — no name translation layer",
			rttiFed.getVtable(), vtableFed);
	}

	@Test
	public void testSlotWithDefinedFunctionCarriesItsSignature() throws Exception {
		// #37-12b: when a Function is defined at a slot's address (the demangler analyzer has
		// applied its signature), the fed slot method carries a definition built from it; a
		// label-only slot stays signatureless.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		builder.createEmptyFunction("draw", "0x" + Long.toHexString(CIRCLE_SLOT_0_FUNCTION), 1,
			new ghidra.program.model.data.IntegerDataType());
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_1_FUNCTION), "area");
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppVTable fed = CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, CIRCLE_VFTABLE), defaultValidationOptions),
			new CppVTableFeeder(typeSystem));

		assertNotNull(fed);
		assertNotNull("the defined function's signature must carry onto the slot",
			fed.getSlot(0).getSignature());
		assertEquals("int", fed.getSlot(0).getSignature().getReturnType().getName());
		assertNull("a label-only slot stays signatureless", fed.getSlot(1).getSignature());
	}

	@Test
	public void testDeclinesWhenASlotHasNoSymbol() throws Exception {
		// No slot function is named — there is no faithful method name, so the whole table declines.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppVTable fed = CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, CIRCLE_VFTABLE), defaultValidationOptions),
			new CppVTableFeeder(typeSystem));

		assertNull("an unnamed slot must decline the whole table", fed);
		assertTrue("a declined table must leave the type system untouched",
			typeSystem.getCppClasses().isEmpty());
	}

	@Test
	public void testDeclinesWhenASlotSymbolIsDefault() throws Exception {
		// A default FUN_... name (analysis ran, demangler did not name the function) is not a
		// faithful method name — never-wrong declines the table rather than render FUN_01001260.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		builder.createEmptyFunction(null, "0x" + Long.toHexString(CIRCLE_SLOT_0_FUNCTION), 1, null);
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_1_FUNCTION), "area");
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppVTable fed = CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, CIRCLE_VFTABLE), defaultValidationOptions),
			new CppVTableFeeder(typeSystem));

		assertNull("a default-named slot must decline the whole table", fed);
		assertTrue(typeSystem.getCppClasses().isEmpty());
	}

	@Test
	public void testDeclinesAPurecallSlot() throws Exception {
		// An abstract class's pure-virtual slot points at _purecall — the runtime trap's name, not
		// the method's. Recovering pure-virtual names is a later slice; decline for now.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_0_FUNCTION), "_purecall");
		builder.createLabel("0x" + Long.toHexString(CIRCLE_SLOT_1_FUNCTION), "area");
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppVTable fed = CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, CIRCLE_VFTABLE), defaultValidationOptions),
			new CppVTableFeeder(typeSystem));

		assertNull("a _purecall slot must decline the whole table", fed);
		assertTrue(typeSystem.getCppClasses().isEmpty());
	}

	@Test
	public void testDeclinesAModelThatDoesNotValidate() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();

		// An RTTI2 base-class array address is not a vftable; validation must decline it.
		CppVTable fed = CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, 0x01003290L), defaultValidationOptions),
			new CppVTableFeeder(new CppTypeSystem()));

		assertNull("an invalid model must contribute nothing", fed);
	}

	@Test
	public void testNullModelContributesNothing() {
		assertNull(CppMsvcVftableDriver.feedVtable(null, new CppVTableFeeder(new CppTypeSystem())));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullFeeder() throws Exception {
		ProgramBuilder builder = build32BitX86();
		ProgramDB program = builder.getProgram();
		CppMsvcVftableDriver.feedVtable(
			new VfTableModel(program, addr(program, CIRCLE_VFTABLE), defaultValidationOptions),
			null);
	}
}
