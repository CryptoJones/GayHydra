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
/// \file frame_v1.hh
/// \brief Decompiler IPC framing v1: greeting + CRC32 + resync.
///
/// Implements the wire format specified in
/// docs/decisions/0005-ipc-framing-v1.md (Rec 33 #33-2.1). Pure
/// helper module — no IPC plumbing in this header; callers wire
/// it into ghidra_arch.cc in later PRs of the sprint
/// (#33-2.2 reader / #33-2.3 writer / #33-2.4 greeting).
///
/// Wire format:
///   +---------+--------+--------+---------+-----------+---------+
///   | MAGIC   | TYPE   | FLAGS  | LENGTH  | PAYLOAD   | CRC32   |
///   | 4 bytes | 1 byte | 1 byte | 4 bytes | LENGTH    | 4 bytes |
///   +---------+--------+--------+---------+-----------+---------+
///
/// MAGIC = 0x47 0x48 0x01 0x00 ("GH" ASCII + protocol-version
/// 1.0). Big-endian throughout (matches Ghidra Java side's
/// DataInputStream default).
///
/// CRC32 is computed over `TYPE | FLAGS | LENGTH | PAYLOAD` using
/// the IEEE 802.3 polynomial (init 0xFFFFFFFF, refin/refout, final
/// XOR 0xFFFFFFFF) — same algorithm as java.util.zip.CRC32 and the
/// existing crc32tab in crc32.cc. MAGIC is NOT covered.

#ifndef __FRAME_V1_HH__
#define __FRAME_V1_HH__

#include "types.h"
#include <string>
#include <vector>
#include <istream>
#include <ostream>

namespace ghidra {

using std::string;
using std::vector;

/// \brief v1 frame header constants.
namespace frame_v1 {

/// 4-byte magic prefix: "GH" + protocol-version 1.0
constexpr uint1 MAGIC[4] = { 0x47, 0x48, 0x01, 0x00 };

/// Hard cap on payload length. Larger LENGTH fields are rejected
/// by the reader. Prevents a malformed length from making the
/// reader allocate gigabytes.
constexpr uint4 MAX_PAYLOAD_LEN = 16u * 1024u * 1024u;

/// Frame type enumeration. One byte. Wire layout matches
/// DD-0005's TYPE table.
enum class Type : uint1 {
  GREETING               = 0x00,
  COMMAND                = 0x01,
  RESPONSE               = 0x02,
  RESPONSE_BYTE_DATA     = 0x03,
  RESPONSE_STRING        = 0x04,
  EXCEPTION              = 0x05,
  CONTINUE               = 0x06,
  PING                   = 0x7E,
  ERROR_                 = 0x7F  // trailing underscore: ERROR is a macro in some Windows SDKs
};

/// Flag bitmask (1 byte). Bit 0 = CRC32 present (must be set in v1).
/// Bit 1 = compression (reserved). Bit 2 = continuation (reserved).
namespace flags {
  constexpr uint1 CRC_PRESENT   = 0x01;
  constexpr uint1 COMPRESSION   = 0x02;  // reserved
  constexpr uint1 CONTINUATION  = 0x04;  // reserved
}

/// Header struct (post-MAGIC, pre-PAYLOAD). 6 bytes on the wire.
struct Header {
  Type type;
  uint1 flagbits;
  uint4 length;
};

/// Error categories returned by decode_frame.
enum class Error {
  OK = 0,
  MAGIC_MISMATCH,    ///< Magic bytes didn't match — caller should resync.
  TRUNCATED,         ///< Stream ended mid-frame.
  LENGTH_TOO_LARGE,  ///< LENGTH exceeded MAX_PAYLOAD_LEN.
  CRC_MISMATCH,      ///< CRC32 trailer didn't match payload.
  RESERVED_FLAG_SET  ///< COMPRESSION/CONTINUATION flag set (not supported yet).
};

} // namespace frame_v1

/// \brief Encode a single v1 frame to a byte vector.
///
/// Computes the CRC32 over the assembled `type | flags | length |
/// payload` bytes (NOT over MAGIC). Always emits the CRC32 trailer
/// (flags |= CRC_PRESENT).
///
/// \param type Frame type tag.
/// \param payload Frame body bytes (may be empty).
/// \return Encoded frame: MAGIC + header + payload + CRC32.
///         Total length = 14 + payload.size() bytes.
vector<uint1> encode_frame_v1(frame_v1::Type type, const vector<uint1> &payload);

/// \brief Encode a single v1 frame from a string payload.
///
/// Convenience overload. UTF-8 bytes are taken as-is.
vector<uint1> encode_frame_v1(frame_v1::Type type, const string &payload);

/// \brief Decode a single v1 frame from a byte buffer.
///
/// Parses bytes starting at \c buf[start]. On success, fills out
/// \c hdr_out and \c payload_out, sets \c next_out to the offset
/// just past the trailing CRC, and returns Error::OK.
///
/// On MAGIC_MISMATCH: next_out is set to start+1 so the caller can
/// advance one byte and re-attempt (this is the resync entry
/// point — DD-0005's "walk forward until next valid frame").
///
/// On other errors: next_out is set to start (caller has to decide
/// whether to drop the buffer or wait for more bytes).
///
/// \param buf Byte buffer.
/// \param start Offset to start parsing at.
/// \param hdr_out Decoded header (only valid when return == OK).
/// \param payload_out Decoded payload (only valid when return == OK).
/// \param next_out Offset of next byte to parse (or resync target).
/// \return Decode error code.
frame_v1::Error decode_frame_v1(
    const vector<uint1> &buf,
    size_t start,
    frame_v1::Header &hdr_out,
    vector<uint1> &payload_out,
    size_t &next_out);

/// \brief Compute the IEEE 802.3 CRC32 over a byte range.
///
/// Helper exposed for unit tests + the frame-encode path. Init
/// 0xFFFFFFFF, polynomial 0xEDB88320, refin/refout, final XOR
/// 0xFFFFFFFF. Matches java.util.zip.CRC32.
uint4 crc32_ieee802_3(const uint1 *bytes, size_t len);

/// \brief Read one v1 frame from an istream.
///
/// Reads exactly 4 magic bytes; if they match \c MAGIC, continues
/// reading the 6-byte header + payload + 4-byte CRC trailer.
/// Returns the same \c Error categories as decode_frame_v1; on
/// MAGIC_MISMATCH the stream has consumed 4 bytes and \c
/// peeked_out is populated with what was read (so the caller can
/// feed those bytes into the legacy v0 path — that's how #33-2.4's
/// greeting handshake dispatches between v1 and v0 at connection
/// start).
///
/// On TRUNCATED (stream EOF mid-frame), the stream's position is
/// undefined relative to the start of the read attempt (some bytes
/// may have been consumed and discarded). Treat the channel as
/// closed by the peer at that point.
///
/// On CRC_MISMATCH or LENGTH_TOO_LARGE or RESERVED_FLAG_SET, the
/// stream has consumed the full frame (header + payload + CRC).
/// Caller can attempt to resync on the next read.
///
/// \param s Input stream.
/// \param hdr_out Decoded header (only valid when return == OK).
/// \param payload_out Decoded payload (only valid when return == OK).
/// \param peeked_out On MAGIC_MISMATCH, populated with the 4 bytes
///                  that were actually read (for v0 fallback feed).
///                  Otherwise empty.
/// \return Decode error code.
frame_v1::Error read_frame_v1(
    std::istream &s,
    frame_v1::Header &hdr_out,
    vector<uint1> &payload_out,
    vector<uint1> &peeked_out);

} // End namespace ghidra
#endif
