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
// Rec 34 (#34-4b): compile-and-exercise check for the FlatBuffers IPC C++
// bindings (ghidra::ipc::*). The flatc-generated header and the vendored
// FlatBuffers runtime are inert in production — nothing in the decompiler
// encodes or decodes with them yet — so this test is what proves they stay
// mutually compatible: including the header compiles its FLATBUFFERS_VERSION
// static_assert against the vendored runtime, and the round-trips below pin the
// wire behavior (defaults, scalars, strings, enums) ahead of the dual-encode
// work in #34-4..#34-6. This is the C++ analog of the Java compile-wire (#34-4a).
#include "test.hh"
#include "schema/decompile_generated.h"

namespace ghidra {

// Full round-trip of the nominal root message: build, verify, read back.
TEST(schema_fb_request_roundtrip) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto root = ipc::CreateDecompileFunctionRequestDirect(fbb, "prog-42", 0x401000ULL, 5000u, 0xFu);
  ipc::FinishDecompileFunctionRequestBuffer(fbb, root);

  ::flatbuffers::Verifier verifier(fbb.GetBufferPointer(), fbb.GetSize());
  ASSERT(ipc::VerifyDecompileFunctionRequestBuffer(verifier));

  const ipc::DecompileFunctionRequest *req = ipc::GetDecompileFunctionRequest(fbb.GetBufferPointer());
  ASSERT(req->program_id() != (const ::flatbuffers::String *)0);
  ASSERT_EQUALS(req->program_id()->str(), std::string("prog-42"));
  ASSERT_EQUALS(req->function_address(), 0x401000ULL);
  ASSERT_EQUALS(req->timeout_ms(), 5000u);
  ASSERT_EQUALS(req->flags(), 0xFu);
}

// Unset fields must read back as the schema-declared defaults, and an unset
// string must read back as null — the contract the dual-encode path relies on.
TEST(schema_fb_request_defaults) {
  ::flatbuffers::FlatBufferBuilder fbb;
  ipc::DecompileFunctionRequestBuilder b(fbb);
  b.add_function_address(0x10ULL);
  ipc::FinishDecompileFunctionRequestBuffer(fbb, b.Finish());

  const ipc::DecompileFunctionRequest *req = ipc::GetDecompileFunctionRequest(fbb.GetBufferPointer());
  ASSERT_EQUALS(req->function_address(), 0x10ULL);
  ASSERT_EQUALS(req->timeout_ms(), 30000u);  // schema default
  ASSERT_EQUALS(req->flags(), 0u);           // schema default
  ASSERT(req->program_id() == (const ::flatbuffers::String *)0);
}

// Enum + string + scalar on a non-root table, rooted manually.
TEST(schema_fb_enum_roundtrip) {
  ::flatbuffers::FlatBufferBuilder fbb;
  auto diag = ipc::CreateDiagnosticDirect(fbb, ipc::Severity_ERROR, "boom", 7u);
  fbb.Finish(diag);

  const ipc::Diagnostic *d = ::flatbuffers::GetRoot<ipc::Diagnostic>(fbb.GetBufferPointer());
  ASSERT_EQUALS((int)d->severity(), (int)ipc::Severity_ERROR);
  ASSERT_EQUALS(d->message()->str(), std::string("boom"));
  ASSERT_EQUALS(d->pcode_seq(), 7u);
}

}  // namespace ghidra
