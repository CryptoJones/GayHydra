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
// Rec 34 #34-10a (DD-0080): the wire registry of schema-v1 command ids.
//
// A SCHEMA_PAYLOAD frame's body is `[u8 command-id][FlatBuffers bytes]`;
// this header is the single C++ home of the id<->name mapping. The Java
// mirror is ghidra.app.decompiler.ipc.IpcCommandId — the two are locked
// together by pinned-value unit tests on each side
// (../unittests/testipc_command_ids.cc / IpcCommandIdTest.java), the same
// cross-language golden-vector pattern the framing CRC uses.
//
// Ids cover exactly the seven schematized commands (decompile.fbs), in
// the worker's command-registration order (ghidra_process.cc). 0x00 is
// reserved as the invalid/unknown sentinel and never goes on the wire.
// The un-schematized surface (signature commands, the mid-decompile
// callback sub-protocol) has no id by design — it rides v0 regardless
// (DD-0080's carve-out).
//
// Deliberately FlatBuffers-free: unlike its sibling ipc_*_codec.h
// headers this one has no vendored-header dependency, so the live
// command loop can include it without touching the production compile
// rules (those gain FLATBUF_INCLUDE in #34-10b, with the dispatch).

#ifndef __IPC_COMMAND_IDS_H__
#define __IPC_COMMAND_IDS_H__

#include <cstdint>
#include <string>

namespace ghidra {
namespace ipc_v1 {

/// One byte on the wire, leading every SCHEMA_PAYLOAD frame body.
enum class CommandId : uint8_t {
  INVALID            = 0x00,  ///< reserved sentinel; never on the wire
  REGISTER_PROGRAM   = 0x01,
  DEREGISTER_PROGRAM = 0x02,
  FLUSH_NATIVE       = 0x03,
  DECOMPILE_AT       = 0x04,
  STRUCTURE_GRAPH    = 0x05,
  SET_ACTION         = 0x06,
  SET_OPTIONS        = 0x07,
};

/// Map a v0 command name to its schema-v1 id. Returns
/// CommandId::INVALID for a name outside the seven schematized
/// commands (including the deliberately un-schematized signature
/// commands), which a dispatcher must treat as "stays v0".
inline CommandId command_id_for_name(const std::string &name)
{
  if (name == "registerProgram")   return CommandId::REGISTER_PROGRAM;
  if (name == "deregisterProgram") return CommandId::DEREGISTER_PROGRAM;
  if (name == "flushNative")       return CommandId::FLUSH_NATIVE;
  if (name == "decompileAt")       return CommandId::DECOMPILE_AT;
  if (name == "structureGraph")    return CommandId::STRUCTURE_GRAPH;
  if (name == "setAction")         return CommandId::SET_ACTION;
  if (name == "setOptions")        return CommandId::SET_OPTIONS;
  return CommandId::INVALID;
}

/// Map a schema-v1 id back to its v0 command name. Returns the empty
/// string for INVALID or any out-of-registry byte — the receiver's
/// protocol-error signal for an unknown id (DD-0080).
inline std::string name_for_command_id(CommandId id)
{
  switch (id) {
    case CommandId::REGISTER_PROGRAM:   return "registerProgram";
    case CommandId::DEREGISTER_PROGRAM: return "deregisterProgram";
    case CommandId::FLUSH_NATIVE:       return "flushNative";
    case CommandId::DECOMPILE_AT:       return "decompileAt";
    case CommandId::STRUCTURE_GRAPH:    return "structureGraph";
    case CommandId::SET_ACTION:         return "setAction";
    case CommandId::SET_OPTIONS:        return "setOptions";
    default:                            return std::string();
  }
}

} // namespace ipc_v1
} // namespace ghidra

#endif // __IPC_COMMAND_IDS_H__
