/* ###
 * IP: GHIDRA
 *
 * Fuzz harness for the decompiler's binary marshal protocol.
 *
 * Scaffold for Rec 13: see docs/security/OSS_FUZZ.md.
 *
 * This harness drives the Decoder/Encoder interfaces in marshal.cc/hh,
 * which implement the Java↔C++ IPC. The protocol is byte-framed with
 * `{0, 0, 1, X}` magic markers (see DecompileProcess.java:54-61) and
 * has no schema version, no CRC, and no graceful resync. One byte of
 * corruption can wedge the decompiler.
 *
 * Rec 33 (versioning) and Rec 34 (schema replacement) address the
 * protocol design; this harness addresses the parser side.
 */

#include <stddef.h>
#include <stdint.h>

#include "marshal.hh"

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size == 0) {
        return 0;
    }

    /* The marshal Decoder consumes a length-prefixed byte stream of
     * elements. We feed the fuzzer-generated buffer straight in and
     * let the decoder walk it. */
    try {
        ghidra::PackedDecode decoder(nullptr /* AddrSpaceManager: not
            needed for parser-side fuzzing of malformed input */);
        decoder.ingestBytes(reinterpret_cast<const char *>(data),
                            reinterpret_cast<const char *>(data) + size);
        /* Walk one element to exercise the parse paths. */
        decoder.peekElement();
    } catch (ghidra::DecoderError &) {
        /* Expected on malformed framing; not a finding. */
    } catch (ghidra::LowlevelError &) {
        /* Expected on malformed framing; not a finding. */
    }

    return 0;
}
