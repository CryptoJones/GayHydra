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
package ghidra.app.decompiler.ipc;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;

import org.junit.Test;

import ghidra.ipc.DecompileBudget;
import ghidra.ipc.DecompileFunctionRequest;

/**
 * Round-trip unit tests for {@link DecompileRequestCodec}, the host's half of
 * the Rec 34 {@code #34-4} dual-encode. These read the encoded bytes back with
 * the generated accessors to prove the wire contract (scalars, string, schema
 * defaults, null-vs-empty program id). Pure FlatBuffers byte math &mdash; no
 * Ghidra runtime &mdash; so this lives in the fast {@code test} sourceset
 * alongside the framing tests and runs in CI's {@code gradle test}. The
 * worker-side verified decode of the same payload is covered by the C++
 * {@code testipc_codec.cc}.
 */
public class DecompileRequestCodecTest {

	private static DecompileFunctionRequest readBack(byte[] payload) {
		ByteBuffer bb = ByteBuffer.wrap(payload);
		return DecompileFunctionRequest.getRootAsDecompileFunctionRequest(bb);
	}

	@Test
	public void testRoundtripAllFields() {
		byte[] payload = DecompileRequestCodec.encodeRequest("prog-42", 0x401000L, 5000L, 0xFL);
		assertTrue("payload is non-empty", payload.length > 0);

		DecompileFunctionRequest req = readBack(payload);
		assertEquals("prog-42", req.programId());
		assertEquals(0x401000L, req.functionAddress());
		assertEquals(5000L, req.timeoutMs());
		assertEquals(0xFL, req.flags());
	}

	@Test
	public void testSchemaDefaultsReadBack() {
		// timeout encoded equal to its 30000 default is omitted from the buffer;
		// flags 0 is likewise omitted. Read-back must yield the schema defaults.
		byte[] payload = DecompileRequestCodec.encodeRequest("p", 0x10L, 30000L, 0L);

		DecompileFunctionRequest req = readBack(payload);
		assertEquals(0x10L, req.functionAddress());
		assertEquals("schema default timeout", 30000L, req.timeoutMs());
		assertEquals("schema default flags", 0L, req.flags());
	}

	@Test
	public void testNullProgramIdLeavesFieldUnset() {
		// A null program id is distinct from empty: the field is left unset and
		// the worker reads it back as null, not "".
		byte[] payload = DecompileRequestCodec.encodeRequest(null, 0x20L, 1234L, 1L);

		DecompileFunctionRequest req = readBack(payload);
		assertNull("unset program id reads back null", req.programId());
		assertEquals(0x20L, req.functionAddress());
		assertEquals(1234L, req.timeoutMs());
		assertEquals(1L, req.flags());
	}

	@Test
	public void testEmptyProgramIdRoundtripsAsEmpty() {
		// A present-but-empty string must survive as "", not collapse to null.
		byte[] payload = DecompileRequestCodec.encodeRequest("", 0x30L, 7L, 0L);

		DecompileFunctionRequest req = readBack(payload);
		assertEquals("", req.programId());
		assertEquals(0x30L, req.functionAddress());
	}

	@Test
	public void testUnsignedTimeoutAndFlagsAboveIntRange() {
		// timeout_ms and flags are uint32; values with the high bit set must
		// round-trip as their unsigned widening, not sign-extend to negative.
		byte[] payload = DecompileRequestCodec.encodeRequest("u", 0x40L, 0xFFFFFFFFL, 0x80000000L);

		DecompileFunctionRequest req = readBack(payload);
		assertEquals("uint32 timeout widens unsigned", 0xFFFFFFFFL, req.timeoutMs());
		assertEquals("uint32 flags widens unsigned", 0x80000000L, req.flags());
	}

	@Test
	public void testBudgetRoundtrip() {
		// The budget overload writes the optional DecompileBudget sub-table; each
		// of the five non-default caps must read back through req.budget() (#35-2).
		byte[] payload = DecompileRequestCodec.encodeRequest("prog-42", 0x401000L, 5000L, 0xFL,
			10000L, 20000L, 2048L, 500000L, 50L);

		DecompileFunctionRequest req = readBack(payload);
		DecompileBudget budget = req.budget();
		assertNotNull("budget sub-table is present", budget);
		assertEquals(10000L, budget.wallClockMs());
		assertEquals(20000L, budget.wallClockHardMs());
		assertEquals(2048L, budget.rssMaxMb());
		assertEquals(500000L, budget.pcodeOpLimit());
		assertEquals(50L, budget.iterationLimitPerPass());
	}

	@Test
	public void testAbsentBudgetReadsNull() {
		// The four-argument overload leaves the budget unset: req.budget() is null,
		// which the worker treats as "use the schema defaults".
		byte[] payload = DecompileRequestCodec.encodeRequest("p", 0x10L, 30000L, 0L);

		DecompileFunctionRequest req = readBack(payload);
		assertNull("absent budget reads back null", req.budget());
	}

	@Test
	public void testBudgetDefaultValuesReadBack() {
		// A budget whose caps all equal their schema defaults is a present sub-table
		// with every scalar omitted; the accessors must still yield the defaults.
		byte[] payload = DecompileRequestCodec.encodeRequest("p", 0x10L, 30000L, 0L,
			30000L, 60000L, 4096L, 1000000L, 100L);

		DecompileFunctionRequest req = readBack(payload);
		DecompileBudget budget = req.budget();
		assertNotNull("budget sub-table is present even when all-default", budget);
		assertEquals("schema default wall_clock_ms", 30000L, budget.wallClockMs());
		assertEquals("schema default wall_clock_hard_ms", 60000L, budget.wallClockHardMs());
		assertEquals("schema default rss_max_mb", 4096L, budget.rssMaxMb());
		assertEquals("schema default pcode_op_limit", 1000000L, budget.pcodeOpLimit());
		assertEquals("schema default iteration_limit_per_pass", 100L, budget.iterationLimitPerPass());
	}
}
