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
import ghidra.app.util.cpp.CppRttiFeeder.BaseSpec;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;

/**
 * Headless unit tests for the {@code CppRttiFeeder} (Rec 37 {@code #37-4}, grounded by DD-0013).
 * These build recovered {@link BaseSpec} inheritance facts directly — no {@code Program} and no
 * (script-land) {@code classrecovery.GccTypeinfo} — emulating the shapes of the three Itanium
 * typeinfo kinds the recoverer distinguishes.
 */
public class CppRttiFeederTest extends AbstractGenericTest {

	public CppRttiFeederTest() {
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
		new CppRttiFeeder(null);
	}

	@Test
	public void testSingleInheritanceProducesOneEdge() {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);

		// __si_class_type_info shape: a single public, non-virtual base at offset 0
		CppClass derived = feeder.feedClass("Derived", List.of(new BaseSpec("Base", 0, false, true)));

		List<CppBaseClass> bases = derived.getBaseClasses();
		assertEquals(1, bases.size());
		CppBaseClass edge = bases.get(0);
		assertEquals(0, edge.getOffset());
		assertFalse(edge.isVirtual());
		assertTrue(edge.isPublic());
		assertSame("the edge must point at the resolved base CppClass", ts.getCppClass("Base"),
			edge.getBaseClass());
	}

	@Test
	public void testRefeedingTheSameClassAddsNoDuplicateEdges() {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);
		List<BaseSpec> bases = List.of(new BaseSpec("Base", 0, false, true));

		// A contributor can run repeatedly (an analyzer re-triggered as analysis progresses);
		// re-feeding the same recovered facts must be a no-op, not an edge duplication.
		CppClass derived = feeder.feedClass("Derived", bases);
		feeder.feedClass("Derived", bases);

		assertEquals("re-feeding identical facts must not duplicate the edge", 1,
			derived.getBaseClasses().size());

		// A genuinely different edge to the same base (repeated non-virtual base at another
		// offset) is not a duplicate and must still attach.
		feeder.feedClass("Derived", List.of(new BaseSpec("Base", 8, false, true)));
		assertEquals("a distinct edge to the same base must still attach", 2,
			derived.getBaseClasses().size());
	}

	@Test
	public void testMultipleAndVirtualInheritancePreservesOrderOffsetsAndFlags() {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);

		// __vmi_class_type_info shape: several bases with distinct offsets and virtual/access flags
		CppClass derived = feeder.feedClass("Derived", List.of(
			new BaseSpec("PublicNonVirtual", 0, false, true),
			new BaseSpec("PrivateNonVirtual", 8, false, false),
			new BaseSpec("PublicVirtual", 16, true, true)));

		List<CppBaseClass> bases = derived.getBaseClasses();
		assertEquals(3, bases.size());

		assertSame(ts.getCppClass("PublicNonVirtual"), bases.get(0).getBaseClass());
		assertEquals(0, bases.get(0).getOffset());
		assertFalse(bases.get(0).isVirtual());
		assertTrue(bases.get(0).isPublic());

		assertSame(ts.getCppClass("PrivateNonVirtual"), bases.get(1).getBaseClass());
		assertEquals(8, bases.get(1).getOffset());
		assertFalse(bases.get(1).isVirtual());
		assertFalse(bases.get(1).isPublic());

		assertSame(ts.getCppClass("PublicVirtual"), bases.get(2).getBaseClass());
		assertEquals(16, bases.get(2).getOffset());
		assertTrue(bases.get(2).isVirtual());
		assertTrue(bases.get(2).isPublic());
	}

	@Test
	public void testNoBaseClassShapeAddsNoEdgesButRegistersClass() {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);

		// __class_type_info shape: a class with no bases
		CppClass leaf = feeder.feedClass("Leaf", List.of());

		assertNotNull(leaf);
		assertSame(leaf, ts.getCppClass("Leaf"));
		assertTrue(leaf.getBaseClasses().isEmpty());
	}

	@Test
	public void testBothEndpointsGetEmptyPlaceholderWhenUnknown() {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);

		feeder.feedClass("Derived", List.of(new BaseSpec("Base", 0, false, true)));

		CppClass derived = ts.getCppClass("Derived");
		CppClass base = ts.getCppClass("Base");
		assertNotNull(derived);
		assertNotNull(base);
		assertEquals("Derived", derived.getName());
		assertEquals("Base", base.getName());
		assertEquals("a placeholder derived has no recovered components",
			0, derived.getBackingStructure().getNumDefinedComponents());
		assertEquals("a placeholder base has no recovered components",
			0, base.getBackingStructure().getNumDefinedComponents());
	}

	@Test
	public void testExistingClassesAreReusedWithoutClobberingBacking() {
		CppTypeSystem ts = new CppTypeSystem();
		Structure derivedBacking = struct("Derived", 3);
		Structure baseBacking = struct("Base", 2);
		CppClass existingDerived = ts.defineClass(derivedBacking);
		CppClass existingBase = ts.defineClass(baseBacking);
		CppRttiFeeder feeder = new CppRttiFeeder(ts);

		CppClass derived = feeder.feedClass("Derived", List.of(new BaseSpec("Base", 0, false, true)));

		assertSame("the recovered derived class must be reused", existingDerived, derived);
		assertSame("the recovered base class must be reused", existingBase,
			derived.getBaseClasses().get(0).getBaseClass());
		assertEquals("derived backing layout must be untouched", 3,
			derivedBacking.getNumDefinedComponents());
		assertEquals("base backing layout must be untouched", 2,
			baseBacking.getNumDefinedComponents());
	}

	@Test
	public void testFeedingDoesNotInventMethods() {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);

		// RTTI yields the inheritance graph only; methods/vtable are #37-6 (guardrail)
		CppClass derived = feeder.feedClass("Derived", List.of(new BaseSpec("Base", 0, false, true)));
		assertTrue(derived.getMethods().isEmpty());
		assertTrue(ts.getCppClass("Base").getMethods().isEmpty());
	}

	@Test
	public void testOffsetIsNarrowedFromLong() {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);

		CppClass derived = feeder.feedClass("Derived", List.of(new BaseSpec("Base", 24L, false, true)));
		assertEquals(24, derived.getBaseClasses().get(0).getOffset());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeedClassRejectsNullDerivedName() {
		new CppRttiFeeder(new CppTypeSystem()).feedClass(null, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeedClassRejectsBlankDerivedName() {
		new CppRttiFeeder(new CppTypeSystem()).feedClass("  ", List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeedClassRejectsNullBaseList() {
		new CppRttiFeeder(new CppTypeSystem()).feedClass("Derived", null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBaseSpecRejectsNullBaseName() {
		new BaseSpec(null, 0, false, true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBaseSpecRejectsBlankBaseName() {
		new BaseSpec(" ", 0, false, true);
	}
}
