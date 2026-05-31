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

// A p-code op carrying an output varnode and several inputs survives a full
// round-trip, every varnode field and the op's opcode/sequence preserved.
TEST(ipc_response_pcode_roundtrip) {
  ipc::DecompileResponseV1 in;
  in.status = ipc::ResponseStatus_OK;
  ipc::PcodeOpV1 op;
  op.opcode = 0x21;  // some CPUI opcode
  op.sequence_number = 99u;
  op.has_output = true;
  op.output = {1u, 0x4010ULL, 8u};
  op.inputs.push_back({2u, 0x7fffULL, 4u});
  op.inputs.push_back({0u, 0x1ULL, 1u});
  in.pcode.push_back(op);

  std::vector<uint8_t> buf = ipc::encode_decompile_response(in);
  ipc::DecompileResponseV1 out;
  ASSERT(ipc::decode_decompile_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.pcode.size(), (size_t)1);
  const ipc::PcodeOpV1 &o = out.pcode[0];
  ASSERT_EQUALS(o.opcode, (uint16_t)0x21);
  ASSERT_EQUALS(o.sequence_number, 99u);
  ASSERT(o.has_output);
  ASSERT_EQUALS(o.output.address_space, (uint8_t)1);
  ASSERT_EQUALS(o.output.offset, 0x4010ULL);
  ASSERT_EQUALS(o.output.size, 8u);
  ASSERT_EQUALS(o.inputs.size(), (size_t)2);
  ASSERT_EQUALS(o.inputs[0].address_space, (uint8_t)2);
  ASSERT_EQUALS(o.inputs[0].offset, 0x7fffULL);
  ASSERT_EQUALS(o.inputs[0].size, 4u);
  ASSERT_EQUALS(o.inputs[1].offset, 0x1ULL);
}

// An op with no output varnode (e.g. a STORE/BRANCH) leaves the output table
// unset on the wire; decode must read it back as has_output == false, not as a
// zero-valued varnode.
TEST(ipc_response_pcode_no_output) {
  ipc::DecompileResponseV1 in;
  ipc::PcodeOpV1 op;
  op.opcode = 0x9;
  op.has_output = false;
  op.inputs.push_back({3u, 0x2000ULL, 8u});
  in.pcode.push_back(op);

  std::vector<uint8_t> buf = ipc::encode_decompile_response(in);
  ipc::DecompileResponseV1 out;
  ASSERT(ipc::decode_decompile_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.pcode.size(), (size_t)1);
  ASSERT(!out.pcode[0].has_output);
  ASSERT_EQUALS(out.pcode[0].inputs.size(), (size_t)1);
  ASSERT_EQUALS(out.pcode[0].inputs[0].address_space, (uint8_t)3);
}

// Multiple ops, one with empty inputs, round-trip in order; an empty inputs
// list reads back empty rather than collapsing the op.
TEST(ipc_response_pcode_multi_and_empty_inputs) {
  ipc::DecompileResponseV1 in;
  ipc::PcodeOpV1 a;
  a.opcode = 0x1;
  a.has_output = true;
  a.output = {1u, 0x10ULL, 4u};  // no inputs
  ipc::PcodeOpV1 b;
  b.opcode = 0x2;
  b.sequence_number = 5u;
  b.inputs.push_back({1u, 0x20ULL, 4u});
  in.pcode.push_back(a);
  in.pcode.push_back(b);

  std::vector<uint8_t> buf = ipc::encode_decompile_response(in);
  ipc::DecompileResponseV1 out;
  ASSERT(ipc::decode_decompile_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.pcode.size(), (size_t)2);
  ASSERT_EQUALS(out.pcode[0].opcode, (uint16_t)0x1);
  ASSERT(out.pcode[0].has_output);
  ASSERT(out.pcode[0].inputs.empty());
  ASSERT_EQUALS(out.pcode[1].opcode, (uint16_t)0x2);
  ASSERT_EQUALS(out.pcode[1].sequence_number, 5u);
  ASSERT(!out.pcode[1].has_output);
  ASSERT_EQUALS(out.pcode[1].inputs.size(), (size_t)1);
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
