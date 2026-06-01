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
// Rec 34 (#34-6b): worker-side codecs for the v1 (FlatBuffers) configuration and
// graph commands — StructureGraph, SetAction, and SetOptions. These are the
// remaining three of the six non-DecompileAt commands migrated by #34-6; the
// program-lifecycle trio is in schema/ipc_lifecycle_codec.h (#34-6a).
//
// In the protocol the host writes requests and the worker reads them, then the
// worker writes responses and the host reads them. So on the worker side the
// production directions are decode_*_request() and encode_*_response(); the
// opposite directions (encode_*_request / decode_*_response) are the host's and
// are provided here only so each command round-trips in isolation under the unit
// tests (see ../unittests/testipc_config_codec.cc).
//
// The StructureGraph control-flow document, the SetAction root_action /
// print_config selectors, and the SetOptions <optionslist> are all carried as
// opaque strings to match the legacy wire (see decompile.fbs); structuring them
// is out of scope. None of these tables is the schema root_type (only
// DecompileFunctionRequest is), so — like the response codec — the generic
// FlatBufferBuilder::Finish / GetRoot / Verifier::VerifyBuffer API roots them.
// Every decode verifies before reading and returns false (leaving its out-param
// untouched) on a null or unverifiable payload.
//
// Like the rest of #34-4..#34-6 this header is inert: nothing in the production
// decompiler includes it. The command-loop wiring that would dispatch on a Rec
// 33 frame's command id and call these is the end-to-end-only change deferred
// per DD-0005.
#ifndef GHIDRA_IPC_CONFIG_CODEC_H
#define GHIDRA_IPC_CONFIG_CODEC_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "schema/decompile_generated.h"

namespace ghidra {
namespace ipc {

// ---------------------------------------------------------- StructureGraph
// Native view of a structure-graph request: the program id and the encoded
// <block> control-flow document. The response carries the structured document.
// Both XML payloads are opaque strings; an unset field reads back as empty.
struct StructureGraphRequestV1 {
  std::string program_id;
  std::string control_flow;
};

struct StructureGraphResponseV1 {
  std::string structured;
};

inline std::vector<uint8_t> encode_structure_graph_request(const StructureGraphRequestV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateStructureGraphRequestDirect(fbb, in.program_id.c_str(),
                                                in.control_flow.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_structure_graph_request(const uint8_t *buf, size_t len,
                                           StructureGraphRequestV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<StructureGraphRequest>(nullptr)) {
    return false;
  }
  const StructureGraphRequest *req = ::flatbuffers::GetRoot<StructureGraphRequest>(buf);
  out.program_id = (req->program_id() != nullptr) ? req->program_id()->str() : std::string();
  out.control_flow = (req->control_flow() != nullptr) ? req->control_flow()->str() : std::string();
  return true;
}

inline std::vector<uint8_t> encode_structure_graph_response(const StructureGraphResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateStructureGraphResponseDirect(fbb, in.structured.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_structure_graph_response(const uint8_t *buf, size_t len,
                                            StructureGraphResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<StructureGraphResponse>(nullptr)) {
    return false;
  }
  const StructureGraphResponse *resp = ::flatbuffers::GetRoot<StructureGraphResponse>(buf);
  out.structured = (resp->structured() != nullptr) ? resp->structured()->str() : std::string();
  return true;
}

// --------------------------------------------------------------- SetAction
// Native view of a set-action request: the program id, the root action
// selector, and the print-config selector. An empty selector leaves that
// setting unchanged on the worker (legacy semantics). The response is the
// legacy 't'/'f' success flag.
struct SetActionRequestV1 {
  std::string program_id;
  std::string root_action;
  std::string print_config;
};

struct SetActionResponseV1 {
  bool success = false;
};

inline std::vector<uint8_t> encode_set_action_request(const SetActionRequestV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateSetActionRequestDirect(fbb, in.program_id.c_str(), in.root_action.c_str(),
                                           in.print_config.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_set_action_request(const uint8_t *buf, size_t len, SetActionRequestV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<SetActionRequest>(nullptr)) {
    return false;
  }
  const SetActionRequest *req = ::flatbuffers::GetRoot<SetActionRequest>(buf);
  out.program_id = (req->program_id() != nullptr) ? req->program_id()->str() : std::string();
  out.root_action = (req->root_action() != nullptr) ? req->root_action()->str() : std::string();
  out.print_config = (req->print_config() != nullptr) ? req->print_config()->str() : std::string();
  return true;
}

inline std::vector<uint8_t> encode_set_action_response(const SetActionResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateSetActionResponse(fbb, in.success);
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_set_action_response(const uint8_t *buf, size_t len, SetActionResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<SetActionResponse>(nullptr)) {
    return false;
  }
  const SetActionResponse *resp = ::flatbuffers::GetRoot<SetActionResponse>(buf);
  out.success = resp->success();
  return true;
}

// -------------------------------------------------------------- SetOptions
// Native view of a set-options request: the program id and the encoded
// <optionslist> document (opaque string). The response is the legacy 't'/'f'
// success flag.
struct SetOptionsRequestV1 {
  std::string program_id;
  std::string options;
};

struct SetOptionsResponseV1 {
  bool success = false;
};

inline std::vector<uint8_t> encode_set_options_request(const SetOptionsRequestV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateSetOptionsRequestDirect(fbb, in.program_id.c_str(), in.options.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_set_options_request(const uint8_t *buf, size_t len, SetOptionsRequestV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<SetOptionsRequest>(nullptr)) {
    return false;
  }
  const SetOptionsRequest *req = ::flatbuffers::GetRoot<SetOptionsRequest>(buf);
  out.program_id = (req->program_id() != nullptr) ? req->program_id()->str() : std::string();
  out.options = (req->options() != nullptr) ? req->options()->str() : std::string();
  return true;
}

inline std::vector<uint8_t> encode_set_options_response(const SetOptionsResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateSetOptionsResponse(fbb, in.success);
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_set_options_response(const uint8_t *buf, size_t len, SetOptionsResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<SetOptionsResponse>(nullptr)) {
    return false;
  }
  const SetOptionsResponse *resp = ::flatbuffers::GetRoot<SetOptionsResponse>(buf);
  out.success = resp->success();
  return true;
}

}  // namespace ipc
}  // namespace ghidra

#endif  // GHIDRA_IPC_CONFIG_CODEC_H
