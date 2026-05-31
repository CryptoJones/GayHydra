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
// Rec 34 (#34-5a): unit tests for the worker-side v1 response *envelope* codec
// (schema/ipc_response_codec.h). These pin the encode<->decode round-trip of the
// ResponseStatus + Diagnostic list and the malformed-input contract: decode must
// reject an unverifiable buffer rather than read through it. Pure and inert — no
// production code links the codec yet (see the header's note); this test is its
// only caller. The pcode/high_function body is out of scope here and lands with
// #34-5b/#34-5c.
#include "test.hh"
#include "schema/ipc_response_codec.h"

namespace ghidra {

// status and a multi-entry diagnostics list survive a full encode -> decode
// round-trip, each diagnostic field preserved in order.
TEST(ipc_response_roundtrip) {
  ipc::DecompileResponseV1 in;
  in.status = ipc::ResponseStatus_PARTIAL;
  in.diagnostics.push_back({ipc::Severity_WARN, "narrowed type", 7u});
  in.diagnostics.push_back({ipc::Severity_ERROR, "unreachable block", 42u});

  std::vector<uint8_t> buf = ipc::encode_decompile_response(in);
  ASSERT(!buf.empty());

  ipc::DecompileResponseV1 out;
  ASSERT(ipc::decode_decompile_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.status, ipc::ResponseStatus_PARTIAL);
  ASSERT_EQUALS(out.diagnostics.size(), (size_t)2);
  ASSERT_EQUALS(out.diagnostics[0].severity, ipc::Severity_WARN);
  ASSERT_EQUALS(out.diagnostics[0].message, std::string("narrowed type"));
  ASSERT_EQUALS(out.diagnostics[0].pcode_seq, 7u);
  ASSERT_EQUALS(out.diagnostics[1].severity, ipc::Severity_ERROR);
  ASSERT_EQUALS(out.diagnostics[1].message, std::string("unreachable block"));
  ASSERT_EQUALS(out.diagnostics[1].pcode_seq, 42u);
}

// A clean result: OK status encoded equal to its schema default is omitted from
// the buffer, and an empty diagnostics list round-trips as empty (not null,
// not a crash).
TEST(ipc_response_clean_result) {
  ipc::DecompileResponseV1 in;  // status defaults to OK, no diagnostics

  std::vector<uint8_t> buf = ipc::encode_decompile_response(in);
  ASSERT(!buf.empty());

  ipc::DecompileResponseV1 out;
  out.status = ipc::ResponseStatus_TIMEOUT;  // sentinel must be overwritten
  ASSERT(ipc::decode_decompile_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.status, ipc::ResponseStatus_OK);  // schema default, omitted
  ASSERT(out.diagnostics.empty());
}

// A diagnostic with an empty message is a present-but-empty string; it must
// round-trip to an empty string, not get dropped or crash decode.
TEST(ipc_response_empty_message) {
  ipc::DecompileResponseV1 in;
  in.status = ipc::ResponseStatus_ANALYSIS_FAILED;
  in.diagnostics.push_back({ipc::Severity_INFO, "", 0u});

  std::vector<uint8_t> buf = ipc::encode_decompile_response(in);

  ipc::DecompileResponseV1 out;
  ASSERT(ipc::decode_decompile_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.status, ipc::ResponseStatus_ANALYSIS_FAILED);
  ASSERT_EQUALS(out.diagnostics.size(), (size_t)1);
  ASSERT_EQUALS(out.diagnostics[0].severity, ipc::Severity_INFO);
  ASSERT_EQUALS(out.diagnostics[0].message, std::string(""));
}

// A null pointer is rejected without dereference.
TEST(ipc_response_rejects_null) {
  ipc::DecompileResponseV1 out;
  ASSERT(!ipc::decode_decompile_response((const uint8_t *)0, 0, out));
}

// Garbage bytes must fail verification, not be read through. The sentinel status
// proves decode left out untouched on rejection.
TEST(ipc_response_rejects_garbage) {
  uint8_t junk[16];
  for (int i = 0; i < 16; ++i) {
    junk[i] = (uint8_t)(0xD0 + i);
  }
  ipc::DecompileResponseV1 out;
  out.status = ipc::ResponseStatus_PARTIAL;
  ASSERT(!ipc::decode_decompile_response(junk, sizeof(junk), out));
  ASSERT_EQUALS(out.status, ipc::ResponseStatus_PARTIAL);  // untouched on failure
}

// A truncated copy of a valid buffer must also fail verification.
TEST(ipc_response_rejects_truncated) {
  ipc::DecompileResponseV1 in;
  in.status = ipc::ResponseStatus_OK;
  in.diagnostics.push_back({ipc::Severity_WARN, "partial", 1u});
  std::vector<uint8_t> buf = ipc::encode_decompile_response(in);
  ASSERT(buf.size() > 4);

  ipc::DecompileResponseV1 out;
  ASSERT(!ipc::decode_decompile_response(buf.data(), buf.size() / 2, out));
}

}  // namespace ghidra
