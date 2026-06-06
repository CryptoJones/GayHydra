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
 * Headless unit tests for the {@code CppDecompilerHints} virtual-method-call renderer (Rec 37
 * {@code #37-7}, grounded by DD-0016). These build the resolved model state directly through the
 * model setters — a {@link CppClass} with a {@link CppVTable} whose slots already reference
 * name-resolved {@link CppMethod}s, the shape a class has after the {@code #37-6}/{@code #37-6c}
 * feeders run — with no {@code Program}, no {@code DataTypeManager}, and no decompiler.
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
}
