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
// Rec 34 (#34-4): worker-side codec for the v1 (FlatBuffers) decompile-function
// request. This is the testable half of the dual-encode step — the pure
// payload<->fields mapping, with no dependency on the live command loop or on
// Ghidra's native Address/Architecture types, so it can be unit-tested in
// isolation (see ../unittests/testipc_codec.cc). The command-loop wiring that
// would actually call decode_decompile_request() on an incoming v1 frame is a
// separate, end-to-end-only change (it compiles solely into ghidra_dbg and is
// not exercisable by the C++ precheck) and is intentionally NOT done here.
// Until that wiring lands this header is inert: nothing in the production
// decompiler includes it.
#ifndef GHIDRA_IPC_REQUEST_CODEC_H
#define GHIDRA_IPC_REQUEST_CODEC_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "schema/decompile_generated.h"

namespace ghidra {
namespace ipc {

// Native view of the per-function analysis budget (Rec 35, #35-2). Defaults
// mirror the schema (decompile.fbs / DECOMPILER_BUDGETS.md), so an all-unset or
// absent budget reads back as these values rather than zero-by-omission.
struct DecompileBudgetV1 {
  uint32_t wall_clock_ms = 30000;
  uint32_t wall_clock_hard_ms = 60000;
  uint32_t rss_max_mb = 4096;
  uint32_t pcode_op_limit = 1000000;
  uint32_t iteration_limit_per_pass = 100;
};

// Native view of a decompile-function request, decoded out of a v1 payload.
// Defaults mirror the schema (decompile.fbs) so a freshly-constructed value
// matches what decode yields for an all-unset buffer.
struct DecompileRequestV1 {
  std::string program_id;
  uint64_t function_address = 0;
  uint32_t timeout_ms = 30000;  // schema default
  uint32_t flags = 0;           // schema default
  DecompileBudgetV1 budget;     // schema defaults when the request omits it
};

// Encode a request as a finished, root-typed FlatBuffers payload (schema v1),
// returning the buffer bytes ready to ride inside a Rec 33 frame's payload
// range. The returned vector owns a copy of the builder's bytes. A non-null
// budget is encoded as the optional budget sub-table; a null budget leaves the
// field unset, which the worker reads back as the schema defaults.
inline std::vector<uint8_t> encode_decompile_request(const std::string &program_id,
                                                     uint64_t function_address,
                                                     uint32_t timeout_ms,
                                                     uint32_t flags,
                                                     const DecompileBudgetV1 *budget = nullptr) {
  ::flatbuffers::FlatBufferBuilder fbb;
  ::flatbuffers::Offset<DecompileBudget> budget_off = 0;
  if (budget != nullptr) {
    budget_off = CreateDecompileBudget(fbb, budget->wall_clock_ms, budget->wall_clock_hard_ms,
                                       budget->rss_max_mb, budget->pcode_op_limit,
                                       budget->iteration_limit_per_pass);
  }
  auto root = CreateDecompileFunctionRequestDirect(fbb, program_id.c_str(),
                                                   function_address, timeout_ms, flags, budget_off);
  FinishDecompileFunctionRequestBuffer(fbb, root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

// Verify, then decode, a v1 request payload. Returns false (leaving out
// untouched) when buf is null or the bytes fail FlatBuffers verification — the
// worker must treat an unverifiable payload as a protocol error and never read
// through it. On success, every field of out is populated; an unset string
// reads back as empty and unset scalars read back as their schema defaults.
inline bool decode_decompile_request(const uint8_t *buf, size_t len, DecompileRequestV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!VerifyDecompileFunctionRequestBuffer(verifier)) {
    return false;
  }
  const DecompileFunctionRequest *req = GetDecompileFunctionRequest(buf);
  out.program_id = (req->program_id() != nullptr) ? req->program_id()->str() : std::string();
  out.function_address = req->function_address();
  out.timeout_ms = req->timeout_ms();
  out.flags = req->flags();
  // budget is optional: an absent sub-table leaves out.budget at its schema
  // defaults; a present one supplies each field (itself defaulted when omitted).
  const DecompileBudget *b = req->budget();
  if (b != nullptr) {
    out.budget.wall_clock_ms = b->wall_clock_ms();
    out.budget.wall_clock_hard_ms = b->wall_clock_hard_ms();
    out.budget.rss_max_mb = b->rss_max_mb();
    out.budget.pcode_op_limit = b->pcode_op_limit();
    out.budget.iteration_limit_per_pass = b->iteration_limit_per_pass();
  }
  return true;
}

}  // namespace ipc
}  // namespace ghidra

#endif  // GHIDRA_IPC_REQUEST_CODEC_H
