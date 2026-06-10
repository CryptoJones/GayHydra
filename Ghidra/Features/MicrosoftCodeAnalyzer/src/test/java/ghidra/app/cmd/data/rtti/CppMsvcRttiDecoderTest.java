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

import ghidra.app.cmd.data.rtti.CppMsvcRttiDecoder.DecodedClass;
import ghidra.app.util.cpp.CppRttiFeeder.BaseSpec;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;

/**
 * Coverage for the Rec 37 MSVC RTTI decoder, exercised against the {@link AbstractRttiTest} MSVC RTTI
 * fixtures.
 *
 * <p>The {@code #37-5-1} {@link CppMsvcRttiDecoder#decodeBase(Rtti1Model)} per-descriptor decoder
 * (DD-0039): the happy path decodes the real {@code Base}/{@code Shape}/{@code Circle} base class
 * descriptors the complete-flow fixture lays down (all non-virtual, public, offset 0). Hand-built variants
 * then ground the three decode dimensions the complete-flow fixture holds constant: a non-zero
 * {@code mdisp} (offset comes from the descriptor, not a constant), a {@code BCD_PRIVORPROTBASE} attribute
 * bit ({@code isPublic} false), and a virtual base ({@code pdisp != -1}, declined because its offset needs
 * the runtime vbtable).
 *
 * <p>The {@code #37-5-2} {@link CppMsvcRttiDecoder#decodeClass(Rtti3Model)} class decoder (DD-0040): the
 * complete-flow RTTI1 descriptors all carry {@code numContainedBases == 0}, so each test writes the real
 * subtree sizes into the shared descriptors to express a graph. Single inheritance grounds that a
 * transitive base ({@code Base} under {@code Shape} under {@code Circle}) is excluded from
 * {@code Circle}'s direct bases; a multiple-inheritance reinterpretation of the same array grounds that two
 * unrelated direct bases are both emitted in array order.
 */
public class CppMsvcRttiDecoderTest extends AbstractCppRttiTest {

	// Base's real RTTI0 (".?AVBase@@" -> "Base") and RTTI3, laid down by setupRtti32Base, that the
	// hand-built variant descriptors point at so they validate.
	private static final String BASE_RTTI0 = "0x01005200";
	private static final String BASE_RTTI3 = "0x01003368";
	private static final long VARIANT_RTTI1 = 0x01003400L;

	@Test
	public void testDecodesRealNonVirtualPublicBases() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		ProgramDB program = builder.getProgram();

		assertEquals(new BaseSpec("Base", 0, false, true), decode(program, 0x010033a8L));
		assertEquals(new BaseSpec("Shape", 0, false, true), decode(program, 0x010033c4L));
		assertEquals(new BaseSpec("Circle", 0, false, true), decode(program, 0x010032a8L));
	}

	@Test
	public void testOffsetComesFromMdisp() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32Base(builder);
		// non-virtual (pdisp -1), public (0x40), but a non-zero member displacement
		setupRtti1_32(builder, VARIANT_RTTI1, BASE_RTTI0, 0, 8, 0xffffffff, 0, 0x40, BASE_RTTI3);
		ProgramDB program = builder.getProgram();

		assertEquals(new BaseSpec("Base", 8, false, true), decode(program, VARIANT_RTTI1));
	}

	@Test
	public void testPrivateOrProtectedBaseIsNotPublic() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32Base(builder);
		// non-virtual public-flow but with BCD_PRIVORPROTBASE (0x04) set in attributes (0x40 | 0x04)
		setupRtti1_32(builder, VARIANT_RTTI1, BASE_RTTI0, 0, 0, 0xffffffff, 0, 0x44, BASE_RTTI3);
		ProgramDB program = builder.getProgram();

		assertEquals(new BaseSpec("Base", 0, false, false), decode(program, VARIANT_RTTI1));
	}

	@Test
	public void testDeclinesVirtualBase() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32Base(builder);
		// pdisp != -1 marks a virtual base, whose offset needs the runtime vbtable: declined here
		setupRtti1_32(builder, VARIANT_RTTI1, BASE_RTTI0, 0, 0, 0, 0, 0x40, BASE_RTTI3);
		ProgramDB program = builder.getProgram();

		assertNull("a virtual base must be declined", decode(program, VARIANT_RTTI1));
	}

	@Test
	public void testDeclinesNull() {
		assertNull("a null descriptor must yield no fact", CppMsvcRttiDecoder.decodeBase(null));
	}

	// --- #37-5-2 decodeClass(Rtti3Model): derived name + DIRECT bases (transitive bases excluded) ---

	// The complete-flow fixture's RTTI1 descriptors all carry numContainedBases == 0 (they exist to
	// test struct parsing, not hierarchy shape), so the contained-bases subtree sizes must be written
	// in to express a real inheritance graph. These are the three shared RTTI1 descriptor addresses.
	private static final long BASE_RTTI1 = 0x010033a8L;
	private static final long SHAPE_RTTI1 = 0x010033c4L;
	private static final long CIRCLE_RTTI1 = 0x010032a8L;

	@Test
	public void testDecodesSingleInheritanceDirectBasesOnly() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		// Make the fixture a true single-inheritance chain Base <- Shape <- Circle: each class's
		// descriptor states the size of its own base subtree (Base: none, Shape: Base, Circle: Shape+Base).
		setNumContainedBases(builder, BASE_RTTI1, 0);
		setNumContainedBases(builder, SHAPE_RTTI1, 1);
		setNumContainedBases(builder, CIRCLE_RTTI1, 2);
		ProgramDB program = builder.getProgram();

		DecodedClass base = decodeClass(program, 0x01003368L);
		assertEquals("Base", base.derivedName());
		assertEquals(List.of(), base.directBases());

		DecodedClass shape = decodeClass(program, 0x01003378L);
		assertEquals("Shape", shape.derivedName());
		assertEquals(List.of(new BaseSpec("Base", 0, false, true)), shape.directBases());

		DecodedClass circle = decodeClass(program, 0x01003268L);
		assertEquals("Circle", circle.derivedName());
		// Shape is the only DIRECT base; Base is transitive (reached through Shape) and excluded.
		assertEquals(List.of(new BaseSpec("Shape", 0, false, true)), circle.directBases());
	}

	@Test
	public void testDecodesMultipleDirectBases() throws Exception {
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		// Reinterpret the same RTTI2 array as multiple inheritance Circle : Shape, Base with Shape and
		// Base unrelated: Circle contains two bases, each of which contains none.
		setNumContainedBases(builder, BASE_RTTI1, 0);
		setNumContainedBases(builder, SHAPE_RTTI1, 0);
		setNumContainedBases(builder, CIRCLE_RTTI1, 2);
		ProgramDB program = builder.getProgram();

		DecodedClass circle = decodeClass(program, 0x01003268L);
		assertEquals("Circle", circle.derivedName());
		assertEquals(
			List.of(new BaseSpec("Shape", 0, false, true), new BaseSpec("Base", 0, false, true)),
			circle.directBases());
	}

	@Test
	public void testDecodeClassDeclinesNull() {
		assertNull("a null hierarchy descriptor must yield no class",
			CppMsvcRttiDecoder.decodeClass(null));
	}

	private BaseSpec decode(ProgramDB program, long rtti1Address) {
		Address address = program.getAddressFactory().getDefaultAddressSpace().getAddress(rtti1Address);
		return CppMsvcRttiDecoder.decodeBase(new Rtti1Model(program, address, defaultValidationOptions));
	}

	@Test
	public void testDecodesTemplateClassName() throws Exception {
		// Guard (#37-10u): an MSVC template class's descriptor name demangles with its arguments
		// (.?AV?$MyVec@H@@ -> MyVec<int>) and flows through the decode unchanged -- the form
		// CppTypeSystem keys by, so template classes need no separate handling anywhere downstream.
		ProgramBuilder builder = build32BitX86();
		setupRtti32CompleteFlow(builder);
		setNumContainedBases(builder, BASE_RTTI1, 0);
		// Overwrite Base's descriptor name (RTTI0 + 8) with the template mangling.
		builder.setBytes("0x01005208", ".?AV?$MyVec@H@@\0".getBytes());
		ProgramDB program = builder.getProgram();

		DecodedClass templated = decodeClass(program, 0x01003368L);
		assertEquals("the demangled template name must flow through the decode", "MyVec<int>",
			templated.derivedName());
		assertEquals(List.of(), templated.directBases());
	}

	private DecodedClass decodeClass(ProgramDB program, long rtti3Address) {
		Address address = program.getAddressFactory().getDefaultAddressSpace().getAddress(rtti3Address);
		return CppMsvcRttiDecoder.decodeClass(new Rtti3Model(program, address, defaultValidationOptions));
	}

}
