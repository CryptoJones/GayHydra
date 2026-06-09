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

import java.util.Iterator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.test.ToyProgramBuilder;

/**
 * Pilot for the Rec 30 headless integration harness
 * ({@link AbstractDecompilerHighFunctionTest}): proves that, with no
 * {@code DISPLAY} and no Swing tool, the harness loads a small program, drives the
 * real decompiler process, and hands back a materialized {@link HighFunction} plus
 * C output that a recognition pass can assert against. This is the gate from
 * {@code docs/decisions/0023-rec30-headless-highfunction-harness.md}; the Rec 37 C++
 * recognition wrappers build on exactly this path.
 *
 * <p>The fixture is a two-instruction Toy function &mdash; load an immediate into
 * {@code r0}, then return &mdash; built entirely in memory via
 * {@link ToyProgramBuilder}. It needs no committed binary blob, no architecture
 * toolchain, and decompiles deterministically in well under a second, so the pilot
 * stays a fast, self-contained smoke test of the harness mechanics rather than a
 * test of any particular decompiler output.
 */
public class HeadlessHighFunctionHarnessTest extends AbstractDecompilerHighFunctionTest {

	private static final String ENTRY = "0x0";

	private ToyProgramBuilder builder;
	private Program program;
	private Function function;

	@Before
	public void setUp() throws Exception {
		builder = new ToyProgramBuilder();
		builder.createMemory("test", ENTRY, 0x10);
		builder.addBytesMoveImmediate(ENTRY, (short) 7); // r0 = 7   (2 bytes)
		builder.addBytesReturn("0x2");                   // return   (2 bytes)
		function = builder.createFunction(ENTRY);
		program = builder.getProgram();
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testHarnessProducesHighFunction() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, function);

		Address entry = program.getAddressFactory().getDefaultAddressSpace().getAddress(ENTRY);
		assertEquals("HighFunction is for a different entry point", entry,
			highFunction.getFunction().getEntryPoint());

		// A materialized syntax tree the recognition pass can walk: at minimum the
		// immediate-load and the return produce p-code ops.
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		int opCount = 0;
		while (ops.hasNext()) {
			ops.next();
			opCount++;
		}
		assertTrue("HighFunction carried no p-code ops", opCount > 0);
	}

	@Test
	public void testHarnessCOutputReadableAfterDispose() {
		// The helper disposes its DecompInterface before returning; reading the C
		// markup here proves DecompileResults is fully materialized and outlives the
		// native process.
		DecompileResults results = decompile(program, function);
		assertTrue("decompile did not complete", results.decompileCompleted());
		assertNotNull("no C markup produced", results.getCCodeMarkup());

		DecompiledFunction decompiled = results.getDecompiledFunction();
		assertNotNull("no pretty-printed C produced", decompiled);
		assertFalse("decompiled C was blank", decompiled.getC().isBlank());
	}
}
