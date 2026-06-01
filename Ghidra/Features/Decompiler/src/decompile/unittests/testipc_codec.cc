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
// Rec 34 (#34-4): unit tests for the worker-side v1 request codec
// (schema/ipc_request_codec.h). These pin the encode<->decode round-trip and,
// crucially, the malformed-input contract: decode must reject an unverifiable
// buffer rather than read through it, which is the property the command-loop
// wiring will rely on when it starts handing decode_decompile_request() bytes
// straight off the wire. Pure and inert — no production code links the codec
// yet (see the header's note); this test is its only caller.
#include "test.hh"
#include "schema/ipc_request_codec.h"

namespace ghidra {

// Every field survives a full encode -> decode round-trip with non-default
// values, including a non-default timeout and a non-zero flags word.
TEST(ipc_codec_roundtrip) {
  std::vector<uint8_t> buf = ipc::encode_decompile_request("prog-42", 0x401000ULL, 5000u, 0xFu);
  ASSERT(!buf.empty());

  ipc::DecompileRequestV1 out;
  ASSERT(ipc::decode_decompile_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-42"));
  ASSERT_EQUALS(out.function_address, 0x401000ULL);
  ASSERT_EQUALS(out.timeout_ms, 5000u);
  ASSERT_EQUALS(out.flags, 0xFu);
}

// A field encoded equal to its schema default is not stored in the buffer;
// decode must still read it back as that default, not as zero-by-omission.
TEST(ipc_codec_schema_defaults) {
  std::vector<uint8_t> buf = ipc::encode_decompile_request("p", 0x10ULL, 30000u, 0u);

  ipc::DecompileRequestV1 out;
  ASSERT(ipc::decode_decompile_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.function_address, 0x10ULL);
  ASSERT_EQUALS(out.timeout_ms, 30000u);  // schema default, omitted from buffer
  ASSERT_EQUALS(out.flags, 0u);           // schema default, omitted from buffer
}

// An empty program_id is a present-but-empty string, distinct from unset; it
// must round-trip to an empty string, not get dropped or crash decode.
TEST(ipc_codec_empty_program_id) {
  std::vector<uint8_t> buf = ipc::encode_decompile_request("", 0x20ULL, 1234u, 1u);

  ipc::DecompileRequestV1 out;
  ASSERT(ipc::decode_decompile_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string(""));
  ASSERT_EQUALS(out.function_address, 0x20ULL);
}

// A null pointer is rejected without dereference.
TEST(ipc_codec_rejects_null) {
  ipc::DecompileRequestV1 out;
  ASSERT(!ipc::decode_decompile_request((const uint8_t *)0, 0, out));
}

// Garbage bytes must fail verification, not be read through. The sentinel
// values prove decode left out untouched on rejection.
TEST(ipc_codec_rejects_garbage) {
  uint8_t junk[16];
  for (int i = 0; i < 16; ++i) {
    junk[i] = (uint8_t)(0xD0 + i);
  }
  ipc::DecompileRequestV1 out;
  out.function_address = 0xDEADBEEFULL;
  ASSERT(!ipc::decode_decompile_request(junk, sizeof(junk), out));
  ASSERT_EQUALS(out.function_address, 0xDEADBEEFULL);  // untouched on failure
}

// A truncated copy of a valid buffer must also fail verification.
TEST(ipc_codec_rejects_truncated) {
  std::vector<uint8_t> buf = ipc::encode_decompile_request("prog-42", 0x401000ULL, 5000u, 0xFu);
  ASSERT(buf.size() > 4);

  ipc::DecompileRequestV1 out;
  ASSERT(!ipc::decode_decompile_request(buf.data(), buf.size() / 2, out));
}

// An explicit, all-non-default budget survives the encode -> decode round-trip:
// every one of the five caps reads back the value it was given (Rec 35, #35-2).
TEST(ipc_codec_budget_roundtrip) {
  ipc::DecompileBudgetV1 budget;
  budget.wall_clock_ms = 10000u;
  budget.wall_clock_hard_ms = 20000u;
  budget.rss_max_mb = 2048u;
  budget.pcode_op_limit = 500000u;
  budget.iteration_limit_per_pass = 50u;

  std::vector<uint8_t> buf = ipc::encode_decompile_request("prog-42", 0x401000ULL, 5000u, 0xFu, &budget);
  ASSERT(!buf.empty());

  ipc::DecompileRequestV1 out;
  ASSERT(ipc::decode_decompile_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.budget.wall_clock_ms, 10000u);
  ASSERT_EQUALS(out.budget.wall_clock_hard_ms, 20000u);
  ASSERT_EQUALS(out.budget.rss_max_mb, 2048u);
  ASSERT_EQUALS(out.budget.pcode_op_limit, 500000u);
  ASSERT_EQUALS(out.budget.iteration_limit_per_pass, 50u);
}

// A request encoded without a budget leaves the sub-table absent; decode must
// read the budget back as the schema defaults, not as zero-by-omission.
TEST(ipc_codec_budget_absent_defaults) {
  std::vector<uint8_t> buf = ipc::encode_decompile_request("p", 0x10ULL, 30000u, 0u);

  ipc::DecompileRequestV1 out;
  ASSERT(ipc::decode_decompile_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.budget.wall_clock_ms, 30000u);
  ASSERT_EQUALS(out.budget.wall_clock_hard_ms, 60000u);
  ASSERT_EQUALS(out.budget.rss_max_mb, 4096u);
  ASSERT_EQUALS(out.budget.pcode_op_limit, 1000000u);
  ASSERT_EQUALS(out.budget.iteration_limit_per_pass, 100u);
}

// A budget whose caps all equal their schema defaults is encoded as a
// present-but-empty sub-table (the scalars are omitted); decode must still read
// each cap back as its default.
TEST(ipc_codec_budget_default_values_roundtrip) {
  ipc::DecompileBudgetV1 budget;  // all fields at their schema defaults
  std::vector<uint8_t> buf = ipc::encode_decompile_request("p", 0x10ULL, 30000u, 0u, &budget);

  ipc::DecompileRequestV1 out;
  ASSERT(ipc::decode_decompile_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.budget.wall_clock_ms, 30000u);
  ASSERT_EQUALS(out.budget.wall_clock_hard_ms, 60000u);
  ASSERT_EQUALS(out.budget.rss_max_mb, 4096u);
  ASSERT_EQUALS(out.budget.pcode_op_limit, 1000000u);
  ASSERT_EQUALS(out.budget.iteration_limit_per_pass, 100u);
}

}  // namespace ghidra
