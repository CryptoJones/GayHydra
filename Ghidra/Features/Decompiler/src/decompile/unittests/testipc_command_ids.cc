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
// Rec 34 #34-10a (DD-0080): the schema-v1 command-id registry.
//
// The id values are a WIRE CONTRACT shared with the Java mirror
// (ghidra.app.decompiler.ipc.IpcCommandId, pinned by IpcCommandIdTest).
// The pinned-value tests below are the C++ half of that lock — if an id
// changes here without the Java side moving in lock-step, one of the
// two suites goes red.
#include "test.hh"
#include "schema/ipc_command_ids.h"

namespace ghidra {

using ipc_v1::CommandId;

// ------------------------------------------------------------------
// Pinned wire values — the cross-language golden contract.
// ------------------------------------------------------------------

TEST(ipc_command_ids_pinned_values) {
  ASSERT_EQUALS((int)CommandId::INVALID,            0x00);
  ASSERT_EQUALS((int)CommandId::REGISTER_PROGRAM,   0x01);
  ASSERT_EQUALS((int)CommandId::DEREGISTER_PROGRAM, 0x02);
  ASSERT_EQUALS((int)CommandId::FLUSH_NATIVE,       0x03);
  ASSERT_EQUALS((int)CommandId::DECOMPILE_AT,       0x04);
  ASSERT_EQUALS((int)CommandId::STRUCTURE_GRAPH,    0x05);
  ASSERT_EQUALS((int)CommandId::SET_ACTION,         0x06);
  ASSERT_EQUALS((int)CommandId::SET_OPTIONS,        0x07);
}

// ------------------------------------------------------------------
// name -> id covers exactly the seven schematized commands.
// ------------------------------------------------------------------

TEST(ipc_command_ids_name_to_id) {
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("registerProgram"),
                (int)CommandId::REGISTER_PROGRAM);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("deregisterProgram"),
                (int)CommandId::DEREGISTER_PROGRAM);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("flushNative"),
                (int)CommandId::FLUSH_NATIVE);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("decompileAt"),
                (int)CommandId::DECOMPILE_AT);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("structureGraph"),
                (int)CommandId::STRUCTURE_GRAPH);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("setAction"),
                (int)CommandId::SET_ACTION);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("setOptions"),
                (int)CommandId::SET_OPTIONS);
}

// The deliberately un-schematized surface maps to INVALID ("stays v0"),
// as does anything unknown.
TEST(ipc_command_ids_unschematized_names_invalid) {
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("generateSignatures"),
                (int)CommandId::INVALID);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("debugSignatures"),
                (int)CommandId::INVALID);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("getSignatureSettings"),
                (int)CommandId::INVALID);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("setSignatureSettings"),
                (int)CommandId::INVALID);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name(""),
                (int)CommandId::INVALID);
  ASSERT_EQUALS((int)ipc_v1::command_id_for_name("RegisterProgram"),  // case matters
                (int)CommandId::INVALID);
}

// ------------------------------------------------------------------
// id -> name round-trips the registry; unknown bytes yield "".
// ------------------------------------------------------------------

TEST(ipc_command_ids_roundtrip_and_unknown) {
  const char *names[] = { "registerProgram", "deregisterProgram", "flushNative",
                          "decompileAt", "structureGraph", "setAction", "setOptions" };
  for (const char *n : names) {
    CommandId id = ipc_v1::command_id_for_name(n);
    ASSERT(id != CommandId::INVALID);
    ASSERT_EQUALS(ipc_v1::name_for_command_id(id), std::string(n));
  }
  ASSERT_EQUALS(ipc_v1::name_for_command_id(CommandId::INVALID), std::string());
  ASSERT_EQUALS(ipc_v1::name_for_command_id((CommandId)0x7f), std::string());
  ASSERT_EQUALS(ipc_v1::name_for_command_id((CommandId)0xff), std::string());
}

} // namespace ghidra
