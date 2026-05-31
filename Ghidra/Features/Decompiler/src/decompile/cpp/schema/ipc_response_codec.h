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
// Rec 34 (#34-5a/#34-5b/#34-5c): worker-side codec for the v1 (FlatBuffers)
// decompile-function response. This is the response-path analogue of
// schema/ipc_request_codec.h, with the direction flipped: in the protocol the
// worker writes the response and the host reads it, so
// encode_decompile_response() is the worker's production direction.
// decode_decompile_response() mirrors the host's read and pins the same
// verify-before-read contract; it is exercised only by the round-trip and
// malformed-input tests (see ../unittests/testipc_response_codec.cc).
//
// Scope grew additively across the response increments, which FlatBuffers'
// forward-compatible layout made painless — each step extended
// DecompileResponseV1 and the encode/decode without rewriting the prior one:
//   #34-5a  the envelope: ResponseStatus + the Diagnostic list.
//   #34-5b  the pcode body: the PcodeOp array, each carrying an optional output
//           Varnode and an inputs Varnode list.
//   #34-5c  the HighFunction tree: the function's optional return-type DataType
//           and its parameter/local HighSymbol lists, each symbol carrying an
//           optional DataType and Storage (this increment, completing the
//           DecompileFunctionResponse codec).
//
// Every nested table the schema marks optional is mirrored by a has_* flag on
// the native view so encode/decode distinguish "absent" from a zero-valued
// table rather than fabricating one.
//
// Like the request codec this header is inert: nothing in the production
// decompiler includes it. The command-loop wiring that would call it on a real
// decompile result is the separate, end-to-end-only change tracked by DD-0005.
#ifndef GHIDRA_IPC_RESPONSE_CODEC_H
#define GHIDRA_IPC_RESPONSE_CODEC_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "schema/decompile_generated.h"

namespace ghidra {
namespace ipc {

// Native view of one diagnostic. Defaults mirror the schema (decompile.fbs):
// Severity's zero value is INFO and pcode_seq defaults to 0.
struct DiagnosticV1 {
  Severity severity = Severity_INFO;
  std::string message;
  uint32_t pcode_seq = 0;
};

// Native view of a p-code varnode (a leaf table: no further nesting).
struct VarnodeV1 {
  uint8_t address_space = 0;
  uint64_t offset = 0;
  uint32_t size = 0;
};

// Native view of a single p-code operation. The schema's output is an optional
// table, so has_output distinguishes "no output varnode" from a zero-valued
// one; output is meaningful only when has_output is true.
struct PcodeOpV1 {
  uint16_t opcode = 0;
  bool has_output = false;
  VarnodeV1 output;
  std::vector<VarnodeV1> inputs;
  uint32_t sequence_number = 0;
};

// Native view of a datatype (a leaf table). kind defaults to VOID, the schema's
// zero value.
struct DataTypeV1 {
  std::string name;
  uint32_t size = 0;
  DataTypeKind kind = DataTypeKind_VOID;
};

// Native view of a symbol's storage location (a leaf table). kind defaults to
// REGISTER, the schema's zero value.
struct StorageV1 {
  StorageKind kind = StorageKind_REGISTER;
  uint64_t address = 0;
  uint8_t space = 0;
};

// Native view of a high-level symbol. Both its datatype and its storage are
// optional tables in the schema, so has_type / has_storage gate them.
struct HighSymbolV1 {
  std::string name;
  bool has_type = false;
  DataTypeV1 type;
  bool has_storage = false;
  StorageV1 storage;
};

// Native view of a decompiled function's high-level interface: its name, an
// optional return-type datatype, and its parameter/local symbol lists.
struct HighFunctionV1 {
  std::string name;
  bool has_return_type = false;
  DataTypeV1 return_type;
  std::vector<HighSymbolV1> parameters;
  std::vector<HighSymbolV1> locals;
};

// Native view of the decompile-function response, decoded out of a v1 payload.
// status defaults to OK (the schema's zero value); diagnostics is empty for a
// clean result; pcode is the operation list; high_function carries the recovered
// function interface and is gated by has_high_function (the schema table is
// optional).
struct DecompileResponseV1 {
  ResponseStatus status = ResponseStatus_OK;
  std::vector<PcodeOpV1> pcode;
  bool has_high_function = false;
  HighFunctionV1 high_function;
  std::vector<DiagnosticV1> diagnostics;
};

// --- internal build/read helpers for the HighFunction tree ---------------
// HighSymbol appears in both the parameter and local lists, so its encode/decode
// is factored out rather than duplicated.

inline ::flatbuffers::Offset<DataType> build_data_type(::flatbuffers::FlatBufferBuilder &fbb,
                                                       const DataTypeV1 &dt) {
  return CreateDataTypeDirect(fbb, dt.name.c_str(), dt.size, dt.kind);
}

inline ::flatbuffers::Offset<HighSymbol> build_high_symbol(::flatbuffers::FlatBufferBuilder &fbb,
                                                           const HighSymbolV1 &sym) {
  ::flatbuffers::Offset<DataType> type_off = sym.has_type ? build_data_type(fbb, sym.type) : 0;
  ::flatbuffers::Offset<Storage> storage_off =
      sym.has_storage ? CreateStorage(fbb, sym.storage.kind, sym.storage.address, sym.storage.space)
                      : 0;
  return CreateHighSymbolDirect(fbb, sym.name.c_str(), type_off, storage_off);
}

inline void read_data_type(const DataType *dt, bool &has_out, DataTypeV1 &out) {
  if (dt == nullptr) {
    has_out = false;
    return;
  }
  has_out = true;
  out.name = (dt->name() != nullptr) ? dt->name()->str() : std::string();
  out.size = dt->size();
  out.kind = dt->kind();
}

inline void read_high_symbol(const HighSymbol *s, HighSymbolV1 &out) {
  out.name = (s->name() != nullptr) ? s->name()->str() : std::string();
  read_data_type(s->type(), out.has_type, out.type);
  const Storage *st = s->storage();
  if (st != nullptr) {
    out.has_storage = true;
    out.storage.kind = st->kind();
    out.storage.address = st->address();
    out.storage.space = st->space();
  }
}

// Encode a response envelope as a finished, root-typed FlatBuffers payload
// (schema v1), returning the buffer bytes ready to ride inside a Rec 33 frame's
// payload range. DecompileFunctionResponse is not the schema root_type, so the
// generic FlatBufferBuilder::Finish is used to root it. The returned vector owns
// a copy of the builder's bytes.
inline std::vector<uint8_t> encode_decompile_response(const DecompileResponseV1 &in) {
  ::flatbuffers::FlatBufferBuilder fbb;
  std::vector<::flatbuffers::Offset<PcodeOp>> pcode_offsets;
  pcode_offsets.reserve(in.pcode.size());
  for (const PcodeOpV1 &op : in.pcode) {
    std::vector<::flatbuffers::Offset<Varnode>> input_offsets;
    input_offsets.reserve(op.inputs.size());
    for (const VarnodeV1 &v : op.inputs) {
      input_offsets.push_back(CreateVarnode(fbb, v.address_space, v.offset, v.size));
    }
    ::flatbuffers::Offset<Varnode> output_offset = 0;
    if (op.has_output) {
      output_offset =
          CreateVarnode(fbb, op.output.address_space, op.output.offset, op.output.size);
    }
    pcode_offsets.push_back(CreatePcodeOpDirect(fbb, op.opcode, output_offset, &input_offsets,
                                                op.sequence_number));
  }
  ::flatbuffers::Offset<HighFunction> hf_offset = 0;
  if (in.has_high_function) {
    const HighFunctionV1 &hf = in.high_function;
    ::flatbuffers::Offset<DataType> ret_offset =
        hf.has_return_type ? build_data_type(fbb, hf.return_type) : 0;
    std::vector<::flatbuffers::Offset<HighSymbol>> param_offsets;
    param_offsets.reserve(hf.parameters.size());
    for (const HighSymbolV1 &s : hf.parameters) {
      param_offsets.push_back(build_high_symbol(fbb, s));
    }
    std::vector<::flatbuffers::Offset<HighSymbol>> local_offsets;
    local_offsets.reserve(hf.locals.size());
    for (const HighSymbolV1 &s : hf.locals) {
      local_offsets.push_back(build_high_symbol(fbb, s));
    }
    hf_offset =
        CreateHighFunctionDirect(fbb, hf.name.c_str(), ret_offset, &param_offsets, &local_offsets);
  }
  std::vector<::flatbuffers::Offset<Diagnostic>> diag_offsets;
  diag_offsets.reserve(in.diagnostics.size());
  for (const DiagnosticV1 &d : in.diagnostics) {
    diag_offsets.push_back(
        CreateDiagnosticDirect(fbb, d.severity, d.message.c_str(), d.pcode_seq));
  }
  auto root = CreateDecompileFunctionResponseDirect(fbb, in.status, &pcode_offsets, hf_offset,
                                                    &diag_offsets);
  fbb.Finish(root);
  const uint8_t *p = fbb.GetBufferPointer();
  return std::vector<uint8_t>(p, p + fbb.GetSize());
}

// Verify, then decode, a v1 response envelope. Returns false (leaving out
// untouched) when buf is null or the bytes fail FlatBuffers verification — a
// reader must treat an unverifiable payload as a protocol error and never read
// through it. On success, out.status and out.diagnostics are fully populated;
// an unset diagnostic message reads back as empty.
inline bool decode_decompile_response(const uint8_t *buf, size_t len, DecompileResponseV1 &out) {
  if (buf == nullptr) {
    return false;
  }
  ::flatbuffers::Verifier verifier(buf, len);
  if (!verifier.VerifyBuffer<DecompileFunctionResponse>(nullptr)) {
    return false;
  }
  const DecompileFunctionResponse *resp =
      ::flatbuffers::GetRoot<DecompileFunctionResponse>(buf);
  out.status = resp->status();
  out.pcode.clear();
  if (resp->pcode() != nullptr) {
    const auto *ops = resp->pcode();
    out.pcode.reserve(ops->size());
    for (::flatbuffers::uoffset_t i = 0; i < ops->size(); ++i) {
      const PcodeOp *op = ops->Get(i);
      PcodeOpV1 nop;
      nop.opcode = op->opcode();
      nop.sequence_number = op->sequence_number();
      const Varnode *outv = op->output();
      if (outv != nullptr) {
        nop.has_output = true;
        nop.output.address_space = outv->address_space();
        nop.output.offset = outv->offset();
        nop.output.size = outv->size();
      }
      if (op->inputs() != nullptr) {
        const auto *ins = op->inputs();
        nop.inputs.reserve(ins->size());
        for (::flatbuffers::uoffset_t j = 0; j < ins->size(); ++j) {
          const Varnode *v = ins->Get(j);
          VarnodeV1 nv;
          nv.address_space = v->address_space();
          nv.offset = v->offset();
          nv.size = v->size();
          nop.inputs.push_back(nv);
        }
      }
      out.pcode.push_back(nop);
    }
  }
  out.has_high_function = false;
  out.high_function = HighFunctionV1();
  const HighFunction *hf = resp->high_function();
  if (hf != nullptr) {
    out.has_high_function = true;
    out.high_function.name = (hf->name() != nullptr) ? hf->name()->str() : std::string();
    read_data_type(hf->return_type(), out.high_function.has_return_type,
                   out.high_function.return_type);
    if (hf->parameters() != nullptr) {
      const auto *ps = hf->parameters();
      out.high_function.parameters.reserve(ps->size());
      for (::flatbuffers::uoffset_t i = 0; i < ps->size(); ++i) {
        HighSymbolV1 sym;
        read_high_symbol(ps->Get(i), sym);
        out.high_function.parameters.push_back(sym);
      }
    }
    if (hf->locals() != nullptr) {
      const auto *ls = hf->locals();
      out.high_function.locals.reserve(ls->size());
      for (::flatbuffers::uoffset_t i = 0; i < ls->size(); ++i) {
        HighSymbolV1 sym;
        read_high_symbol(ls->Get(i), sym);
        out.high_function.locals.push_back(sym);
      }
    }
  }
  out.diagnostics.clear();
  if (resp->diagnostics() != nullptr) {
    const auto *diags = resp->diagnostics();
    out.diagnostics.reserve(diags->size());
    for (::flatbuffers::uoffset_t i = 0; i < diags->size(); ++i) {
      const Diagnostic *d = diags->Get(i);
      DiagnosticV1 nd;
      nd.severity = d->severity();
      nd.message = (d->message() != nullptr) ? d->message()->str() : std::string();
      nd.pcode_seq = d->pcode_seq();
      out.diagnostics.push_back(nd);
    }
  }
  return true;
}

}  // namespace ipc
}  // namespace ghidra

#endif  // GHIDRA_IPC_RESPONSE_CODEC_H
