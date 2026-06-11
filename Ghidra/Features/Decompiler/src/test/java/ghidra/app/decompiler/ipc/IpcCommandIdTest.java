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

import org.junit.Test;

/**
 * Pinned-value tests for the Rec 34 {@code #34-10a} (DD-0080) schema-v1
 * command-id registry. The id values are a wire contract shared with the
 * C++ mirror ({@code schema/ipc_command_ids.h}, pinned by
 * {@code testipc_command_ids.cc}) — if an id changes here without the C++
 * side moving in lock-step, one of the two suites goes red. Pure byte
 * math — no Ghidra runtime, so this lives in the fast {@code test}
 * sourceset.
 */
public class IpcCommandIdTest {

	@Test
	public void testPinnedWireValues() {
		// Numeric literals on purpose — the cross-language golden contract.
		assertEquals(0x01, IpcCommandId.REGISTER_PROGRAM.getWireId());
		assertEquals(0x02, IpcCommandId.DEREGISTER_PROGRAM.getWireId());
		assertEquals(0x03, IpcCommandId.FLUSH_NATIVE.getWireId());
		assertEquals(0x04, IpcCommandId.DECOMPILE_AT.getWireId());
		assertEquals(0x05, IpcCommandId.STRUCTURE_GRAPH.getWireId());
		assertEquals(0x06, IpcCommandId.SET_ACTION.getWireId());
		assertEquals(0x07, IpcCommandId.SET_OPTIONS.getWireId());
		assertEquals("registry covers exactly the seven schematized commands", 7,
			IpcCommandId.values().length);
	}

	@Test
	public void testForNameCoversSchematizedCommands() {
		assertEquals(IpcCommandId.REGISTER_PROGRAM, IpcCommandId.forName("registerProgram"));
		assertEquals(IpcCommandId.DEREGISTER_PROGRAM, IpcCommandId.forName("deregisterProgram"));
		assertEquals(IpcCommandId.FLUSH_NATIVE, IpcCommandId.forName("flushNative"));
		assertEquals(IpcCommandId.DECOMPILE_AT, IpcCommandId.forName("decompileAt"));
		assertEquals(IpcCommandId.STRUCTURE_GRAPH, IpcCommandId.forName("structureGraph"));
		assertEquals(IpcCommandId.SET_ACTION, IpcCommandId.forName("setAction"));
		assertEquals(IpcCommandId.SET_OPTIONS, IpcCommandId.forName("setOptions"));
	}

	@Test
	public void testUnschematizedNamesReturnNull() {
		// The deliberately un-schematized surface stays v0 (DD-0080
		// carve-out): the registry must answer null, the "stays v0" signal.
		assertNull(IpcCommandId.forName("generateSignatures"));
		assertNull(IpcCommandId.forName("debugSignatures"));
		assertNull(IpcCommandId.forName("getSignatureSettings"));
		assertNull(IpcCommandId.forName("setSignatureSettings"));
		assertNull(IpcCommandId.forName(""));
		assertNull(IpcCommandId.forName("RegisterProgram")); // case matters
	}

	@Test
	public void testWireIdRoundtripAndUnknown() {
		for (IpcCommandId id : IpcCommandId.values()) {
			assertEquals(id, IpcCommandId.forWireId(id.getWireId()));
			assertEquals(id, IpcCommandId.forName(id.getCommandName()));
		}
		assertNull("0x00 is the reserved invalid sentinel", IpcCommandId.forWireId(0x00));
		assertNull(IpcCommandId.forWireId(0x7f));
		assertNull(IpcCommandId.forWireId(0xff));
	}
}
