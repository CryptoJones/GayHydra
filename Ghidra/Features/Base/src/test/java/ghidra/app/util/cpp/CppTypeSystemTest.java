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
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;

/**
 * Headless unit tests for the model-only {@code CppTypeSystem} skeleton (Rec 37 {@code #37-2},
 * grounded by DD-0011). These exercise the data model against standalone {@link StructureDataType}
 * fixtures, so no program or {@code DataTypeManager} is required.
 */
public class CppTypeSystemTest extends AbstractGenericTest {

	public CppTypeSystemTest() {
		super();
	}

	private static Structure struct(String name, int fieldCount) {
		StructureDataType s = new StructureDataType(name, 0);
		for (int i = 0; i < fieldCount; i++) {
			s.add(IntegerDataType.dataType, 4, "f" + i, null);
		}
		return s;
	}

	@Test
	public void testDefineClassProjectsBackingStructure() {
		CppTypeSystem ts = new CppTypeSystem();
		Structure backing = struct("Widget", 2);
		CppClass cls = ts.defineClass(backing);

		assertNotNull(cls);
		assertSame(backing, cls.getBackingStructure());
		assertEquals("Widget", cls.getName());
		assertEquals(backing.getName(), cls.getName());
	}

	@Test
	public void testDefineClassIsIdempotentByName() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass first = ts.defineClass(struct("Widget", 1));
		CppClass second = ts.defineClass(struct("Widget", 1));

		assertSame("defining an existing name must return the same projection", first, second);
		assertEquals(1, ts.getCppClasses().size());
		assertSame(first, ts.getCppClass("Widget"));
	}

	@Test
	public void testRegistryPreservesDefinitionOrder() {
		CppTypeSystem ts = new CppTypeSystem();
		ts.defineClass(struct("Alpha", 0));
		ts.defineClass(struct("Beta", 0));
		ts.defineClass(struct("Gamma", 0));

		assertEquals(List.of("Alpha", "Beta", "Gamma"),
			List.copyOf(ts.getCppClasses().keySet()));
	}

	@Test
	public void testProjectionDoesNotClobberBackingStructure() {
		CppTypeSystem ts = new CppTypeSystem();
		Structure backing = struct("Widget", 3);
		int componentsBefore = backing.getNumDefinedComponents();
		int lengthBefore = backing.getLength();

		CppClass cls = ts.defineClass(backing);
		cls.addMethod(new CppMethod("doThing"));
		cls.addBaseClass(new CppBaseClass(ts.defineClass(struct("Base", 1)), 0, false, true));
		cls.setVtable(new CppVTable());

		assertEquals("backing structure layout must be untouched by C++ annotations",
			componentsBefore, backing.getNumDefinedComponents());
		assertEquals(lengthBefore, backing.getLength());
	}

	@Test
	public void testInheritanceEdgeRoundTrip() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass base = ts.defineClass(struct("Base", 1));
		CppClass derived = ts.defineClass(struct("Derived", 1));
		derived.addBaseClass(new CppBaseClass(base, 8, true, false));

		List<CppBaseClass> bases = derived.getBaseClasses();
		assertEquals(1, bases.size());
		CppBaseClass edge = bases.get(0);
		assertSame(base, edge.getBaseClass());
		assertEquals(8, edge.getOffset());
		assertTrue(edge.isVirtual());
		assertFalse(edge.isPublic());
	}

	@Test
	public void testMethodQualifiersAndConvention() {
		CppMethod m = new CppMethod("clone");
		m.setVirtual(true);
		m.setPureVirtual(true);
		m.setConst(true);
		m.setCallingConvention(new CppCallingConvention("__thiscall", 0));

		assertEquals("clone", m.getName());
		assertTrue(m.isVirtual());
		assertTrue(m.isPureVirtual());
		assertTrue(m.isConst());
		assertFalse(m.isStatic());
		assertNotNull(m.getCallingConvention());
		assertEquals("__thiscall", m.getCallingConvention().getName());
		assertTrue(m.getCallingConvention().hasImplicitThis());
		assertEquals(0, m.getCallingConvention().getThisParameterOrdinal());
	}

	@Test
	public void testStaticMethodHasNoImplicitThis() {
		CppCallingConvention conv = new CppCallingConvention("__cdecl", CppCallingConvention.NO_THIS);
		assertFalse(conv.hasImplicitThis());
		assertEquals(CppCallingConvention.NO_THIS, conv.getThisParameterOrdinal());
	}

	@Test
	public void testVtableSlotMapping() {
		CppVTable vt = new CppVTable();
		CppMethod m0 = new CppMethod("~Widget");
		CppMethod m1 = new CppMethod("draw");

		assertEquals(0, vt.addSlot(m0));
		assertEquals(1, vt.addSlot(m1));
		assertEquals(2, vt.getSlotCount());
		assertSame(m0, vt.getSlot(0));
		assertSame(m1, vt.getSlot(1));
		assertEquals(List.of(m0, m1), vt.getSlots());
	}

	@Test
	public void testVtableSlotsViewIsUnmodifiable() {
		CppVTable vt = new CppVTable();
		vt.addSlot(new CppMethod("draw"));
		try {
			vt.getSlots().add(new CppMethod("sneaky"));
			fail("expected the slot view to be unmodifiable");
		}
		catch (UnsupportedOperationException expected) {
			// expected
		}
	}

	@Test
	public void testMethodAttachmentToClass() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass cls = ts.defineClass(struct("Widget", 0));
		CppMethod m = new CppMethod("draw");
		cls.addMethod(m);

		assertEquals(1, cls.getMethods().size());
		assertSame(m, cls.getMethods().get(0));
	}

	@Test
	public void testBackingNamespaceDefaultsNull() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass cls = ts.defineClass(struct("Widget", 0));
		assertNull(cls.getBackingNamespace());
	}

	@Test
	public void testUnboundTypeSystemHasNullManager() {
		assertNull(new CppTypeSystem().getDataTypeManager());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testDefineClassRejectsNullBacking() {
		new CppTypeSystem().defineClass(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCppClassRejectsNullBacking() {
		new CppClass(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBaseClassRejectsNullBase() {
		new CppBaseClass(null, 0, false, true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testMethodRejectsNullName() {
		new CppMethod(null);
	}
}
