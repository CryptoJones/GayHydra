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

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;

/**
 * Headless unit tests for the {@code CppDecompilerHints} renderer: the virtual-method-call form
 * (Rec 37 {@code #37-7}, DD-0016), the up/down-cast form (Rec 37 {@code #37-8}, DD-0017), the
 * heap-construction form (Rec 37 {@code #37-9}, DD-0018), the explicit destructor-call form
 * (Rec 37 {@code #37-9c}, DD-0019), the array-construction form (Rec 37 {@code #37-9d}, DD-0020),
 * the placement-construction form (Rec 37 {@code #37-9e}, DD-0021), and the deallocation form
 * (Rec 37 {@code #37-9f}, DD-0022). These
 * build the resolved model state directly through the model setters — a {@link CppClass} with a
 * {@link CppVTable} whose slots already reference name-resolved {@link CppMethod}s, and
 * {@link CppBaseClass} inheritance edges as the {@code #37-4} feeder would record them — with no
 * {@code Program}, no {@code DataTypeManager}, and no decompiler.
 */
public class CppDecompilerHintsTest extends AbstractGenericTest {

	public CppDecompilerHintsTest() {
		super();
	}

	private static Structure struct(String name) {
		return new StructureDataType(name, 0);
	}

	/** A name-resolved vtable slot, as the reconciler leaves it post-{@code #37-6c}. */
	private static CppMethod slot(String name) {
		CppMethod m = new CppMethod(name);
		m.setVirtual(true);
		return m;
	}

	private static CppClass classWithVtable(String name, CppMethod... slots) {
		CppClass c = new CppClass(struct(name));
		CppVTable vt = new CppVTable();
		for (CppMethod s : slots) {
			vt.addSlot(s);
		}
		c.setVtable(vt);
		return c;
	}

	/** Adds a base-class edge to {@code derived} and returns {@code derived} for chaining. */
	private static CppClass withBase(CppClass derived, CppClass base, int offset, boolean virtual,
			boolean publicBase) {
		derived.addBaseClass(new CppBaseClass(base, offset, virtual, publicBase));
		return derived;
	}

	@Test
	public void testValidSlotRendersArrowCallWithArgsInOrder() {
		CppClass shape = classWithVtable("Shape", slot("draw"), slot("area"));
		String rendered = new CppDecompilerHints().renderVirtualCall(
			shape, 1, "shape", true, List.of("x", "y"));
		assertEquals("shape->area(x, y)", rendered);
	}

	@Test
	public void testValueReceiverRendersDotAccess() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		String rendered = new CppDecompilerHints().renderVirtualCall(
			shape, 0, "shape", false, List.of());
		assertEquals("shape.area()", rendered);
	}

	@Test
	public void testNoArgCallRendersEmptyParens() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		String rendered = new CppDecompilerHints().renderVirtualCall(
			shape, 0, "this", true, List.of());
		assertEquals("this->area()", rendered);
	}

	@Test
	public void testOutOfRangeSlotIndexFallsBackToNeutralVtableForm() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		String rendered = new CppDecompilerHints().renderVirtualCall(
			shape, 5, "shape", true, List.of("z"));
		assertEquals("shape->vtable[5](z)", rendered);
	}

	@Test
	public void testNegativeSlotIndexFallsBackToNeutralVtableForm() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		String rendered = new CppDecompilerHints().renderVirtualCall(
			shape, -1, "shape", true, List.of());
		assertEquals("shape->vtable[-1]()", rendered);
	}

	@Test
	public void testClassWithoutVtableFallsBackToNeutralForm() {
		CppClass noVtable = new CppClass(struct("Plain"));
		String rendered = new CppDecompilerHints().renderVirtualCall(
			noVtable, 0, "p", true, List.of());
		assertEquals("p->vtable[0]()", rendered);
	}

	@Test
	public void testBlankSlotNameFallsBackToNeutralForm() {
		CppClass shape = classWithVtable("Shape", slot("   "));
		String rendered = new CppDecompilerHints().renderVirtualCall(
			shape, 0, "shape", true, List.of("a"));
		assertEquals("shape->vtable[0](a)", rendered);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullClass() {
		new CppDecompilerHints().renderVirtualCall(null, 0, "shape", true, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullReceiver() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		new CppDecompilerHints().renderVirtualCall(shape, 0, null, true, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsBlankReceiver() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		new CppDecompilerHints().renderVirtualCall(shape, 0, "  ", true, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullArgumentList() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		new CppDecompilerHints().renderVirtualCall(shape, 0, "shape", true, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullArgumentElement() {
		CppClass shape = classWithVtable("Shape", slot("area"));
		new CppDecompilerHints().renderVirtualCall(
			shape, 0, "shape", true, Arrays.asList("a", null));
	}

	@Test
	public void testRendererIsStatelessAcrossCalls() {
		CppDecompilerHints hints = new CppDecompilerHints();
		CppClass shape = classWithVtable("Shape", slot("draw"), slot("area"));

		// Interleave a fallback render between two named renders; each result depends only on its
		// own inputs, so reusing one renderer instance must not let calls bleed into one another.
		String first = hints.renderVirtualCall(shape, 0, "s", true, List.of("p"));
		String fallback = hints.renderVirtualCall(shape, 9, "s", true, List.of("p"));
		String second = hints.renderVirtualCall(shape, 1, "s", false, List.of());

		assertEquals("s->draw(p)", first);
		assertEquals("s->vtable[9](p)", fallback);
		assertEquals("s.area()", second);
		assertEquals("s->draw(p)", hints.renderVirtualCall(shape, 0, "s", true, List.of("p")));
	}

	// ----- up/down-cast renderer (#37-8) -----

	@Test
	public void testUpcastAcrossNonVirtualBaseRendersStaticCastToBase() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		String rendered = new CppDecompilerHints().renderUpcast(derived, 8, "d");
		assertEquals("static_cast<Base*>(d)", rendered);
	}

	@Test
	public void testDowncastAcrossNonVirtualBaseRendersStaticCastToDerived() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		String rendered = new CppDecompilerHints().renderDowncast(derived, 8, "b");
		assertEquals("static_cast<Derived*>(b)", rendered);
	}

	@Test
	public void testPrimaryBaseAtOffsetZeroRendersStaticCast() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 0, false, true);
		assertEquals("static_cast<Base*>(d)", new CppDecompilerHints().renderUpcast(derived, 0, "d"));
	}

	@Test
	public void testNonPublicBaseStillRendersStaticCast() {
		// Access does not change the pointer adjustment; the hint is advisory, not a compilability
		// claim, so a private base renders the same static_cast as a public one.
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Secret")), 4, false, false);
		assertEquals("static_cast<Secret*>(d)", new CppDecompilerHints().renderUpcast(derived, 4, "d"));
	}

	@Test
	public void testUpcastNeverRendersDynamicCast() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		String rendered = new CppDecompilerHints().renderUpcast(derived, 8, "d");
		assertTrue("a recovered constant offset is a static_cast", rendered.startsWith("static_cast<"));
		assertFalse("must never assert an RTTI-checked conversion", rendered.contains("dynamic_cast"));
	}

	@Test
	public void testVirtualBaseDeclinesStaticCastAndFallsBackUpcast() {
		// A virtual base's offset is dynamic, so a constant static_cast would misrepresent it.
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("VBase")), 16, true, true);
		assertEquals("d + 16", new CppDecompilerHints().renderUpcast(derived, 16, "d"));
	}

	@Test
	public void testVirtualBaseDeclinesStaticCastAndFallsBackDowncast() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("VBase")), 16, true, true);
		assertEquals("b - 16", new CppDecompilerHints().renderDowncast(derived, 16, "b"));
	}

	@Test
	public void testOffsetMatchingNoEdgeFallsBackUpcast() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		// No base edge sits at offset 4: decline the cast, render the raw adjustment.
		assertEquals("d + 4", new CppDecompilerHints().renderUpcast(derived, 4, "d"));
	}

	@Test
	public void testOffsetMatchingNoEdgeFallsBackDowncast() {
		CppClass derived = new CppClass(struct("Derived")); // no base edges at all
		assertEquals("b - 12", new CppDecompilerHints().renderDowncast(derived, 12, "b"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullDerivedClassUpcast() {
		new CppDecompilerHints().renderUpcast(null, 8, "d");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullSourceExprUpcast() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		new CppDecompilerHints().renderUpcast(derived, 8, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsBlankSourceExprDowncast() {
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		new CppDecompilerHints().renderDowncast(derived, 8, "   ");
	}

	@Test
	public void testCastAndVirtualCallShareOneStatelessInstance() {
		CppDecompilerHints hints = new CppDecompilerHints();
		CppClass shape = classWithVtable("Shape", slot("area"));
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);

		// Interleaving a cast render between two call renders on one instance must not let the
		// renders bleed into one another.
		assertEquals("s->area()", hints.renderVirtualCall(shape, 0, "s", true, List.of()));
		assertEquals("static_cast<Base*>(d)", hints.renderUpcast(derived, 8, "d"));
		assertEquals("s->area()", hints.renderVirtualCall(shape, 0, "s", true, List.of()));
	}

	// ----- heap-construction renderer (#37-9) -----

	@Test
	public void testConstructionWithArgsRendersNewExpressionInOrder() {
		CppClass widget = new CppClass(struct("Widget"));
		String rendered = new CppDecompilerHints().renderConstruction(widget, List.of("a", "b"));
		assertEquals("new Widget(a, b)", rendered);
	}

	@Test
	public void testZeroArgConstructionRendersEmptyParens() {
		CppClass widget = new CppClass(struct("Widget"));
		String rendered = new CppDecompilerHints().renderConstruction(widget, List.of());
		assertEquals("new Widget()", rendered);
	}

	@Test
	public void testTemplateClassConstructionRendersAngleBrackets() {
		// Guard (#37-10u): the renderer uses the class name verbatim, so a template class fed under
		// its demangled name (MyVec<int>) renders source-faithfully with no template-specific code.
		CppClass vec = new CppClass(struct("MyVec<int>"));
		assertEquals("new MyVec<int>(n)",
			new CppDecompilerHints().renderConstruction(vec, List.of("n")));
	}

	@Test
	public void testConstructionUsesClassNameNotVtableOrBases() {
		// The form needs only the class name; a vtable / base edges on the type do not change it.
		CppClass shape = classWithVtable("Shape", slot("area"));
		withBase(shape, new CppClass(struct("Base")), 8, false, true);
		assertEquals("new Shape(x)", new CppDecompilerHints().renderConstruction(shape, List.of("x")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullConstructedType() {
		new CppDecompilerHints().renderConstruction(null, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullConstructionArgumentList() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderConstruction(widget, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullConstructionArgumentElement() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderConstruction(widget, Arrays.asList("a", null));
	}

	@Test
	public void testConstructionSharesOneStatelessInstanceWithCallAndCast() {
		CppDecompilerHints hints = new CppDecompilerHints();
		CppClass shape = classWithVtable("Shape", slot("area"));
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		CppClass widget = new CppClass(struct("Widget"));

		// Interleaving a construction render among call and cast renders on one instance must not let
		// the renders bleed into one another.
		assertEquals("s->area()", hints.renderVirtualCall(shape, 0, "s", true, List.of()));
		assertEquals("new Widget(a, b)", hints.renderConstruction(widget, List.of("a", "b")));
		assertEquals("static_cast<Base*>(d)", hints.renderUpcast(derived, 8, "d"));
		assertEquals("new Widget()", hints.renderConstruction(widget, List.of()));
	}

	// ----- explicit destructor-call renderer (#37-9c) -----

	@Test
	public void testPointerReceiverRendersArrowTildeDestructor() {
		CppClass widget = new CppClass(struct("Widget"));
		String rendered = new CppDecompilerHints().renderDestructorCall(widget, "p", true);
		assertEquals("p->~Widget()", rendered);
	}

	@Test
	public void testValueReceiverRendersDotTildeDestructor() {
		CppClass widget = new CppClass(struct("Widget"));
		String rendered = new CppDecompilerHints().renderDestructorCall(widget, "w", false);
		assertEquals("w.~Widget()", rendered);
	}

	@Test
	public void testDestructorNameComesFromClassNotVtableSlot() {
		// A vtable / base edges on the type do not change the explicit destructor rendering: the name
		// is "~" + getName(), not a slot lookup (virtual-dispatch destruction is the #37-7 form).
		CppClass shape = classWithVtable("Shape", slot("area"), slot("~Other"));
		withBase(shape, new CppClass(struct("Base")), 8, false, true);
		assertEquals("s->~Shape()", new CppDecompilerHints().renderDestructorCall(shape, "s", true));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullDestructedType() {
		new CppDecompilerHints().renderDestructorCall(null, "p", true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullDestructorReceiver() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderDestructorCall(widget, null, true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsBlankDestructorReceiver() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderDestructorCall(widget, "  ", false);
	}

	@Test
	public void testDestructorSharesOneStatelessInstanceWithCallCastAndConstruction() {
		CppDecompilerHints hints = new CppDecompilerHints();
		CppClass shape = classWithVtable("Shape", slot("area"));
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		CppClass widget = new CppClass(struct("Widget"));

		// Interleaving a destructor render among the call, cast, and construction renders on one
		// instance must not let the renders bleed into one another.
		assertEquals("s->area()", hints.renderVirtualCall(shape, 0, "s", true, List.of()));
		assertEquals("new Widget(a)", hints.renderConstruction(widget, List.of("a")));
		assertEquals("p->~Widget()", hints.renderDestructorCall(widget, "p", true));
		assertEquals("static_cast<Base*>(d)", hints.renderUpcast(derived, 8, "d"));
		assertEquals("w.~Widget()", hints.renderDestructorCall(widget, "w", false));
	}

	// ----- array-construction renderer (#37-9d) -----

	@Test
	public void testArrayConstructionRendersNewBracketCount() {
		CppClass widget = new CppClass(struct("Widget"));
		String rendered = new CppDecompilerHints().renderArrayConstruction(widget, "n");
		assertEquals("new Widget[n]", rendered);
	}

	@Test
	public void testArrayConstructionPreservesCountExpressionVerbatim() {
		// The count is an opaque expression the recognition pass recovers; it is emitted verbatim.
		CppClass widget = new CppClass(struct("Widget"));
		assertEquals("new Widget[count + 1]",
			new CppDecompilerHints().renderArrayConstruction(widget, "count + 1"));
	}

	@Test
	public void testArrayConstructionUsesClassNameNotVtableOrBases() {
		// The form needs only the class name; a vtable / base edges on the type do not change it, and
		// there is no per-element constructor argument list (array new[] default-initializes elements).
		CppClass shape = classWithVtable("Shape", slot("area"));
		withBase(shape, new CppClass(struct("Base")), 8, false, true);
		assertEquals("new Shape[k]", new CppDecompilerHints().renderArrayConstruction(shape, "k"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullArrayConstructedType() {
		new CppDecompilerHints().renderArrayConstruction(null, "n");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullArrayCountExpr() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderArrayConstruction(widget, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsBlankArrayCountExpr() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderArrayConstruction(widget, "  ");
	}

	@Test
	public void testArrayConstructionSharesOneStatelessInstanceWithOtherForms() {
		CppDecompilerHints hints = new CppDecompilerHints();
		CppClass shape = classWithVtable("Shape", slot("area"));
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		CppClass widget = new CppClass(struct("Widget"));

		// Interleaving an array-construction render among the call, cast, construction, and destructor
		// renders on one instance must not let the renders bleed into one another.
		assertEquals("s->area()", hints.renderVirtualCall(shape, 0, "s", true, List.of()));
		assertEquals("new Widget(a)", hints.renderConstruction(widget, List.of("a")));
		assertEquals("new Widget[n]", hints.renderArrayConstruction(widget, "n"));
		assertEquals("static_cast<Base*>(d)", hints.renderUpcast(derived, 8, "d"));
		assertEquals("p->~Widget()", hints.renderDestructorCall(widget, "p", true));
	}

	// ----- placement-construction renderer (#37-9e) -----

	@Test
	public void testPlacementConstructionWithArgsRendersBracketedTargetAndArgs() {
		CppClass widget = new CppClass(struct("Widget"));
		String rendered =
			new CppDecompilerHints().renderPlacementConstruction(widget, "buf", List.of("a", "b"));
		assertEquals("new (buf) Widget(a, b)", rendered);
	}

	@Test
	public void testZeroArgPlacementConstructionRendersEmptyCtorParens() {
		CppClass widget = new CppClass(struct("Widget"));
		String rendered =
			new CppDecompilerHints().renderPlacementConstruction(widget, "buf", List.of());
		assertEquals("new (buf) Widget()", rendered);
	}

	@Test
	public void testPlacementConstructionUsesClassNameNotVtableOrBases() {
		// The form needs only the class name; a vtable / base edges on the type do not change it.
		CppClass shape = classWithVtable("Shape", slot("area"));
		withBase(shape, new CppClass(struct("Base")), 8, false, true);
		assertEquals("new (p) Shape(x)",
			new CppDecompilerHints().renderPlacementConstruction(shape, "p", List.of("x")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullPlacementConstructedType() {
		new CppDecompilerHints().renderPlacementConstruction(null, "buf", List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullPlacementExpr() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderPlacementConstruction(widget, null, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsBlankPlacementExpr() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderPlacementConstruction(widget, "  ", List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullPlacementArgumentList() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderPlacementConstruction(widget, "buf", null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullPlacementArgumentElement() {
		CppClass widget = new CppClass(struct("Widget"));
		new CppDecompilerHints().renderPlacementConstruction(widget, "buf", Arrays.asList("a", null));
	}

	@Test
	public void testPlacementConstructionSharesOneStatelessInstanceWithOtherForms() {
		CppDecompilerHints hints = new CppDecompilerHints();
		CppClass shape = classWithVtable("Shape", slot("area"));
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		CppClass widget = new CppClass(struct("Widget"));

		// Interleaving a placement-construction render among the other five forms on one instance must
		// not let the renders bleed into one another.
		assertEquals("s->area()", hints.renderVirtualCall(shape, 0, "s", true, List.of()));
		assertEquals("new Widget(a)", hints.renderConstruction(widget, List.of("a")));
		assertEquals("new (buf) Widget(a, b)",
			hints.renderPlacementConstruction(widget, "buf", List.of("a", "b")));
		assertEquals("new Widget[n]", hints.renderArrayConstruction(widget, "n"));
		assertEquals("static_cast<Base*>(d)", hints.renderUpcast(derived, 8, "d"));
		assertEquals("w.~Widget()", hints.renderDestructorCall(widget, "w", false));
	}

	// ----- deallocation renderer (#37-9f) -----

	@Test
	public void testScalarDeleteRendersDeleteReceiver() {
		String rendered = new CppDecompilerHints().renderDelete("p", false);
		assertEquals("delete p", rendered);
	}

	@Test
	public void testArrayDeleteRendersDeleteBracketReceiver() {
		String rendered = new CppDecompilerHints().renderDelete("p", true);
		assertEquals("delete[] p", rendered);
	}

	@Test
	public void testDeletePreservesReceiverExpressionVerbatim() {
		// The receiver is an opaque expression the recognition pass recovers; it is emitted verbatim,
		// and delete reads no class name (C++ delete infers the type from the pointer).
		assertEquals("delete this->buf", new CppDecompilerHints().renderDelete("this->buf", false));
		assertEquals("delete[] arr + 1", new CppDecompilerHints().renderDelete("arr + 1", true));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullDeleteReceiver() {
		new CppDecompilerHints().renderDelete(null, false);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsBlankDeleteReceiver() {
		new CppDecompilerHints().renderDelete("  ", true);
	}

	@Test
	public void testDeleteSharesOneStatelessInstanceWithOtherForms() {
		CppDecompilerHints hints = new CppDecompilerHints();
		CppClass shape = classWithVtable("Shape", slot("area"));
		CppClass derived = withBase(new CppClass(struct("Derived")),
			new CppClass(struct("Base")), 8, false, true);
		CppClass widget = new CppClass(struct("Widget"));

		// Interleaving a deallocation render among all six prior forms on one instance must not let the
		// renders bleed into one another; delete reads no model fact, so it depends only on its operand.
		assertEquals("s->area()", hints.renderVirtualCall(shape, 0, "s", true, List.of()));
		assertEquals("new Widget(a)", hints.renderConstruction(widget, List.of("a")));
		assertEquals("new (buf) Widget()", hints.renderPlacementConstruction(widget, "buf", List.of()));
		assertEquals("new Widget[n]", hints.renderArrayConstruction(widget, "n"));
		assertEquals("delete p", hints.renderDelete("p", false));
		assertEquals("static_cast<Base*>(d)", hints.renderUpcast(derived, 8, "d"));
		assertEquals("p->~Widget()", hints.renderDestructorCall(widget, "p", true));
		assertEquals("delete[] arr", hints.renderDelete("arr", true));
	}
}
