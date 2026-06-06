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
package ghidra.app.plugin.core.decompile;

import static ghidra.program.model.pcode.AttributeId.*;
import static ghidra.program.model.pcode.ElementId.*;
import static org.junit.Assert.*;

import java.io.*;

import org.junit.Before;
import org.junit.Test;

import ghidra.app.decompiler.DecompileProcess;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.*;
import ghidra.program.model.pcode.*;

/**
 * Unit tests for the Rec 35 {@code #35-5b-1} partial-result banner text:
 * {@link OverlayMessagePainter#getPartialResultMessage(DecompileResults)} turns a
 * budget-truncated result ({@link DecompileResults#isPartial()}) into the overlay banner
 * naming the exhausted analysis pass, and the empty string for a complete result. The text is
 * derived from the structured marshalled signal (DD-0010), so this exercises the same decode
 * path the GUI sees without standing up a decompiler process or the Swing provider.
 *
 * <p>The {@link DecompileResults} fixtures are built the same way the decode-side test does
 * ({@code DecompileResultsBudgetDecodeTest}): a {@code <doc>} carrying (or omitting) a
 * {@code <budgetexhausted>} child, round-tripped through {@link PatchPackedEncode} /
 * {@link PackedDecode}. A budget-exhausted-only document never reaches the {@code <function>}
 * branch, so the {@code Function}/language/spec collaborators stay null. Pure marshal
 * round-trip; lives in the fast {@code test} sourceset.
 */
public class OverlayMessagePainterPartialTest {

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
		decoder.open(1 << 20, "OverlayMessagePainterPartialTest");
		decoder.ingestStreamToNextTerminator(inStream);
		decoder.endIngest();
		return decoder;
	}

	private DecompileResults resultsOf(Decoder decoder) {
		return new DecompileResults(null, null, null, null, "", decoder,
			DecompileProcess.DisposeState.NOT_DISPOSED);
	}

	/** A partial result whose {@code <budgetexhausted>} names the given pass. */
	private DecompileResults partialResults(String pass)
			throws IOException, DecoderException {
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		encoder.openElement(ELEM_DOC);
		encoder.openElement(ELEM_BUDGETEXHAUSTED);
		encoder.writeString(ATTRIB_NAME, pass);
		encoder.closeElement(ELEM_BUDGETEXHAUSTED);
		encoder.closeElement(ELEM_DOC);
		return resultsOf(decoderOf(encoder));
	}

	/** A complete result: a {@code <doc>} with no {@code <budgetexhausted>} child. */
	private DecompileResults completeResults() throws IOException, DecoderException {
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		encoder.openElement(ELEM_DOC);
		encoder.closeElement(ELEM_DOC);
		return resultsOf(decoderOf(encoder));
	}

	@Test
	public void testMessageNamesExhaustedPass() throws IOException, DecoderException {
		assertEquals("Decompilation partial - budget exhausted on data_flow",
			OverlayMessagePainter.getPartialResultMessage(partialResults("data_flow")));
	}

	@Test
	public void testMessageFallsBackWhenPassBlank() throws IOException, DecoderException {
		assertEquals("Decompilation partial - analysis budget exhausted",
			OverlayMessagePainter.getPartialResultMessage(partialResults("")));
	}

	@Test
	public void testNoMessageForCompleteResult() throws IOException, DecoderException {
		assertEquals("", OverlayMessagePainter.getPartialResultMessage(completeResults()));
	}

	@Test
	public void testNoMessageForNullResult() {
		assertEquals("", OverlayMessagePainter.getPartialResultMessage(null));
	}
}
