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
// Rec 34 (#34-6a): unit tests for the worker-side v1 program-lifecycle codecs
// (schema/ipc_lifecycle_codec.h) — RegisterProgram, DeregisterProgram, and
// FlushNative. Each command pins the encode<->decode round-trip for both its
// request and its response, plus the malformed-input contract: decode must
// reject a null/garbage/truncated buffer rather than read through it, and leave
// its out-param untouched on rejection. Pure and inert — no production code
// links the codec yet (see the header's note); this test is its only caller.
#include "test.hh"
#include "schema/ipc_lifecycle_codec.h"

namespace ghidra {

// --------------------------------------------------------- RegisterProgram

// All four spec documents survive a full request encode -> decode round-trip.
TEST(ipc_lifecycle_register_request_roundtrip) {
  ipc::RegisterProgramRequestV1 in;
  in.processor_spec = "<pspec/>";
  in.compiler_spec = "<cspec/>";
  in.translate_spec = "<sleigh/>";
  in.core_types_spec = "<coretypes/>";
  std::vector<uint8_t> buf = ipc::encode_register_program_request(in);
  ASSERT(!buf.empty());

  ipc::RegisterProgramRequestV1 out;
  ASSERT(ipc::decode_register_program_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.processor_spec, std::string("<pspec/>"));
  ASSERT_EQUALS(out.compiler_spec, std::string("<cspec/>"));
  ASSERT_EQUALS(out.translate_spec, std::string("<sleigh/>"));
  ASSERT_EQUALS(out.core_types_spec, std::string("<coretypes/>"));
}

// Unset spec fields are present-but-absent on the wire; decode reads them back
// as empty strings, not as a crash or stale value.
TEST(ipc_lifecycle_register_request_empty) {
  ipc::RegisterProgramRequestV1 in;
  in.processor_spec = "only-this-one";
  std::vector<uint8_t> buf = ipc::encode_register_program_request(in);

  ipc::RegisterProgramRequestV1 out;
  ASSERT(ipc::decode_register_program_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.processor_spec, std::string("only-this-one"));
  ASSERT_EQUALS(out.compiler_spec, std::string(""));
  ASSERT_EQUALS(out.translate_spec, std::string(""));
  ASSERT_EQUALS(out.core_types_spec, std::string(""));
}

// The assigned program id survives a response round-trip.
TEST(ipc_lifecycle_register_response_roundtrip) {
  ipc::RegisterProgramResponseV1 in;
  in.program_id = "prog-7";
  std::vector<uint8_t> buf = ipc::encode_register_program_response(in);

  ipc::RegisterProgramResponseV1 out;
  ASSERT(ipc::decode_register_program_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-7"));
}

// A null request buffer is rejected without dereference.
TEST(ipc_lifecycle_register_rejects_null) {
  ipc::RegisterProgramRequestV1 out;
  ASSERT(!ipc::decode_register_program_request((const uint8_t *)0, 0, out));
}

// Garbage bytes fail verification and leave out untouched.
TEST(ipc_lifecycle_register_rejects_garbage) {
  uint8_t junk[16];
  for (int i = 0; i < 16; ++i) {
    junk[i] = (uint8_t)(0xA0 + i);
  }
  ipc::RegisterProgramRequestV1 out;
  out.processor_spec = "sentinel";
  ASSERT(!ipc::decode_register_program_request(junk, sizeof(junk), out));
  ASSERT_EQUALS(out.processor_spec, std::string("sentinel"));  // untouched on failure
}

// ------------------------------------------------------- DeregisterProgram

// The program id survives a deregister request round-trip.
TEST(ipc_lifecycle_deregister_request_roundtrip) {
  ipc::DeregisterProgramRequestV1 in;
  in.program_id = "prog-9";
  std::vector<uint8_t> buf = ipc::encode_deregister_program_request(in);

  ipc::DeregisterProgramRequestV1 out;
  ASSERT(ipc::decode_deregister_program_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-9"));
}

// A non-default meta-command (1 = terminate) round-trips through the response.
TEST(ipc_lifecycle_deregister_response_roundtrip) {
  ipc::DeregisterProgramResponseV1 in;
  in.meta_command = 1;
  std::vector<uint8_t> buf = ipc::encode_deregister_program_response(in);

  ipc::DeregisterProgramResponseV1 out;
  ASSERT(ipc::decode_deregister_program_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.meta_command, 1);
}

// meta_command encoded equal to its schema default (0) is omitted from the
// buffer; decode must still read it back as 0.
TEST(ipc_lifecycle_deregister_response_default) {
  ipc::DeregisterProgramResponseV1 in;  // meta_command defaults to 0
  std::vector<uint8_t> buf = ipc::encode_deregister_program_response(in);

  ipc::DeregisterProgramResponseV1 out;
  out.meta_command = 99;  // sentinel that decode must overwrite with the default
  ASSERT(ipc::decode_deregister_program_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.meta_command, 0);
}

// Garbage bytes fail verification and leave out untouched.
TEST(ipc_lifecycle_deregister_rejects_garbage) {
  uint8_t junk[16];
  for (int i = 0; i < 16; ++i) {
    junk[i] = (uint8_t)(0xB0 + i);
  }
  ipc::DeregisterProgramResponseV1 out;
  out.meta_command = 0x7EAD;
  ASSERT(!ipc::decode_deregister_program_response(junk, sizeof(junk), out));
  ASSERT_EQUALS(out.meta_command, 0x7EAD);  // untouched on failure
}

// ------------------------------------------------------------- FlushNative

// The program id survives a flush-native request round-trip.
TEST(ipc_lifecycle_flush_request_roundtrip) {
  ipc::FlushNativeRequestV1 in;
  in.program_id = "prog-3";
  std::vector<uint8_t> buf = ipc::encode_flush_native_request(in);

  ipc::FlushNativeRequestV1 out;
  ASSERT(ipc::decode_flush_native_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-3"));
}

// A non-default result code round-trips through the response.
TEST(ipc_lifecycle_flush_response_roundtrip) {
  ipc::FlushNativeResponseV1 in;
  in.result = -1;
  std::vector<uint8_t> buf = ipc::encode_flush_native_response(in);

  ipc::FlushNativeResponseV1 out;
  ASSERT(ipc::decode_flush_native_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.result, -1);
}

// A null response buffer is rejected without dereference, and a truncated copy
// of a valid request buffer must also fail verification.
TEST(ipc_lifecycle_flush_rejects_null_and_truncated) {
  ipc::FlushNativeResponseV1 rout;
  ASSERT(!ipc::decode_flush_native_response((const uint8_t *)0, 0, rout));

  ipc::FlushNativeRequestV1 in;
  in.program_id = "prog-3";
  std::vector<uint8_t> buf = ipc::encode_flush_native_request(in);
  ASSERT(buf.size() > 4);
  ipc::FlushNativeRequestV1 out;
  ASSERT(!ipc::decode_flush_native_request(buf.data(), buf.size() / 2, out));
}

}  // namespace ghidra
