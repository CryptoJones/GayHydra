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
 * End-to-end regression guard for the Rec 33 #33-2.6 v1 framing tunnel and,
 * since #34-10b (DD-0080), the Rec 34 schema-v1 payload go-live.
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
 *
 * <p>The schema-payload legs additionally exercise the first live schema-v1
 * command ({@code flushNative} as a SCHEMA_PAYLOAD frame): a checked flush
 * between two decompiles of the same function proves the worker decodes the
 * FlatBuffers request, keeps functioning, and the frame stream stays aligned.
 * The {@code decompiler.schemapayload} kill switch ("off") is asserted against
 * the default ("auto") baseline, and a sent-counter observable distinguishes a
 * real schema exchange from a silent fallback to v0 payloads.
 */
public class DecompileProcessFramingV1EndToEndTest extends AbstractGhidraHeadlessIntegrationTest {

	private static final String FRAMING_PROPERTY = "decompiler.framing";
	private static final String SCHEMA_PAYLOAD_PROPERTY = "decompiler.schemapayload";
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
	private String savedSchemaPayloadProperty;

	@Before
	public void setUp() throws Exception {
		savedFramingProperty = System.getProperty(FRAMING_PROPERTY);
		savedSchemaPayloadProperty = System.getProperty(SCHEMA_PAYLOAD_PROPERTY);
		builder = new ProgramBuilder("framingV1E2E", LANGUAGE_ID);
		builder.setBytes(FUNCTION_ADDR, FUNCTION_BYTES);
		builder.disassemble(FUNCTION_ADDR, FUNCTION_LENGTH, false);
		builder.createFunction(FUNCTION_ADDR);
		program = builder.getProgram();
	}

	@After
	public void tearDown() {
		restoreProperty(FRAMING_PROPERTY, savedFramingProperty);
		restoreProperty(SCHEMA_PAYLOAD_PROPERTY, savedSchemaPayloadProperty);
		if (builder != null) {
			builder.dispose();
		}
	}

	private static void restoreProperty(String name, String saved) {
		if (saved == null) {
			System.clearProperty(name);
		}
		else {
			System.setProperty(name, saved);
		}
	}

	/** Callback receiving a live, opened decompiler before it is disposed. */
	private interface DecompilerCheck {
		void check(DecompInterface decompiler, String firstC) throws Exception;
	}

	/**
	 * Open the program under the given framing mode, decompile the test
	 * function once, run the callback against the live decompiler (handing it
	 * that first decompile for comparisons), and dispose. The first decompile's
	 * C is returned for baseline comparisons.
	 */
	private String withDecompiler(String framingMode, DecompilerCheck body) throws Exception {
		System.setProperty(FRAMING_PROPERTY, framingMode);
		DecompInterface decompiler = new DecompInterface();
		try {
			assertTrue("openProgram failed under framing=" + framingMode,
				decompiler.openProgram(program));
			String c = decompileTestFunction(decompiler, framingMode);
			if (body != null) {
				body.check(decompiler, c);
			}
			return c;
		}
		finally {
			decompiler.dispose();
		}
	}

	private String decompileTestFunction(DecompInterface decompiler, String label)
			throws Exception {
		Address addr =
			program.getAddressFactory().getDefaultAddressSpace().getAddress(FUNCTION_ADDR);
		Function func = program.getListing().getFunctionAt(addr);
		assertNotNull("no function at " + FUNCTION_ADDR, func);
		DecompileResults results = decompiler.decompileFunction(func,
			DecompileOptions.SUGGESTED_DECOMPILE_TIMEOUT_SECS, TaskMonitor.DUMMY);
		assertTrue("decompile did not complete under " + label + ": " +
			results.getErrorMessage(), results.decompileCompleted());
		return results.getDecompiledFunction().getC();
	}

	private String decompileUnder(String framingMode) throws Exception {
		return withDecompiler(framingMode, null);
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

	@Test
	public void testWorkerAdvertisesSchemaV1Requests() throws Exception {
		// #34-10b: on a v1 channel the worker greeting advertises that it
		// decodes schema-v1 request payloads; a v0 channel has no
		// capability concept and must report 0.
		withDecompiler("v1", (decompiler, firstC) -> {
			DecompileProcess process = decompiler.decompProcess;
			assertTrue("v1 channel did not negotiate", process.isChannelV1());
			assertTrue("worker did not advertise SCHEMA_V1_REQUESTS",
				process.peerAcceptsSchemaV1Requests());
		});
		withDecompiler("v0", (decompiler, firstC) -> {
			DecompileProcess process = decompiler.decompProcess;
			assertFalse(process.isChannelV1());
			assertEquals("v0 channel must carry no capabilities", 0,
				process.getPeerCapabilities());
		});
	}

	@Test
	public void testSchemaV1FlushNativeWorkerSurvives() throws Exception {
		// #34-10b: the first live schema-v1 command. flushCache() returns the
		// worker's FlushNative result (0) on success and -1 on the
		// swallowed-exception/stopProcess path, so the return value is the
		// desync detector; the second decompile on the same live process
		// proves the frame stream stayed aligned after the schema exchange
		// (the re-fetch callbacks ride v0 inside the same v1 session — the
		// mixed-per-frame session DD-0080 designed for).
		String v0 = decompileUnder("v0");
		String auto = withDecompiler("auto", (decompiler, firstC) -> {
			DecompileProcess process = decompiler.decompProcess;
			// decompileFunction already flushed once via its trailing
			// flushCache, so a schema exchange has happened by now.
			assertTrue("no schema-v1 request was sent (silent v0 fallback?)",
				process.getSchemaV1RequestsSent() > 0);
			assertEquals("flushNative round-trip failed", 0, decompiler.flushCache());
			String again = decompileTestFunction(decompiler, "auto+flush");
			assertEquals("output changed after schema-v1 flushNative", firstC, again);
			assertTrue("process not ready after schema-v1 flushNative",
				process.isReady());
		});
		assertEquals("schema-v1 payloads changed the decompiled output", v0, auto);
	}

	@Test
	public void testSchemaPayloadOffMatchesAuto() throws Exception {
		// The kill switch: under decompiler.schemapayload=off every payload
		// stays v0 even on a v1 channel (counter stays 0), and the output is
		// byte-identical to the default-auto leg.
		System.setProperty(SCHEMA_PAYLOAD_PROPERTY, "off");
		String off = withDecompiler("auto", (decompiler, firstC) -> {
			DecompileProcess process = decompiler.decompProcess;
			assertEquals("kill switch leaked a schema-v1 request", 0,
				process.getSchemaV1RequestsSent());
			assertEquals("v0-payload flushNative failed under off", 0,
				decompiler.flushCache());
		});
		System.setProperty(SCHEMA_PAYLOAD_PROPERTY, "auto");
		String auto = withDecompiler("auto", (decompiler, firstC) -> {
			assertTrue("auto leg sent no schema-v1 request",
				decompiler.decompProcess.getSchemaV1RequestsSent() > 0);
		});
		assertEquals("schemapayload=off changed the decompiled output", off, auto);
	}
}
