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
package ghidra.app.util.cpp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.app.util.cpp.CppVTableFeeder.SlotSpec;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;

/**
 * Headless unit tests for the {@code CppVTableFeeder} (Rec 37 {@code #37-6}, grounded by DD-0014).
 * These build recovered {@link SlotSpec} vtable facts directly — no {@code Program}, no
 * (script-land) {@code classrecovery.Vftable}, and no MSVC {@code VfTableModel} — emulating the
 * shape of a recovered vftable as an ordered list of slot methods.
 */
public class CppVTableFeederTest extends AbstractGenericTest {

	public CppVTableFeederTest() {
		super();
	}

	private static Structure struct(String name, int fieldCount) {
		StructureDataType s = new StructureDataType(name, 0);
		for (int i = 0; i < fieldCount; i++) {
			s.add(IntegerDataType.dataType, 4, "f" + i, null);
		}
		return s;
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeederRejectsNullTypeSystem() {
		new CppVTableFeeder(null);
	}

	@Test
	public void testSlotsBecomeVirtualMethodsInLayoutOrder() {
		CppTypeSystem ts = new CppTypeSystem();
		CppVTableFeeder feeder = new CppVTableFeeder(ts);

		CppVTable vtable = feeder.feedVtable("Shape", List.of(
			new SlotSpec("area", false),
			new SlotSpec("perimeter", false),
			new SlotSpec("describe", false)));

		CppClass owner = ts.getCppClass("Shape");
		assertSame("the vtable must be attached to the owning class", vtable, owner.getVtable());
		assertEquals(3, vtable.getSlotCount());
		assertEquals("area", vtable.getSlot(0).getName());
		assertEquals("perimeter", vtable.getSlot(1).getName());
		assertEquals("describe", vtable.getSlot(2).getName());
		for (CppMethod slot : vtable.getSlots()) {
			assertTrue("every vtable slot method is virtual", slot.isVirtual());
		}
	}

	@Test
	public void testPureVirtualSlotIsBothVirtualAndPure() {
		CppTypeSystem ts = new CppTypeSystem();
		CppVTableFeeder feeder = new CppVTableFeeder(ts);

		CppVTable vtable = feeder.feedVtable("AbstractBase", List.of(
			new SlotSpec("concreteImpl", false),
			new SlotSpec("pureSlot", true)));

		CppMethod concrete = vtable.getSlot(0);
		assertTrue(concrete.isVirtual());
		assertFalse("a normal slot is not pure-virtual", concrete.isPureVirtual());

		CppMethod pure = vtable.getSlot(1);
		assertTrue("a pure-virtual slot is still virtual", pure.isVirtual());
		assertTrue(pure.isPureVirtual());
	}

	@Test
	public void testEmptySlotListAttachesEmptyVtableButRegistersClass() {
		CppTypeSystem ts = new CppTypeSystem();
		CppVTableFeeder feeder = new CppVTableFeeder(ts);

		CppVTable vtable = feeder.feedVtable("NoVirtuals", List.of());

		CppClass owner = ts.getCppClass("NoVirtuals");
		assertNotNull(owner);
		assertSame(vtable, owner.getVtable());
		assertEquals(0, vtable.getSlotCount());
		assertTrue(vtable.getSlots().isEmpty());
	}

	@Test
	public void testOwningClassGetsEmptyPlaceholderWhenUnknown() {
		CppTypeSystem ts = new CppTypeSystem();
		CppVTableFeeder feeder = new CppVTableFeeder(ts);

		feeder.feedVtable("Unknown", List.of(new SlotSpec("f", false)));

		CppClass owner = ts.getCppClass("Unknown");
		assertNotNull(owner);
		assertEquals("Unknown", owner.getName());
		assertEquals("a placeholder owner has no recovered components",
			0, owner.getBackingStructure().getNumDefinedComponents());
	}

	@Test
	public void testExistingClassIsReusedWithoutClobberingBackingOrMethods() {
		CppTypeSystem ts = new CppTypeSystem();
		Structure backing = struct("Widget", 4);
		CppClass existing = ts.defineClass(backing);
		CppMethod declared = new CppMethod("ctor");
		existing.addMethod(declared);
		CppVTableFeeder feeder = new CppVTableFeeder(ts);

		CppVTable vtable = feeder.feedVtable("Widget", List.of(
			new SlotSpec("draw", false),
			new SlotSpec("resize", false)));

		assertSame("the recovered owning class must be reused", existing, ts.getCppClass("Widget"));
		assertEquals("backing layout must be untouched", 4, backing.getNumDefinedComponents());
		assertEquals("slot methods must not leak into the class method list", 1,
			existing.getMethods().size());
		assertSame("the pre-existing declared method must be untouched", declared,
			existing.getMethods().get(0));
		assertEquals(2, vtable.getSlotCount());
	}

	@Test
	public void testTableAddressIsNullFromFeeder() {
		CppTypeSystem ts = new CppTypeSystem();
		CppVTableFeeder feeder = new CppVTableFeeder(ts);

		// The recovered program address is the #37-6b scanner's to set, not the headless feeder's.
		CppVTable vtable = feeder.feedVtable("C", List.of(new SlotSpec("f", false)));
		assertNull(vtable.getTableAddress());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeedVtableRejectsNullOwningName() {
		new CppVTableFeeder(new CppTypeSystem()).feedVtable(null, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeedVtableRejectsBlankOwningName() {
		new CppVTableFeeder(new CppTypeSystem()).feedVtable("  ", List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeedVtableRejectsNullSlotList() {
		new CppVTableFeeder(new CppTypeSystem()).feedVtable("C", null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testSlotSpecRejectsNullMethodName() {
		new SlotSpec(null, false);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testSlotSpecRejectsBlankMethodName() {
		new SlotSpec(" ", false);
	}
}
