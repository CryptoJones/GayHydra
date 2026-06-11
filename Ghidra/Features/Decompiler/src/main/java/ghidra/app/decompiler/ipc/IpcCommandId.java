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

/**
 * Rec 34 {@code #34-10a} (DD-0080): the wire registry of schema-v1 command
 * ids. A {@code SCHEMA_PAYLOAD} frame's body is
 * {@code [u8 command-id][FlatBuffers bytes]}; this enum is the single Java
 * home of the id&lt;-&gt;name mapping. The C++ mirror is
 * {@code schema/ipc_command_ids.h} — the two are locked together by
 * pinned-value unit tests on each side ({@code IpcCommandIdTest} /
 * {@code testipc_command_ids.cc}), the same cross-language golden-vector
 * pattern the framing CRC uses.
 *
 * <p>Ids cover exactly the seven schematized commands ({@code decompile.fbs}),
 * in the worker's command-registration order ({@code ghidra_process.cc}).
 * {@code 0x00} is reserved as the invalid/unknown sentinel and never goes on
 * the wire. The un-schematized surface (the signature commands, the
 * mid-decompile callback sub-protocol) has no id by design — it rides v0
 * regardless (DD-0080's carve-out).
 */
public enum IpcCommandId {

	REGISTER_PROGRAM(0x01, "registerProgram"),
	DEREGISTER_PROGRAM(0x02, "deregisterProgram"),
	FLUSH_NATIVE(0x03, "flushNative"),
	DECOMPILE_AT(0x04, "decompileAt"),
	STRUCTURE_GRAPH(0x05, "structureGraph"),
	SET_ACTION(0x06, "setAction"),
	SET_OPTIONS(0x07, "setOptions");

	private final int wireId;
	private final String commandName;

	private IpcCommandId(int wireId, String commandName) {
		this.wireId = wireId;
		this.commandName = commandName;
	}

	/**
	 * {@return the one-byte wire id leading a SCHEMA_PAYLOAD frame body}
	 */
	public int getWireId() {
		return wireId;
	}

	/**
	 * {@return the v0 command name this id replaces}
	 */
	public String getCommandName() {
		return commandName;
	}

	/**
	 * Map a v0 command name to its schema-v1 id.
	 * @param name the v0 command name
	 * @return the matching id, or null for a name outside the seven
	 *         schematized commands (the caller's "stays v0" signal)
	 */
	public static IpcCommandId forName(String name) {
		for (IpcCommandId id : values()) {
			if (id.commandName.equals(name)) {
				return id;
			}
		}
		return null;
	}

	/**
	 * Map a wire byte back to its schema-v1 id.
	 * @param wireId the one-byte wire id (unsigned)
	 * @return the matching id, or null for an out-of-registry byte (the
	 *         receiver's protocol-error signal for an unknown id, DD-0080)
	 */
	public static IpcCommandId forWireId(int wireId) {
		for (IpcCommandId id : values()) {
			if (id.wireId == wireId) {
				return id;
			}
		}
		return null;
	}
}
