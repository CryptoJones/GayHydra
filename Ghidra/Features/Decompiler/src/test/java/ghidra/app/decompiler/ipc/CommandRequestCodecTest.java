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

import ghidra.ipc.DeregisterProgramRequest;
import ghidra.ipc.FlushNativeRequest;
import ghidra.ipc.RegisterProgramRequest;
import ghidra.ipc.SetActionRequest;
import ghidra.ipc.SetOptionsRequest;
import ghidra.ipc.StructureGraphRequest;

/**
 * Round-trip unit tests for {@link CommandRequestCodec}, the host's half of the
 * Rec 34 {@code #34-6} command migration &mdash; the encoders for the six
 * non-{@code DecompileAt} requests ({@code RegisterProgram},
 * {@code DeregisterProgram}, {@code FlushNative}, {@code StructureGraph},
 * {@code SetAction}, {@code SetOptions}). Each test reads the encoded bytes back
 * with the generated accessors to prove the wire contract: every field
 * round-trips and a {@code null} argument leaves its field unset (read back as
 * {@code null}), distinct from a present-but-empty string. Pure FlatBuffers byte
 * math &mdash; no Ghidra runtime &mdash; so this lives in the fast {@code test}
 * sourceset alongside {@link DecompileRequestCodecTest} and runs in CI's
 * {@code gradle test}. The worker-side verified decode of the same payloads is
 * covered by the C++ {@code testipc_lifecycle_codec.cc} /
 * {@code testipc_config_codec.cc}.
 */
public class CommandRequestCodecTest {

	private static RegisterProgramRequest readRegister(byte[] payload) {
		return RegisterProgramRequest.getRootAsRegisterProgramRequest(ByteBuffer.wrap(payload));
	}

	private static DeregisterProgramRequest readDeregister(byte[] payload) {
		return DeregisterProgramRequest.getRootAsDeregisterProgramRequest(ByteBuffer.wrap(payload));
	}

	private static FlushNativeRequest readFlush(byte[] payload) {
		return FlushNativeRequest.getRootAsFlushNativeRequest(ByteBuffer.wrap(payload));
	}

	private static StructureGraphRequest readStructure(byte[] payload) {
		return StructureGraphRequest.getRootAsStructureGraphRequest(ByteBuffer.wrap(payload));
	}

	private static SetActionRequest readSetAction(byte[] payload) {
		return SetActionRequest.getRootAsSetActionRequest(ByteBuffer.wrap(payload));
	}

	private static SetOptionsRequest readSetOptions(byte[] payload) {
		return SetOptionsRequest.getRootAsSetOptionsRequest(ByteBuffer.wrap(payload));
	}

	// ------------------------------------------------------- RegisterProgram

	@Test
	public void testRegisterProgramRoundtripAllFields() {
		byte[] payload = CommandRequestCodec.encodeRegisterProgramRequest("<pspec/>", "<cspec/>",
			"<sleigh/>", "<coretypes/>");
		assertTrue("payload is non-empty", payload.length > 0);

		RegisterProgramRequest req = readRegister(payload);
		assertEquals("<pspec/>", req.processorSpec());
		assertEquals("<cspec/>", req.compilerSpec());
		assertEquals("<sleigh/>", req.translateSpec());
		assertEquals("<coretypes/>", req.coreTypesSpec());
	}

	@Test
	public void testRegisterProgramNullSpecLeavesFieldUnset() {
		// A null spec is distinct from empty: the field is left unset and the
		// worker reads it back as null, not "". Here only the compiler spec is set.
		byte[] payload =
			CommandRequestCodec.encodeRegisterProgramRequest(null, "<cspec/>", null, null);

		RegisterProgramRequest req = readRegister(payload);
		assertNull("unset processor spec reads back null", req.processorSpec());
		assertEquals("<cspec/>", req.compilerSpec());
		assertNull("unset translate spec reads back null", req.translateSpec());
		assertNull("unset core-types spec reads back null", req.coreTypesSpec());
	}

	@Test
	public void testRegisterProgramEmptySpecRoundtripsAsEmpty() {
		// A present-but-empty string must survive as "", not collapse to null.
		byte[] payload = CommandRequestCodec.encodeRegisterProgramRequest("", "", "", "");

		RegisterProgramRequest req = readRegister(payload);
		assertEquals("", req.processorSpec());
		assertEquals("", req.compilerSpec());
		assertEquals("", req.translateSpec());
		assertEquals("", req.coreTypesSpec());
	}

	// ----------------------------------------------------- DeregisterProgram

	@Test
	public void testDeregisterProgramRoundtrip() {
		byte[] payload = CommandRequestCodec.encodeDeregisterProgramRequest("prog-3");
		assertTrue("payload is non-empty", payload.length > 0);

		DeregisterProgramRequest req = readDeregister(payload);
		assertEquals("prog-3", req.programId());
	}

	@Test
	public void testDeregisterProgramNullProgramIdLeavesFieldUnset() {
		byte[] payload = CommandRequestCodec.encodeDeregisterProgramRequest(null);

		DeregisterProgramRequest req = readDeregister(payload);
		assertNull("unset program id reads back null", req.programId());
	}

	// ----------------------------------------------------------- FlushNative

	@Test
	public void testFlushNativeRoundtrip() {
		byte[] payload = CommandRequestCodec.encodeFlushNativeRequest("prog-4");

		FlushNativeRequest req = readFlush(payload);
		assertEquals("prog-4", req.programId());
	}

	@Test
	public void testFlushNativeEmptyProgramIdRoundtripsAsEmpty() {
		byte[] payload = CommandRequestCodec.encodeFlushNativeRequest("");

		FlushNativeRequest req = readFlush(payload);
		assertEquals("", req.programId());
	}

	// -------------------------------------------------------- StructureGraph

	@Test
	public void testStructureGraphRoundtripAllFields() {
		byte[] payload =
			CommandRequestCodec.encodeStructureGraphRequest("prog-2", "<block id=\"0\"/>");

		StructureGraphRequest req = readStructure(payload);
		assertEquals("prog-2", req.programId());
		assertEquals("<block id=\"0\"/>", req.controlFlow());
	}

	@Test
	public void testStructureGraphNullControlFlowLeavesFieldUnset() {
		byte[] payload = CommandRequestCodec.encodeStructureGraphRequest("prog-2", null);

		StructureGraphRequest req = readStructure(payload);
		assertEquals("prog-2", req.programId());
		assertNull("unset control flow reads back null", req.controlFlow());
	}

	// ------------------------------------------------------------- SetAction

	@Test
	public void testSetActionRoundtripAllFields() {
		byte[] payload = CommandRequestCodec.encodeSetActionRequest("prog-5", "decompile", "tree");

		SetActionRequest req = readSetAction(payload);
		assertEquals("prog-5", req.programId());
		assertEquals("decompile", req.rootAction());
		assertEquals("tree", req.printConfig());
	}

	@Test
	public void testSetActionEmptySelectorRoundtripsAsEmpty() {
		// An empty selector is the legacy "leave unchanged" signal; it must survive
		// as "" rather than collapse to null so the worker can tell the difference.
		byte[] payload = CommandRequestCodec.encodeSetActionRequest("prog-5", "", "");

		SetActionRequest req = readSetAction(payload);
		assertEquals("prog-5", req.programId());
		assertEquals("", req.rootAction());
		assertEquals("", req.printConfig());
	}

	@Test
	public void testSetActionNullSelectorLeavesFieldUnset() {
		byte[] payload = CommandRequestCodec.encodeSetActionRequest("prog-5", null, null);

		SetActionRequest req = readSetAction(payload);
		assertEquals("prog-5", req.programId());
		assertNull("unset root action reads back null", req.rootAction());
		assertNull("unset print config reads back null", req.printConfig());
	}

	// ------------------------------------------------------------ SetOptions

	@Test
	public void testSetOptionsRoundtripAllFields() {
		byte[] payload = CommandRequestCodec.encodeSetOptionsRequest("prog-8",
			"<optionslist><currentaction>conditionalexe</currentaction></optionslist>");

		SetOptionsRequest req = readSetOptions(payload);
		assertEquals("prog-8", req.programId());
		assertEquals("<optionslist><currentaction>conditionalexe</currentaction></optionslist>",
			req.options());
	}

	@Test
	public void testSetOptionsNullOptionsLeavesFieldUnset() {
		byte[] payload = CommandRequestCodec.encodeSetOptionsRequest("prog-8", null);

		SetOptionsRequest req = readSetOptions(payload);
		assertEquals("prog-8", req.programId());
		assertNull("unset options reads back null", req.options());
	}
}
