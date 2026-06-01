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
// Rec 34 (#34-6b): unit tests for the worker-side v1 configuration/graph codecs
// (schema/ipc_config_codec.h) — StructureGraph, SetAction, and SetOptions. Each
// command pins the encode<->decode round-trip for both its request and its
// response, plus the malformed-input contract: decode must reject a
// null/garbage/truncated buffer rather than read through it, and leave its
// out-param untouched on rejection. Pure and inert — no production code links
// the codec yet (see the header's note); this test is its only caller.
#include "test.hh"
#include "schema/ipc_config_codec.h"

namespace ghidra {

// ---------------------------------------------------------- StructureGraph

// Both the program id and the control-flow document survive a request round-trip.
TEST(ipc_config_structure_request_roundtrip) {
  ipc::StructureGraphRequestV1 in;
  in.program_id = "prog-2";
  in.control_flow = "<block id=\"0\"/>";
  std::vector<uint8_t> buf = ipc::encode_structure_graph_request(in);
  ASSERT(!buf.empty());

  ipc::StructureGraphRequestV1 out;
  ASSERT(ipc::decode_structure_graph_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-2"));
  ASSERT_EQUALS(out.control_flow, std::string("<block id=\"0\"/>"));
}

// The structured document survives a response round-trip.
TEST(ipc_config_structure_response_roundtrip) {
  ipc::StructureGraphResponseV1 in;
  in.structured = "<whiledo/>";
  std::vector<uint8_t> buf = ipc::encode_structure_graph_response(in);

  ipc::StructureGraphResponseV1 out;
  ASSERT(ipc::decode_structure_graph_response(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.structured, std::string("<whiledo/>"));
}

// An unset control-flow field reads back as an empty string, distinct from the
// set program id.
TEST(ipc_config_structure_request_empty_field) {
  ipc::StructureGraphRequestV1 in;
  in.program_id = "prog-2";
  std::vector<uint8_t> buf = ipc::encode_structure_graph_request(in);

  ipc::StructureGraphRequestV1 out;
  ASSERT(ipc::decode_structure_graph_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-2"));
  ASSERT_EQUALS(out.control_flow, std::string(""));
}

// Garbage bytes fail verification and leave out untouched.
TEST(ipc_config_structure_rejects_garbage) {
  uint8_t junk[16];
  for (int i = 0; i < 16; ++i) {
    junk[i] = (uint8_t)(0xC0 + i);
  }
  ipc::StructureGraphRequestV1 out;
  out.program_id = "sentinel";
  ASSERT(!ipc::decode_structure_graph_request(junk, sizeof(junk), out));
  ASSERT_EQUALS(out.program_id, std::string("sentinel"));  // untouched on failure
}

// --------------------------------------------------------------- SetAction

// All three selectors survive a set-action request round-trip.
TEST(ipc_config_setaction_request_roundtrip) {
  ipc::SetActionRequestV1 in;
  in.program_id = "prog-5";
  in.root_action = "decompile";
  in.print_config = "tree";
  std::vector<uint8_t> buf = ipc::encode_set_action_request(in);

  ipc::SetActionRequestV1 out;
  ASSERT(ipc::decode_set_action_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-5"));
  ASSERT_EQUALS(out.root_action, std::string("decompile"));
  ASSERT_EQUALS(out.print_config, std::string("tree"));
}

// success = true round-trips through the response.
TEST(ipc_config_setaction_response_true) {
  ipc::SetActionResponseV1 in;
  in.success = true;
  std::vector<uint8_t> buf = ipc::encode_set_action_response(in);

  ipc::SetActionResponseV1 out;  // default false
  ASSERT(ipc::decode_set_action_response(buf.data(), buf.size(), out));
  ASSERT(out.success);
}

// success = false is the schema default and is omitted from the buffer; decode
// must read it back as false even when out starts true.
TEST(ipc_config_setaction_response_false_default) {
  ipc::SetActionResponseV1 in;  // success defaults to false
  std::vector<uint8_t> buf = ipc::encode_set_action_response(in);

  ipc::SetActionResponseV1 out;
  out.success = true;  // sentinel that decode must overwrite with the default
  ASSERT(ipc::decode_set_action_response(buf.data(), buf.size(), out));
  ASSERT(!out.success);
}

// A null request buffer is rejected without dereference.
TEST(ipc_config_setaction_rejects_null) {
  ipc::SetActionRequestV1 out;
  ASSERT(!ipc::decode_set_action_request((const uint8_t *)0, 0, out));
}

// -------------------------------------------------------------- SetOptions

// The program id and the <optionslist> document survive a request round-trip.
TEST(ipc_config_setoptions_request_roundtrip) {
  ipc::SetOptionsRequestV1 in;
  in.program_id = "prog-8";
  in.options = "<optionslist><currentaction>conditionalexe</currentaction></optionslist>";
  std::vector<uint8_t> buf = ipc::encode_set_options_request(in);

  ipc::SetOptionsRequestV1 out;
  ASSERT(ipc::decode_set_options_request(buf.data(), buf.size(), out));
  ASSERT_EQUALS(out.program_id, std::string("prog-8"));
  ASSERT_EQUALS(out.options,
                std::string("<optionslist><currentaction>conditionalexe</currentaction></optionslist>"));
}

// success = true round-trips through the response.
TEST(ipc_config_setoptions_response_true) {
  ipc::SetOptionsResponseV1 in;
  in.success = true;
  std::vector<uint8_t> buf = ipc::encode_set_options_response(in);

  ipc::SetOptionsResponseV1 out;
  ASSERT(ipc::decode_set_options_response(buf.data(), buf.size(), out));
  ASSERT(out.success);
}

// A null response buffer is rejected without dereference, and a truncated copy
// of a valid request buffer must also fail verification.
TEST(ipc_config_setoptions_rejects_null_and_truncated) {
  ipc::SetOptionsResponseV1 rout;
  ASSERT(!ipc::decode_set_options_response((const uint8_t *)0, 0, rout));

  ipc::SetOptionsRequestV1 in;
  in.program_id = "prog-8";
  in.options = "<optionslist/>";
  std::vector<uint8_t> buf = ipc::encode_set_options_request(in);
  ASSERT(buf.size() > 4);
  ipc::SetOptionsRequestV1 out;
  ASSERT(!ipc::decode_set_options_request(buf.data(), buf.size() / 2, out));
}

}  // namespace ghidra
