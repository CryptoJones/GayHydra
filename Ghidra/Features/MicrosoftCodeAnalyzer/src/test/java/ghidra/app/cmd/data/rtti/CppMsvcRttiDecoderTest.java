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

import org.junit.Test;

import ghidra.app.util.cpp.CppRttiFeeder.BaseSpec;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;

/**
 * Coverage for the Rec 37 {@code #37-5-1} {@link CppMsvcRttiDecoder#decodeBase(Rtti1Model)} per-descriptor
 * MSVC RTTI base decoder (DD-0039), exercised against the {@link AbstractRttiTest} MSVC RTTI fixtures.
 *
 * <p>The happy path decodes the real {@code Base}/{@code Shape}/{@code Circle} base class descriptors the
 * complete-flow fixture lays down (all non-virtual, public, offset 0). Hand-built variants then ground the
 * three decode dimensions the complete-flow fixture holds constant: a non-zero {@code mdisp} (offset comes
 * from the descriptor, not a constant), a {@code BCD_PRIVORPROTBASE} attribute bit ({@code isPublic} false),
 * and a virtual base ({@code pdisp != -1}, declined because its offset needs the runtime vbtable).
 */
public class CppMsvcRttiDecoderTest extends AbstractRttiTest {

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

	private BaseSpec decode(ProgramDB program, long rtti1Address) {
		Address address = program.getAddressFactory().getDefaultAddressSpace().getAddress(rtti1Address);
		return CppMsvcRttiDecoder.decodeBase(new Rtti1Model(program, address, defaultValidationOptions));
	}
}
