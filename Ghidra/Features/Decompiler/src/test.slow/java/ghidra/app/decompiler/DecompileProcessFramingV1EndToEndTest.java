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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.test.AbstractGhidraHeadlessIntegrationTest;
import ghidra.util.task.TaskMonitor;

/**
 * End-to-end regression guard for the Rec 33 #33-2.6 v1 framing tunnel.
 *
 * <p>Unlike {@code DecompileProcessFramingV1Test} (pure wire-format byte math
 * with no process spawn), this test drives a real decompilation against the
 * freshly built native {@code decompile} executable, once with framing forced
 * to v0 and once forced to v1. The {@code decompiler.framing} system property
 * is read fresh at each {@link DecompInterface#openProgram}, so the two runs
 * exercise two independent native processes from a single JVM.
 *
 * <p>Forcing v1 triggers the GREETING handshake and then tunnels the entire
 * legacy command loop — registerProgram, decompileAt, and every callback query
 * the native side makes back to Ghidra — through v1 frames. Asserting that the
 * v1 decompiled C is byte-identical to the v0 decompiled C proves the tunnel is
 * transparent across the full bidirectional protocol: any frame-boundary
 * desync would either change the output or hang/abort the decompile.
 */
public class DecompileProcessFramingV1EndToEndTest extends AbstractGhidraHeadlessIntegrationTest {

	private static final String FRAMING_PROPERTY = "decompiler.framing";
	private static final String LANGUAGE_ID = "avr8:LE:16:atmega256";
	private static final String FUNCTION_ADDR = "0x1000";
	private static final int FUNCTION_LENGTH = 27;

	// Real avr8 function bytes (borrowed from DecompilerPspecVolatilityTest);
	// the actual decompiled text is irrelevant here — only that v0 and v1
	// produce the exact same non-trivial output.
	private static final String FUNCTION_BYTES =
		"84 ff 02 c0 8d 9a 01 c0 8d 98 85 ff 02 c0 a4 9a 01 c0 a4 98 2f " +
			"b7 86 ff 05 c0 f8 94 90 91 02 01 90 68 04 c0 f8 94 90 91 02 01 9f 77 90 93 02 01" +
			" 2f bf 87 ff 02 c0 a3 9a 01 c0 a3 98 8f 9a 85 e0 8a 95 f1 f7 00 00 8f 98 08";

	private ProgramBuilder builder;
	private Program program;
	private String savedFramingProperty;

	@Before
	public void setUp() throws Exception {
		savedFramingProperty = System.getProperty(FRAMING_PROPERTY);
		builder = new ProgramBuilder("framingV1E2E", LANGUAGE_ID);
		builder.setBytes(FUNCTION_ADDR, FUNCTION_BYTES);
		builder.disassemble(FUNCTION_ADDR, FUNCTION_LENGTH, false);
		builder.createFunction(FUNCTION_ADDR);
		program = builder.getProgram();
	}

	@After
	public void tearDown() {
		if (savedFramingProperty == null) {
			System.clearProperty(FRAMING_PROPERTY);
		}
		else {
			System.setProperty(FRAMING_PROPERTY, savedFramingProperty);
		}
		if (builder != null) {
			builder.dispose();
		}
	}

	private String decompileUnder(String framingMode) throws Exception {
		System.setProperty(FRAMING_PROPERTY, framingMode);
		DecompInterface decompiler = new DecompInterface();
		try {
			assertTrue("openProgram failed under framing=" + framingMode,
				decompiler.openProgram(program));
			Address addr =
				program.getAddressFactory().getDefaultAddressSpace().getAddress(FUNCTION_ADDR);
			Function func = program.getListing().getFunctionAt(addr);
			assertNotNull("no function at " + FUNCTION_ADDR, func);
			DecompileResults results = decompiler.decompileFunction(func,
				DecompileOptions.SUGGESTED_DECOMPILE_TIMEOUT_SECS, TaskMonitor.DUMMY);
			assertTrue("decompile did not complete under framing=" + framingMode + ": " +
				results.getErrorMessage(), results.decompileCompleted());
			return results.getDecompiledFunction().getC();
		}
		finally {
			decompiler.dispose();
		}
	}

	@Test
	public void testV1FramingTunnelMatchesV0() throws Exception {
		String v0 = decompileUnder("v0");
		assertNotNull(v0);
		assertFalse("v0 decompilation was blank", v0.isBlank());

		String v1 = decompileUnder("v1");
		assertNotNull(v1);
		assertFalse("v1 decompilation was blank", v1.isBlank());

		assertEquals("v1 framing tunnel changed the decompiled output", v0, v1);
	}

	@Test
	public void testAutoFramingNegotiatesV1AndMatchesV0() throws Exception {
		// With a v1-capable native, the default "auto" mode negotiates v1; the
		// output must still match the explicit-v0 baseline.
		String v0 = decompileUnder("v0");
		String auto = decompileUnder("auto");
		assertEquals("auto framing changed the decompiled output", v0, auto);
	}
}
