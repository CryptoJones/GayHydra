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
import ghidra.program.model.address.Address;

/**
 * End-to-end coverage for the Rec 37 {@code #37-5-3} {@link CppMsvcRttiDriver} (DD-0041): it bridges the
 * {@link CppMsvcRttiDecoder} MSVC RTTI decode to the {@link CppRttiFeeder}, so these tests assert the whole
 * MSVC RTTI &rarr; {@link CppTypeSystem} inheritance graph the decoder/feeder unit tests each only cover a
 * half of.
 *
 * <p>As in {@link CppMsvcRttiDecoderTest}, the complete-flow fixture's RTTI1 descriptors all carry
 * {@code numContainedBases == 0}, so each test writes the real subtree sizes in to express a graph. The
 * driver is exercised from both entry forms: the {@code RTTICompleteObjectLocator} (RTTI4, what a vftable
 * points at) and the {@code RTTIClassHierarchyDescriptor} (RTTI3) it references.
 */
public class CppMsvcRttiDriverTest extends AbstractCppRttiTest {

	// Complete-flow fixture structure addresses (Base <- Shape <- Circle).
	private static final long BASE_RTTI4 = 0x01003340L;
	private static final long SHAPE_RTTI4 = 0x01003354L;
	private static final long CIRCLE_RTTI4 = 0x01003240L;
	private static final long CIRCLE_RTTI3 = 0x01003268L;
	private static final long BASE_RTTI1 = 0x010033a8L;
	private static final long SHAPE_RTTI1 = 0x010033c4L;
	private static final long CIRCLE_RTTI1 = 0x010032a8L;

	@Test
	public void testFeedsSingleInheritanceGraphEndToEnd() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		// True single-inheritance chain Base <- Shape <- Circle.
		setNumContainedBases(builder, BASE_RTTI1, 0);
		setNumContainedBases(builder, SHAPE_RTTI1, 1);
		setNumContainedBases(builder, CIRCLE_RTTI1, 2);
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(typeSystem);

		assertEquals("Base", feedFromRtti4(program, BASE_RTTI4, feeder).getName());
		assertEquals("Shape", feedFromRtti4(program, SHAPE_RTTI4, feeder).getName());
		assertEquals("Circle", feedFromRtti4(program, CIRCLE_RTTI4, feeder).getName());

		// Base: no edges.
		assertTrue(typeSystem.getCppClass("Base").getBaseClasses().isEmpty());

		// Shape: one edge to Base.
		assertSingleBaseEdge(typeSystem, "Shape", "Base");

		// Circle: one DIRECT edge to Shape; Base is transitive and not a direct edge of Circle.
		assertSingleBaseEdge(typeSystem, "Circle", "Shape");
	}

	@Test
	public void testFeedsFromClassHierarchyDescriptor() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		setNumContainedBases(builder, BASE_RTTI1, 0);
		setNumContainedBases(builder, SHAPE_RTTI1, 1);
		setNumContainedBases(builder, CIRCLE_RTTI1, 2);
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(typeSystem);

		Address rtti3 = addr(program, CIRCLE_RTTI3);
		CppClass circle =
			CppMsvcRttiDriver.feedClass(new Rtti3Model(program, rtti3, defaultValidationOptions),
				feeder);

		assertEquals("Circle", circle.getName());
		assertSingleBaseEdge(typeSystem, "Circle", "Shape");
	}

	@Test
	public void testFeedsMultipleInheritanceGraph() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		// Reinterpret as multiple inheritance Circle : Shape, Base (Shape and Base unrelated).
		setNumContainedBases(builder, BASE_RTTI1, 0);
		setNumContainedBases(builder, SHAPE_RTTI1, 0);
		setNumContainedBases(builder, CIRCLE_RTTI1, 2);
		ProgramDB program = builder.getProgram();

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(typeSystem);

		CppClass circle = feedFromRtti4(program, CIRCLE_RTTI4, feeder);
		assertEquals("Circle", circle.getName());

		List<CppBaseClass> bases = circle.getBaseClasses();
		assertEquals(2, bases.size());
		assertSame(typeSystem.getCppClass("Shape"), bases.get(0).getBaseClass());
		assertSame(typeSystem.getCppClass("Base"), bases.get(1).getBaseClass());
	}

	@Test
	public void testDeclinesNullModels() {
		CppRttiFeeder feeder = new CppRttiFeeder(new CppTypeSystem());
		assertNull("a null complete object locator must feed nothing",
			CppMsvcRttiDriver.feedClass((Rtti4Model) null, feeder));
		assertNull("a null class hierarchy descriptor must feed nothing",
			CppMsvcRttiDriver.feedClass((Rtti3Model) null, feeder));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullFeeder() {
		CppMsvcRttiDriver.feedClass((Rtti3Model) null, null);
	}

	private CppClass feedFromRtti4(ProgramDB program, long rtti4Address, CppRttiFeeder feeder) {
		return CppMsvcRttiDriver.feedClass(
			new Rtti4Model(program, addr(program, rtti4Address), defaultValidationOptions), feeder);
	}

	private static Address addr(ProgramDB program, long offset) {
		return program.getAddressFactory().getDefaultAddressSpace().getAddress(offset);
	}
}
