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

import ghidra.ipc.DecompileBudget;
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
		int root = buildRequest(builder, programId, functionAddress, timeoutMs, flags, 0);
		DecompileFunctionRequest.finishDecompileFunctionRequestBuffer(builder, root);
		return builder.sizedByteArray();
	}

	/**
	 * Encode a decompile-function request carrying an explicit per-function
	 * analysis budget (Rec 35, {@code #35-2}). The five caps ride in an optional
	 * {@code DecompileBudget} sub-table; the worker reads it back with
	 * {@code decode_decompile_request} and threads it through the analysis loop.
	 * Each cap is a uint32; encoding a value equal to its schema default omits it
	 * on the wire, and the worker reads back the default
	 * (see docs/decompiler/DECOMPILER_BUDGETS.md). The four-argument overload
	 * leaves the budget unset, which the worker reads back as all defaults.
	 *
	 * @param programId the program identifier; {@code null} leaves the field unset
	 * @param functionAddress the entry address of the function to decompile (uint64)
	 * @param timeoutMs analysis budget in milliseconds (uint32; schema default 30000)
	 * @param flags request flags bitset (uint32; schema default 0)
	 * @param wallClockMs soft wall-clock cap in ms (schema default 30000)
	 * @param wallClockHardMs hard wall-clock cap in ms (schema default 60000)
	 * @param rssMaxMb hard resident-set cap in MiB (schema default 4096)
	 * @param pcodeOpLimit soft pcode-op count cap (schema default 1000000)
	 * @param iterationLimitPerPass soft per-pass fixed-point cap (schema default 100)
	 * @return the payload bytes
	 */
	public static byte[] encodeRequest(String programId, long functionAddress, long timeoutMs,
			long flags, long wallClockMs, long wallClockHardMs, long rssMaxMb, long pcodeOpLimit,
			long iterationLimitPerPass) {
		FlatBufferBuilder builder = new FlatBufferBuilder(64);
		int budgetOffset = DecompileBudget.createDecompileBudget(builder, wallClockMs,
			wallClockHardMs, rssMaxMb, pcodeOpLimit, iterationLimitPerPass);
		int root = buildRequest(builder, programId, functionAddress, timeoutMs, flags, budgetOffset);
		DecompileFunctionRequest.finishDecompileFunctionRequestBuffer(builder, root);
		return builder.sizedByteArray();
	}

	// Build the request table on an in-progress builder. The program-id string
	// and the budget sub-table (when non-zero) must already be created, since a
	// FlatBuffers table cannot be built while another object is in progress.
	private static int buildRequest(FlatBufferBuilder builder, String programId,
			long functionAddress, long timeoutMs, long flags, int budgetOffset) {
		int programIdOffset = (programId != null) ? builder.createString(programId) : 0;
		return DecompileFunctionRequest.createDecompileFunctionRequest(builder, programIdOffset,
			functionAddress, timeoutMs, flags, budgetOffset);
	}
}
