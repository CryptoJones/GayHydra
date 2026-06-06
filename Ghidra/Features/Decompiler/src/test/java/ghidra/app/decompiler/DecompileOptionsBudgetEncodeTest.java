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

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Test;

import ghidra.program.model.pcode.XmlEncode;

/**
 * Round-trip unit tests for the Rec 35 {@code #35-5a} GUI budget-set half:
 * {@link DecompileOptions#setDecompileBudget(int)} must emit the already
 * worker-registered {@code <decompilebudget>} option element so a budget chosen
 * in the tool options reaches {@code OptionDecompileBudget::apply} in the
 * decompiler process. The element name and id (290) are fixed to match the C++
 * {@code ELEM_DECOMPILEBUDGET} (options.cc); these tests pin the Java encode
 * contract end of that wire. Pure encode math &mdash; no decompiler process
 * &mdash; so this lives in the fast {@code test} sourceset and runs in CI's
 * {@code gradle test}. The worker-side decode and the actual flow truncation
 * (surfaced as {@code DecompileResults.isPartial()}) are covered by {@code
 * #35-5a-2}.
 */
public class DecompileOptionsBudgetEncodeTest {

	private static String encode(DecompileOptions options) throws IOException {
		// iface is only consulted for the currentaction branches, which stay at
		// their defaults here, so a null interface is safe for these options.
		XmlEncode encoder = new XmlEncode(false);
		options.encode(encoder, null);
		return encoder.toString();
	}

	@Test
	public void testBudgetEmittedWhenSet() throws IOException {
		DecompileOptions options = new DecompileOptions();
		options.setDecompileBudget(50);
		assertEquals(50, options.getDecompileBudget());
		String xml = encode(options);
		assertTrue("expected a <decompilebudget> element carrying the cap, got: " + xml,
			xml.contains("<decompilebudget>50</decompilebudget>"));
	}

	@Test
	public void testBudgetOmittedByDefault() throws IOException {
		DecompileOptions options = new DecompileOptions();
		assertEquals("budget must default to 0 (disengaged)", 0, options.getDecompileBudget());
		String xml = encode(options);
		assertFalse("a disengaged budget must not emit the option element, got: " + xml,
			xml.contains("decompilebudget"));
	}

	@Test
	public void testBudgetOmittedWhenResetToZero() throws IOException {
		DecompileOptions options = new DecompileOptions();
		options.setDecompileBudget(120);
		options.setDecompileBudget(0);
		String xml = encode(options);
		assertFalse("clearing the budget back to 0 must suppress the element, got: " + xml,
			xml.contains("decompilebudget"));
	}
}
