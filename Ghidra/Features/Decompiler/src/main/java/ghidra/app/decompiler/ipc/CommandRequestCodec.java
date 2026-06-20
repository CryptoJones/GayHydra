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

import com.google.flatbuffers.FlatBufferBuilder;

import ghidra.ipc.DeregisterProgramRequest;
import ghidra.ipc.FlushNativeRequest;
import ghidra.ipc.RegisterProgramRequest;
import ghidra.ipc.SetActionRequest;
import ghidra.ipc.SetOptionsRequest;
import ghidra.ipc.StructureGraphRequest;

/**
 * Host-side encoders for the Rec 34 v1 (FlatBuffers) requests of the six
 * non-{@code DecompileAt} commands &mdash; the Java half of {@code #34-6}. The
 * decompile-function request has its own encoder ({@link DecompileRequestCodec},
 * {@code #34-4}); this class covers the rest: {@code RegisterProgram},
 * {@code DeregisterProgram}, {@code FlushNative}, {@code StructureGraph},
 * {@code SetAction}, and {@code SetOptions}. The host produces the v1 payload
 * here; the C++ worker decodes it with {@code schema/ipc_lifecycle_codec.h}
 * ({@code #34-6a}) and {@code schema/ipc_config_codec.h} ({@code #34-6b}).
 * <p>
 * Only the encode direction lives on the host: in the protocol the host writes
 * requests and the worker reads them, so a host-side request decoder would have
 * no production caller, and the vendored flatbuffers-java bindings are generated
 * without a verifier, so a host-side "decode" could not safely reject a
 * malformed buffer anyway (the worker's verified decode is covered by the C++
 * {@code testipc_lifecycle_codec.cc} / {@code testipc_config_codec.cc}). Tests
 * read the bytes back with the generated accessors to prove the round-trip.
 * <p>
 * None of these tables is the schema {@code root_type} (only
 * {@code DecompileFunctionRequest} is), so the generic
 * {@link FlatBufferBuilder#finish(int)} roots the buffer rather than a generated
 * {@code finish*Buffer} helper.
 * <p>
 * Live wiring began with #34-10b (DD-0080): {@code DecompileProcess} sends
 * {@link #encodeFlushNativeRequest} as a schema-v1 payload when the worker
 * advertises the capability; the remaining encoders go live one command at a
 * time across #34-10c..e.
 */
public final class CommandRequestCodec {

	private CommandRequestCodec() {
		// no instances; static encoders only
	}

	/**
	 * Encode a register-program request. The four spec documents are carried as
	 * opaque strings; a {@code null} argument leaves that field unset (the worker
	 * reads it back as null), an empty string is a present-but-empty value.
	 *
	 * @param processorSpec the {@code <pspec>} processor specification
	 * @param compilerSpec the {@code <cspec>} compiler specification
	 * @param translateSpec the stripped {@code <sleigh>} address-space description
	 * @param coreTypesSpec the {@code <coretypes>} built-in datatypes document
	 * @return the payload bytes
	 */
	public static byte[] encodeRegisterProgramRequest(String processorSpec, String compilerSpec,
			String translateSpec, String coreTypesSpec) {
		FlatBufferBuilder builder = new FlatBufferBuilder(128);
		int processorOffset = (processorSpec != null) ? builder.createString(processorSpec) : 0;
		int compilerOffset = (compilerSpec != null) ? builder.createString(compilerSpec) : 0;
		int translateOffset = (translateSpec != null) ? builder.createString(translateSpec) : 0;
		int coreTypesOffset = (coreTypesSpec != null) ? builder.createString(coreTypesSpec) : 0;
		int root = RegisterProgramRequest.createRegisterProgramRequest(builder, processorOffset,
			compilerOffset, translateOffset, coreTypesOffset);
		builder.finish(root);
		return builder.sizedByteArray();
	}

	/**
	 * Encode a deregister-program request.
	 *
	 * @param programId the program identifier; {@code null} leaves the field unset
	 * @return the payload bytes
	 */
	public static byte[] encodeDeregisterProgramRequest(String programId) {
		FlatBufferBuilder builder = new FlatBufferBuilder(32);
		int programIdOffset = (programId != null) ? builder.createString(programId) : 0;
		int root = DeregisterProgramRequest.createDeregisterProgramRequest(builder, programIdOffset);
		builder.finish(root);
		return builder.sizedByteArray();
	}

	/**
	 * Encode a flush-native request.
	 *
	 * @param programId the program identifier; {@code null} leaves the field unset
	 * @return the payload bytes
	 */
	public static byte[] encodeFlushNativeRequest(String programId) {
		FlatBufferBuilder builder = new FlatBufferBuilder(32);
		int programIdOffset = (programId != null) ? builder.createString(programId) : 0;
		int root = FlushNativeRequest.createFlushNativeRequest(builder, programIdOffset);
		builder.finish(root);
		return builder.sizedByteArray();
	}

	/**
	 * Encode a structure-graph request.
	 *
	 * @param programId the program identifier; {@code null} leaves the field unset
	 * @param controlFlow the encoded {@code <block>} control-flow document (opaque
	 *            string); {@code null} leaves the field unset
	 * @return the payload bytes
	 */
	public static byte[] encodeStructureGraphRequest(String programId, String controlFlow) {
		FlatBufferBuilder builder = new FlatBufferBuilder(128);
		int programIdOffset = (programId != null) ? builder.createString(programId) : 0;
		int controlFlowOffset = (controlFlow != null) ? builder.createString(controlFlow) : 0;
		int root = StructureGraphRequest.createStructureGraphRequest(builder, programIdOffset,
			controlFlowOffset);
		builder.finish(root);
		return builder.sizedByteArray();
	}

	/**
	 * Encode a set-action request. An empty selector leaves that setting unchanged
	 * on the worker (legacy semantics); a {@code null} argument leaves the field
	 * unset.
	 *
	 * @param programId the program identifier
	 * @param rootAction the root-action selector
	 *            ({@code decompile|normalize|jumptable|paramid|register|firstpass})
	 * @param printConfig the print-config selector ({@code tree|notree|c|noc|...})
	 * @return the payload bytes
	 */
	public static byte[] encodeSetActionRequest(String programId, String rootAction,
			String printConfig) {
		FlatBufferBuilder builder = new FlatBufferBuilder(64);
		int programIdOffset = (programId != null) ? builder.createString(programId) : 0;
		int rootActionOffset = (rootAction != null) ? builder.createString(rootAction) : 0;
		int printConfigOffset = (printConfig != null) ? builder.createString(printConfig) : 0;
		int root = SetActionRequest.createSetActionRequest(builder, programIdOffset, rootActionOffset,
			printConfigOffset);
		builder.finish(root);
		return builder.sizedByteArray();
	}

	/**
	 * Encode a set-options request.
	 *
	 * @param programId the program identifier; {@code null} leaves the field unset
	 * @param options the encoded {@code <optionslist>} document (opaque string);
	 *            {@code null} leaves the field unset
	 * @return the payload bytes
	 */
	public static byte[] encodeSetOptionsRequest(String programId, String options) {
		FlatBufferBuilder builder = new FlatBufferBuilder(128);
		int programIdOffset = (programId != null) ? builder.createString(programId) : 0;
		int optionsOffset = (options != null) ? builder.createString(options) : 0;
		int root = SetOptionsRequest.createSetOptionsRequest(builder, programIdOffset, optionsOffset);
		builder.finish(root);
		return builder.sizedByteArray();
	}
}
