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
package ghidra.app.decompiler;

import static ghidra.program.model.pcode.AttributeId.*;
import static ghidra.program.model.pcode.ElementId.*;
import static org.junit.Assert.*;

import java.io.*;

import org.junit.Before;
import org.junit.Test;

import ghidra.program.model.address.*;
import ghidra.program.model.pcode.*;

/**
 * Decode-side unit tests for the Rec 35 {@code #35-5a-2} partial-result wire
 * contract: a {@code <budgetexhausted>} child of {@code <doc>} (emitted by the
 * worker when {@code OptionDecompileBudget} truncates analysis) must surface as
 * {@link DecompileResults#isPartial()} / {@link
 * DecompileResults#getBudgetExhaustedPass()} rather than being scraped from the
 * warning-header text (DD-0010). The element name and id (291) are fixed to
 * match the C++ {@code ELEM_BUDGETEXHAUSTED} (ghidra_process.cc); these tests
 * pin the Java decode end of that wire.
 *
 * <p>A budget-exhausted-only document never enters the {@code <function>}
 * branch of {@link DecompileResults} decode, so the {@code Function} / language
 * / spec collaborators are never consulted &mdash; the results object decodes
 * with null collaborators. Pure marshal round-trip; no decompiler process, so
 * this lives in the fast {@code test} sourceset and runs in CI's {@code gradle
 * test}. End-to-end truncation under a real tiny budget is exercised
 * separately.
 */
public class DecompileResultsBudgetDecodeTest {

	private AddressFactory addrFactory;

	@Before
	public void setUp() {
		AddressSpace[] spaces = new AddressSpace[4];
		spaces[0] = new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 2);
		spaces[1] = new GenericAddressSpace("register", 32, AddressSpace.TYPE_REGISTER, 3);
		spaces[2] = new GenericAddressSpace("const", 32, AddressSpace.TYPE_CONSTANT, 0);
		spaces[3] = new GenericAddressSpace("unique", 32, AddressSpace.TYPE_UNIQUE, 1);
		addrFactory = new DefaultAddressFactory(spaces);
	}

	/** Build a PackedDecode over the bytes the given encoder produced. */
	private Decoder decoderOf(PatchPackedEncode encoder) throws IOException, DecoderException {
		ByteArrayOutputStream outStream = new ByteArrayOutputStream();
		encoder.writeTo(outStream);
		ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
		PackedDecode decoder = new PackedDecode(addrFactory);
		decoder.open(1 << 20, "DecompileResultsBudgetDecodeTest");
		decoder.ingestStreamToNextTerminator(inStream);
		decoder.endIngest();
		return decoder;
	}

	private DecompileResults resultsOf(Decoder decoder) {
		// A budgetexhausted-only doc never reaches the <function> branch, so the
		// Function/Language/CompilerSpec/PcodeDataTypeManager collaborators stay
		// untouched and null is safe here.
		return new DecompileResults(null, null, null, null, "", decoder,
			DecompileProcess.DisposeState.NOT_DISPOSED);
	}

	@Test
	public void testPartialWhenBudgetExhaustedPresent() throws IOException, DecoderException {
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		encoder.openElement(ELEM_DOC);
		encoder.openElement(ELEM_BUDGETEXHAUSTED);
		encoder.writeString(ATTRIB_NAME, "data_flow");
		encoder.closeElement(ELEM_BUDGETEXHAUSTED);
		encoder.closeElement(ELEM_DOC);

		DecompileResults results = resultsOf(decoderOf(encoder));
		assertTrue("a <budgetexhausted> element must mark the result partial",
			results.isPartial());
		assertEquals("the exhausted pass name must round-trip", "data_flow",
			results.getBudgetExhaustedPass());
		// The new element must be recognized, not rejected as an unknown tag.
		assertTrue("decoding a known partial-result marker must not set an error",
			results.isValid());
	}

	@Test
	public void testNotPartialWhenAbsent() throws IOException, DecoderException {
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		encoder.openElement(ELEM_DOC);
		encoder.closeElement(ELEM_DOC);

		DecompileResults results = resultsOf(decoderOf(encoder));
		assertFalse("a document without <budgetexhausted> must not be partial",
			results.isPartial());
		assertNull("no exhausted pass when the result is complete",
			results.getBudgetExhaustedPass());
	}
}
