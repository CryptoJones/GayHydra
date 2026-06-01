/* ###
 * IP: GHIDRA
 *
 * Fuzz harness for the Rec 34 FlatBuffers IPC schema decoders (#34-9).
 *
 * The schema codecs replace the hand-rolled marshal/XML IPC parsers that
 * `fuzz_marshal.cc` / `fuzz_xml.cc` cover. On the worker side, every
 * `decode_*_request()` consumes attacker-influenceable bytes off the wire,
 * so each one verifies the buffer before reading and must return false —
 * never crash, read out of bounds, or trip a sanitizer — on a null,
 * garbage, or truncated payload. This harness is the differential check on
 * that contract: it feeds one fuzzer buffer to every decode entry point
 * across the four codec headers and relies on ASan/UBSan to catch any
 * verifier or accessor that reads through a malformed buffer.
 *
 * This is the in-tree continuation of Rec 13's fuzz set (see README.md);
 * the OSS-Fuzz upstream submission was rejected, so this harness stands on
 * its own and runs locally / via our own CI. Pure and header-only: the
 * codecs are inline over the vendored FlatBuffers runtime, so the harness
 * links no decompiler object files.
 */

#include <stddef.h>
#include <stdint.h>

#include "schema/ipc_config_codec.h"
#include "schema/ipc_lifecycle_codec.h"
#include "schema/ipc_request_codec.h"
#include "schema/ipc_response_codec.h"

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    using namespace ghidra::ipc;

    /* Each decoder verifies before reading and returns false on a buffer it
     * cannot trust. We discard the bool: the property under test is that no
     * input — however malformed — drives an out-of-bounds read or other UB,
     * which the sanitizers enforce. The out-params start default-constructed
     * and are left untouched on rejection. */

    /* DecompileFunction request/response (#34-4 / #34-5). */
    DecompileRequestV1 decompileReq;
    (void)decode_decompile_request(data, size, decompileReq);
    DecompileResponseV1 decompileResp;
    (void)decode_decompile_response(data, size, decompileResp);

    /* Program-lifecycle trio (#34-6a). */
    RegisterProgramRequestV1 registerReq;
    (void)decode_register_program_request(data, size, registerReq);
    RegisterProgramResponseV1 registerResp;
    (void)decode_register_program_response(data, size, registerResp);
    DeregisterProgramRequestV1 deregisterReq;
    (void)decode_deregister_program_request(data, size, deregisterReq);
    DeregisterProgramResponseV1 deregisterResp;
    (void)decode_deregister_program_response(data, size, deregisterResp);
    FlushNativeRequestV1 flushReq;
    (void)decode_flush_native_request(data, size, flushReq);
    FlushNativeResponseV1 flushResp;
    (void)decode_flush_native_response(data, size, flushResp);

    /* Configuration/graph trio (#34-6b). */
    StructureGraphRequestV1 structureReq;
    (void)decode_structure_graph_request(data, size, structureReq);
    StructureGraphResponseV1 structureResp;
    (void)decode_structure_graph_response(data, size, structureResp);
    SetActionRequestV1 setActionReq;
    (void)decode_set_action_request(data, size, setActionReq);
    SetActionResponseV1 setActionResp;
    (void)decode_set_action_response(data, size, setActionResp);
    SetOptionsRequestV1 setOptionsReq;
    (void)decode_set_options_request(data, size, setOptionsReq);
    SetOptionsResponseV1 setOptionsResp;
    (void)decode_set_options_response(data, size, setOptionsResp);

    return 0;
}
