/* ###
 * IP: GHIDRA
 *
 * Fuzz harness for the decompiler's XML parser.
 *
 * Scaffold for Rec 13: see docs/security/OSS_FUZZ.md.
 *
 * Entry point: xml_tree(istream&) in xml.cc.
 *
 * The XML parser is reached by:
 *   - Loaded sla files (Sleigh compiled output).
 *   - Decompiler XML IPC variant.
 *   - LoadImageXml-format binaries.
 *
 * Any uncaught exception, abort, or sanitizer trip on a well-formed
 * (or malformed) XML input from this harness is a bug.
 */

#include <stddef.h>
#include <stdint.h>
#include <sstream>
#include <string>

#include "xml.hh"

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    /* libFuzzer requires the harness to be cheap, deterministic, and
     * not retain state across calls. We construct a fresh istringstream
     * on every call and discard the parse result. */
    if (size == 0) {
        return 0;
    }

    std::string input(reinterpret_cast<const char *>(data), size);
    std::istringstream in(input);

    try {
        ghidra::Document *doc = ghidra::xml_tree(in);
        delete doc;
    } catch (ghidra::DecoderError &) {
        /* Expected on malformed input; not a finding. */
    } catch (ghidra::LowlevelError &) {
        /* Expected on malformed input; not a finding. */
    }
    /* All other exception types escape and trigger a finding —
     * un-typed catch is intentionally absent. */

    return 0;
}
