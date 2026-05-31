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

import ghidra.ipc.DecompileFunctionRequest;

/**
 * Host-side encoder for the Rec 34 v1 (FlatBuffers) decompile-function request
 * &mdash; the Java half of the {@code #34-4} dual-encode. The host produces the
 * v1 payload here; the C++ worker decodes it with
 * {@code schema/ipc_request_codec.h} (see {@code decode_decompile_request}).
 * <p>
 * Only the encode direction lives on the host: in the protocol the host writes
 * requests and the worker reads them, so a host-side request decoder would have
 * no production caller (and the vendored flatbuffers-java bindings here are
 * generated without a verifier, so a "decode" could not safely reject a
 * malformed buffer anyway). Tests read the bytes back with the generated
 * accessors to prove the round-trip; the worker's verified decode is covered by
 * the C++ {@code testipc_codec.cc}.
 * <p>
 * Inert until the command-loop wiring lands: nothing in {@code DecompileProcess}
 * calls this yet. That wiring is an end-to-end-only change, deferred out of the
 * codec PRs.
 */
public final class DecompileRequestCodec {

	private DecompileRequestCodec() {
		// no instances; static encoder only
	}

	/**
	 * Encode a decompile-function request as a finished, root-typed FlatBuffers
	 * v1 payload, ready to ride inside a Rec 33 frame's payload range.
	 *
	 * @param programId the program identifier; {@code null} leaves the field
	 *            unset (the worker reads it back as null), an empty string is a
	 *            present-but-empty value
	 * @param functionAddress the entry address of the function to decompile
	 *            (uint64; the full 64-bit pattern is carried)
	 * @param timeoutMs analysis budget in milliseconds (uint32); the schema
	 *            default is 30000, so encoding 30000 omits the field on the wire
	 * @param flags request flags bitset (uint32); the schema default is 0
	 * @return the payload bytes
	 */
	public static byte[] encodeRequest(String programId, long functionAddress, long timeoutMs,
			long flags) {
		FlatBufferBuilder builder = new FlatBufferBuilder(64);
		int programIdOffset = (programId != null) ? builder.createString(programId) : 0;
		int root = DecompileFunctionRequest.createDecompileFunctionRequest(builder, programIdOffset,
			functionAddress, timeoutMs, flags);
		DecompileFunctionRequest.finishDecompileFunctionRequestBuffer(builder, root);
		return builder.sizedByteArray();
	}
}
