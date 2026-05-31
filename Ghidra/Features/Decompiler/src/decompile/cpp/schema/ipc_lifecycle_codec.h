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
// Rec 34 (#34-6a): worker-side codecs for the v1 (FlatBuffers) program-lifecycle
// commands — RegisterProgram, DeregisterProgram, and FlushNative. These are the
// non-DecompileAt counterparts to schema/ipc_request_codec.h and
// schema/ipc_response_codec.h, migrated command-by-command per #34-6.
//
// In the protocol the host writes requests and the worker reads them, then the
// worker writes responses and the host reads them. So on the worker side the
// production directions are decode_*_request() and encode_*_response(); the
// opposite directions (encode_*_request / decode_*_response) are the host's and
// are provided here only so each command round-trips in isolation under the unit
// tests (see ../unittests/testipc_lifecycle_codec.cc).
//
// None of these tables is the schema root_type (only DecompileFunctionRequest
// is), so — like the response codec — the generic FlatBufferBuilder::Finish /
// GetRoot / Verifier::VerifyBuffer API roots them rather than the per-table
// Finish*/Get*/Verify* helpers flatc emits only for the root. Every decode
// verifies before reading and returns false (leaving its out-param untouched) on
// a null or unverifiable payload, so the worker can never read through a
// malformed v1 frame.
//
// Like the rest of #34-4..#34-6 this header is inert: nothing in the production
// decompiler includes it. The command-loop wiring that would dispatch on a Rec
// 33 frame's command id and call these is the end-to-end-only change deferred
// per DD-0005.
#ifndef GHIDRA_IPC_LIFECYCLE_CODEC_H
#define GHIDRA_IPC_LIFECYCLE_CODEC_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "schema/decompile_generated.h"

namespace ghidra {
namespace ipc {

// --------------------------------------------------------- RegisterProgram
// Native view of a register-program request: the four spec documents the legacy
// protocol carries as opaque XML, kept as strings (see decompile.fbs). An unset
// field reads back as empty.
struct RegisterProgramRequestV1 {
  std::string processor_spec;
  std::string compiler_spec;
  std::string translate_spec;
  std::string core_types_spec;
};

// Native view of a register-program response: the assigned program id.
struct RegisterProgramResponseV1 {
  std::string program_id;
};

inline std::vector<uint8_t> encode_register_program_request(const RegisterProgramRequestV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateRegisterProgramRequestDirect(fbb, in.processor_spec.c_str(),
                                                 in.compiler_spec.c_str(),
                                                 in.translate_spec.c_str(),
                                                 in.core_types_spec.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_register_program_request(const uint8_t *buf, size_t len,
                                            RegisterProgramRequestV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<RegisterProgramRequest>(nullptr)) {
    return false;
  }
  const RegisterProgramRequest *req = ::flatbuffers::GetRoot<RegisterProgramRequest>(buf);
  out.processor_spec = (req->processor_spec() != nullptr) ? req->processor_spec()->str() : std::string();
  out.compiler_spec = (req->compiler_spec() != nullptr) ? req->compiler_spec()->str() : std::string();
  out.translate_spec = (req->translate_spec() != nullptr) ? req->translate_spec()->str() : std::string();
  out.core_types_spec = (req->core_types_spec() != nullptr) ? req->core_types_spec()->str() : std::string();
  return true;
}

inline std::vector<uint8_t> encode_register_program_response(const RegisterProgramResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateRegisterProgramResponseDirect(fbb, in.program_id.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_register_program_response(const uint8_t *buf, size_t len,
                                             RegisterProgramResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<RegisterProgramResponse>(nullptr)) {
    return false;
  }
  const RegisterProgramResponse *resp = ::flatbuffers::GetRoot<RegisterProgramResponse>(buf);
  out.program_id = (resp->program_id() != nullptr) ? resp->program_id()->str() : std::string();
  return true;
}

// ------------------------------------------------------- DeregisterProgram
// Native view of a deregister-program request: the program id to free. The
// response carries the worker meta-command (1 = terminate).
struct DeregisterProgramRequestV1 {
  std::string program_id;
};

struct DeregisterProgramResponseV1 {
  int32_t meta_command = 0;
};

inline std::vector<uint8_t> encode_deregister_program_request(
    const DeregisterProgramRequestV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateDeregisterProgramRequestDirect(fbb, in.program_id.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_deregister_program_request(const uint8_t *buf, size_t len,
                                              DeregisterProgramRequestV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<DeregisterProgramRequest>(nullptr)) {
    return false;
  }
  const DeregisterProgramRequest *req = ::flatbuffers::GetRoot<DeregisterProgramRequest>(buf);
  out.program_id = (req->program_id() != nullptr) ? req->program_id()->str() : std::string();
  return true;
}

inline std::vector<uint8_t> encode_deregister_program_response(
    const DeregisterProgramResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateDeregisterProgramResponse(fbb, in.meta_command);
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_deregister_program_response(const uint8_t *buf, size_t len,
                                               DeregisterProgramResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<DeregisterProgramResponse>(nullptr)) {
    return false;
  }
  const DeregisterProgramResponse *resp =
      ::flatbuffers::GetRoot<DeregisterProgramResponse>(buf);
  out.meta_command = resp->meta_command();
  return true;
}

// ------------------------------------------------------------- FlushNative
// Native view of a flush-native request: the program id whose cached
// Symbol/Scope/Datatype/Funcdata the worker should clear. The response carries
// the result code (0 = success).
struct FlushNativeRequestV1 {
  std::string program_id;
};

struct FlushNativeResponseV1 {
  int32_t result = 0;
};

inline std::vector<uint8_t> encode_flush_native_request(const FlushNativeRequestV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateFlushNativeRequestDirect(fbb, in.program_id.c_str());
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_flush_native_request(const uint8_t *buf, size_t len,
                                        FlushNativeRequestV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<FlushNativeRequest>(nullptr)) {
    return false;
  }
  const FlushNativeRequest *req = ::flatbuffers::GetRoot<FlushNativeRequest>(buf);
  out.program_id = (req->program_id() != nullptr) ? req->program_id()->str() : std::string();
  return true;
}

inline std::vector<uint8_t> encode_flush_native_response(const FlushNativeResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = CreateFlushNativeResponse(fbb, in.result);
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

inline bool decode_flush_native_response(const uint8_t *buf, size_t len,
                                         FlushNativeResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<FlushNativeResponse>(nullptr)) {
    return false;
  }
  const FlushNativeResponse *resp = ::flatbuffers::GetRoot<FlushNativeResponse>(buf);
  out.result = resp->result();
  return true;
}

}  // namespace ipc
}  // namespace ghidra

#endif  // GHIDRA_IPC_LIFECYCLE_CODEC_H
