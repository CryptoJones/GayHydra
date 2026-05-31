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
// Rec 34 (#34-5a): worker-side codec for the v1 (FlatBuffers) decompile-function
// *response envelope* — the overall ResponseStatus plus the Diagnostic list.
// This is the response-path analogue of schema/ipc_request_codec.h, with the
// direction flipped: in the protocol the worker writes the response and the
// host reads it, so encode_decompile_response() is the worker's production
// direction. decode_decompile_response() mirrors the host's read and pins the
// same verify-before-read contract; it is exercised only by the round-trip and
// malformed-input tests (see ../unittests/testipc_response_codec.cc).
//
// Scope is deliberately the envelope only. The heavy response body — the
// PcodeOp/Varnode array and the HighFunction/HighSymbol/DataType/Storage tree —
// is several levels of nested, optional tables and is migrated in follow-up
// increments (#34-5b, #34-5c). A response encoded here leaves pcode and
// high_function unset on the wire; decode reads back status + diagnostics and
// does not touch them. FlatBuffers' forward-compatible layout makes that growth
// additive: a later increment extends DecompileResponseV1 and the encode/decode
// without rewriting this one.
//
// Like the request codec this header is inert: nothing in the production
// decompiler includes it. The command-loop wiring that would call it on a real
// decompile result is the separate, end-to-end-only change tracked by DD-0005.
#ifndef GHIDRA_IPC_RESPONSE_CODEC_H
#define GHIDRA_IPC_RESPONSE_CODEC_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "schema/decompile_generated.h"

namespace ghidra {
namespace ipc {

// Native view of one diagnostic. Defaults mirror the schema (decompile.fbs):
// Severity's zero value is INFO and pcode_seq defaults to 0.
struct DiagnosticV1 {
  Severity severity = Severity_INFO;
  std::string message;
  uint32_t pcode_seq = 0;
};

// Native view of the decompile-function response envelope, decoded out of a v1
// payload. status defaults to OK (the schema's zero value); diagnostics is
// empty for a clean result. The pcode and high_function members of the wire
// table are intentionally not represented here yet (see the header note).
struct DecompileResponseV1 {
  ResponseStatus status = ResponseStatus_OK;
  std::vector<DiagnosticV1> diagnostics;
};

// Encode a response envelope as a finished, root-typed FlatBuffers payload
// (schema v1), returning the buffer bytes ready to ride inside a Rec 33 frame's
// payload range. DecompileFunctionResponse is not the schema root_type, so the
// generic FlatBufferBuilder::Finish is used to root it. The returned vector owns
// a copy of the builder's bytes.
inline std::vector<uint8_t> encode_decompile_response(const DecompileResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  std::vector<::flatbuffers::Offset<Diagnostic>> diag_offsets;
  diag_offsets.reserve(in.diagnostics.size());
  for (const DiagnosticV1 &d : in.diagnostics) {
    diag_offsets.push_back(
        CreateDiagnosticDirect(fbb, d.severity, d.message.c_str(), d.pcode_seq));
  }
  auto root = CreateDecompileFunctionResponseDirect(fbb, in.status, nullptr, 0, &diag_offsets);
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

// Verify, then decode, a v1 response envelope. Returns false (leaving out
// untouched) when buf is null or the bytes fail FlatBuffers verification — a
// reader must treat an unverifiable payload as a protocol error and never read
// through it. On success, out.status and out.diagnostics are fully populated;
// an unset diagnostic message reads back as empty.
inline bool decode_decompile_response(const uint8_t *buf, size_t len, DecompileResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<DecompileFunctionResponse>(nullptr)) {
    return false;
  }
  const DecompileFunctionResponse *resp =
      ::flatbuffers::GetRoot<DecompileFunctionResponse>(buf);
  out.status = resp->status();
  out.diagnostics.clear();
  if (resp->diagnostics() != nullptr) {
    const auto *diags = resp->diagnostics();
    out.diagnostics.reserve(diags->size());
    for (::flatbuffers::uoffset_t i = 0; i < diags->size(); ++i) {
      const Diagnostic *d = diags->Get(i);
      DiagnosticV1 nd;
      nd.severity = d->severity();
      nd.message = (d->message() != nullptr) ? d->message()->str() : std::string();
      nd.pcode_seq = d->pcode_seq();
      out.diagnostics.push_back(nd);
    }
  }
  return true;
}

}  // namespace ipc
}  // namespace ghidra

#endif  // GHIDRA_IPC_RESPONSE_CODEC_H
