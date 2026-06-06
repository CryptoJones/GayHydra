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

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;

/**
 * Headless unit tests for the {@code CppVtableReconciler} (Rec 37 {@code #37-6c}, grounded by
 * DD-0015). These build the pre-reconciled state directly through the model setters — declared
 * {@link CppMethod}s on a {@link CppClass} plus fresh virtual slot methods in a {@link CppVTable} —
 * emulating the disjoint two-list shape a class has after the {@code #37-3} and {@code #37-6}
 * feeders run, with no {@code Program}, no {@code DataTypeManager}, and no demangler.
 */
public class CppVtableReconcilerTest extends AbstractGenericTest {

	public CppVtableReconcilerTest() {
		super();
	}

	private static Structure struct(String name) {
		return new StructureDataType(name, 0);
	}

	/** A declared method as the demangling feeder would attach it: not yet virtual. */
	private static CppMethod declared(String name) {
		return new CppMethod(name);
	}

	/** A fresh vtable slot method as the vtable feeder would build it: virtual. */
	private static CppMethod slot(String name, boolean pure) {
		CppMethod m = new CppMethod(name);
		m.setVirtual(true);
		m.setPureVirtual(pure);
		return m;
	}

	private static CppVTable vtableOf(CppMethod... slots) {
		CppVTable vt = new CppVTable();
		for (CppMethod s : slots) {
			vt.addSlot(s);
		}
		return vt;
	}

	@Test(expected = IllegalArgumentException.class)
	public void testReconcilerRejectsNullTypeSystem() {
		new CppVtableReconciler(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testReconcileRejectsNullClass() {
		new CppVtableReconciler(new CppTypeSystem()).reconcile((CppClass) null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testReconcileRejectsNullClassName() {
		new CppVtableReconciler(new CppTypeSystem()).reconcile((String) null);
	}

	@Test
	public void testReconcileUnknownNameIsNoOp() {
		// Must not throw; simply does nothing for a name the model has never seen.
		new CppVtableReconciler(new CppTypeSystem()).reconcile("NeverDefined");
	}

	@Test
	public void testUniqueMatchUnifiesOntoCanonicalDeclaredMethod() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass shape = ts.defineClass(struct("Shape"));
		CppMethod area = declared("area");
		area.setConst(true);
		CppCallingConvention thiscall = new CppCallingConvention("__thiscall", 0);
		area.setCallingConvention(thiscall);
		shape.addMethod(area);
		shape.setVtable(vtableOf(slot("area", false)));

		new CppVtableReconciler(ts).reconcile("Shape");

		// The declared method is the one that gets flipped virtual.
		assertTrue("matched declared method becomes virtual", area.isVirtual());
		assertFalse(area.isPureVirtual());
		// And it is the very object now occupying the slot (one method per function).
		assertSame("vtable slot must reference the canonical declared method",
			area, shape.getVtable().getSlot(0));
		// Canonical method keeps its richer demangled qualifiers.
		assertTrue("const qualifier survives", area.isConst());
		assertSame(thiscall, area.getCallingConvention());
		// Declared-method list is unchanged in size — nothing added or removed.
		assertEquals(1, shape.getMethods().size());
		assertSame(area, shape.getMethods().get(0));
	}

	@Test
	public void testPureVirtualFlagIsCopiedFromSlotOntoCanonical() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass base = ts.defineClass(struct("AbstractBase"));
		CppMethod render = declared("render");
		base.addMethod(render);
		base.setVtable(vtableOf(slot("render", true)));

		new CppVtableReconciler(ts).reconcile(base);

		assertTrue(render.isVirtual());
		assertTrue("pure-virtual flag must propagate from the slot", render.isPureVirtual());
		assertSame(render, base.getVtable().getSlot(0));
	}

	@Test
	public void testOverloadedDeclaredNameLeftUnreconciled() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass widget = ts.defineClass(struct("Widget"));
		CppMethod draw1 = declared("draw");
		CppMethod draw2 = declared("draw");
		widget.addMethod(draw1);
		widget.addMethod(draw2);
		CppMethod drawSlot = slot("draw", false);
		widget.setVtable(vtableOf(drawSlot));

		new CppVtableReconciler(ts).reconcile(widget);

		// Ambiguous: neither declared overload may be marked virtual.
		assertFalse(draw1.isVirtual());
		assertFalse(draw2.isVirtual());
		// And the slot keeps its own fresh method (still virtual), untouched.
		assertSame(drawSlot, widget.getVtable().getSlot(0));
		assertTrue(drawSlot.isVirtual());
	}

	@Test
	public void testOverloadedSlotNameLeftUnreconciled() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass widget = ts.defineClass(struct("Widget"));
		CppMethod resize = declared("resize");
		widget.addMethod(resize);
		CppMethod resizeSlot0 = slot("resize", false);
		CppMethod resizeSlot1 = slot("resize", false);
		widget.setVtable(vtableOf(resizeSlot0, resizeSlot1));

		new CppVtableReconciler(ts).reconcile(widget);

		// Two slots share the name: no sound 1:1 pairing, so the declared method stays non-virtual.
		assertFalse(resize.isVirtual());
		assertSame(resizeSlot0, widget.getVtable().getSlot(0));
		assertSame(resizeSlot1, widget.getVtable().getSlot(1));
	}

	@Test
	public void testSlotWithoutDeclaredCounterpartKeepsItsFreshMethod() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass c = ts.defineClass(struct("C"));
		CppMethod known = declared("known");
		c.addMethod(known);
		CppMethod knownSlot = slot("known", false);
		CppMethod orphanSlot = slot("orphanVirtual", false);
		c.setVtable(vtableOf(knownSlot, orphanSlot));

		new CppVtableReconciler(ts).reconcile(c);

		// The matched slot is unified; the orphan slot is left exactly as the feeder built it.
		assertSame(known, c.getVtable().getSlot(0));
		assertSame(orphanSlot, c.getVtable().getSlot(1));
		assertTrue("orphan slot stays virtual", orphanSlot.isVirtual());
	}

	@Test
	public void testDeclaredMethodWithoutSlotStaysNonVirtual() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass c = ts.defineClass(struct("C"));
		CppMethod virtualOne = declared("vmethod");
		CppMethod plainOne = declared("nonVirtualHelper");
		c.addMethod(virtualOne);
		c.addMethod(plainOne);
		c.setVtable(vtableOf(slot("vmethod", false)));

		new CppVtableReconciler(ts).reconcile(c);

		assertTrue(virtualOne.isVirtual());
		assertFalse("a declared method with no vtable slot stays non-virtual", plainOne.isVirtual());
	}

	@Test
	public void testClassWithNoVtableIsNoOp() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass c = ts.defineClass(struct("C"));
		CppMethod m = declared("ctor");
		c.addMethod(m);

		new CppVtableReconciler(ts).reconcile(c);

		assertNull(c.getVtable());
		assertFalse(m.isVirtual());
	}

	@Test
	public void testEmptyVtableIsNoOp() {
		CppTypeSystem ts = new CppTypeSystem();
		CppClass c = ts.defineClass(struct("C"));
		CppMethod m = declared("ctor");
		c.addMethod(m);
		c.setVtable(new CppVTable());

		new CppVtableReconciler(ts).reconcile(c);

		assertEquals(0, c.getVtable().getSlotCount());
		assertFalse(m.isVirtual());
	}

	@Test
	public void testWholeModelPassReconcilesEachClassAndIsIdempotent() {
		CppTypeSystem ts = new CppTypeSystem();

		CppClass shape = ts.defineClass(struct("Shape"));
		CppMethod area = declared("area");
		shape.addMethod(area);
		shape.setVtable(vtableOf(slot("area", false)));

		CppClass base = ts.defineClass(struct("Base"));
		CppMethod run = declared("run");
		base.addMethod(run);
		base.setVtable(vtableOf(slot("run", true)));

		CppVtableReconciler reconciler = new CppVtableReconciler(ts);
		reconciler.reconcileAll();

		assertTrue(area.isVirtual());
		assertSame(area, shape.getVtable().getSlot(0));
		assertTrue(run.isVirtual());
		assertTrue(run.isPureVirtual());
		assertSame(run, base.getVtable().getSlot(0));

		// Second pass must change nothing (slots already reference the canonical methods).
		reconciler.reconcileAll();
		assertSame(area, shape.getVtable().getSlot(0));
		assertSame(run, base.getVtable().getSlot(0));
		assertEquals(1, shape.getVtable().getSlotCount());
		assertEquals(1, base.getVtable().getSlotCount());
		assertEquals(1, shape.getMethods().size());
		assertEquals(1, base.getMethods().size());
	}

	@Test
	public void testSetSlotReplacesInPlaceWithoutChangingCount() {
		CppVTable vt = vtableOf(slot("a", false), slot("b", false));
		CppMethod replacement = declared("a");
		vt.setSlot(0, replacement);

		assertEquals(2, vt.getSlotCount());
		assertSame(replacement, vt.getSlot(0));
		assertEquals("b", vt.getSlot(1).getName());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testSetSlotRejectsNullMethod() {
		vtableOf(slot("a", false)).setSlot(0, null);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void testSetSlotRejectsOutOfRangeIndex() {
		vtableOf(slot("a", false)).setSlot(3, declared("x"));
	}
}
