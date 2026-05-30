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
#include <streambuf>

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

/// Negotiated framing mode for a connection. The greeting handshake
/// (#33-2.4) decides this once at connection start; the IPC loop reads
/// and writes v0 markers or v1 frames accordingly.
enum class ChannelMode {
  V0 = 0,  ///< Legacy `\0\0\1\NN` marker framing (default / fallback).
  V1       ///< v1 framing negotiated via a GREETING handshake.
};

/// Greeting-payload version field. Major-version mismatch is rejected
/// by the reader; a minor bump is forward-compatible. On the wire the
/// 2-byte VERSION field is big-endian (major byte first).
constexpr uint1 GREETING_VERSION_MAJOR = 0x01;
constexpr uint1 GREETING_VERSION_MINOR = 0x00;

/// Greeting CAPABS bitmask (4-byte big-endian field). v1 always
/// advertises CRC-required; compression is reserved.
namespace capab {
  constexpr uint4 CRC_REQUIRED          = 0x00000001;
  constexpr uint4 COMPRESSION_SUPPORTED = 0x00000002;  // reserved
}

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

/// \brief Write one v1 frame to an ostream.
///
/// Stream wrapper around \c encode_frame_v1: the same wire layout
/// (`MAGIC + TYPE + FLAGS + LENGTH + PAYLOAD + CRC32`) is written
/// directly to \c s without an intermediate \c vector<uint1>
/// allocation when the payload is large (the implementation
/// builds the CRC incrementally as it writes).
///
/// Does not flush. Caller is responsible for `s.flush()` if the
/// transport needs it (stdio's libc buffering vs. a `stringstream`
/// is the canonical reason callers may or may not need to flush).
///
/// \param s Output stream.
/// \param type Frame type tag.
/// \param payload Frame body bytes.
void write_frame_v1(std::ostream &s, frame_v1::Type type, const vector<uint1> &payload);

/// \brief Write one v1 frame to an ostream from a string payload.
///
/// Convenience overload. UTF-8 bytes are taken as-is.
void write_frame_v1(std::ostream &s, frame_v1::Type type, const string &payload);

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

/// \brief Build a v1 greeting payload (VERSION | CAPABS | IDENT).
///
/// Layout per DD-0005: 2-byte big-endian VERSION (major.minor),
/// 4-byte big-endian CAPABS bitmask (CRC-required set), then the
/// UTF-8 \c ident bytes for the remainder. The result is the PAYLOAD
/// of a `Type::GREETING` frame — wrap it with \c write_frame_v1 /
/// \c encode_frame_v1 to put it on the wire.
///
/// \param ident Free-text peer identity (e.g. "GayHydra-decompiler").
/// \return Greeting payload bytes (6 + ident.size()).
vector<uint1> build_greeting_payload_v1(const string &ident);

/// \brief Parse a v1 greeting payload.
///
/// Inverse of \c build_greeting_payload_v1. Requires at least 6 bytes
/// (VERSION + CAPABS); the remainder is the IDENT string.
///
/// \param payload Greeting frame payload bytes.
/// \param version_out Big-endian VERSION (major in high byte).
/// \param capabs_out Big-endian CAPABS bitmask.
/// \param ident_out UTF-8 identity string (may be empty).
/// \return true if the payload was long enough to parse; false otherwise.
bool parse_greeting_payload_v1(
    const vector<uint1> &payload,
    uint2 &version_out,
    uint4 &capabs_out,
    string &ident_out);

/// \brief Perform the connection-start greeting handshake (#33-2.4).
///
/// A single non-consuming \c peek distinguishes the peer's framing:
/// v0 streams always open with `0x00` (the `\0\0\1\NN` burst), the v1
/// greeting opens with `MAGIC[0]` (0x47). When the first byte is not
/// `0x47` the stream is left byte-identical for the legacy v0 reader
/// and this returns \c ChannelMode::V0.
///
/// When the first byte is the v1 magic lead-in (a peer no v0 client
/// produces), the client greeting frame is consumed and validated; on
/// a well-formed `Type::GREETING` with a matching major version, the
/// server's own greeting (identified by \c server_ident) is written to
/// \c sout and the function returns \c ChannelMode::V1. Any malformed
/// or non-greeting frame falls back to \c ChannelMode::V0.
///
/// \param sin Connection input stream (peer → us).
/// \param sout Connection output stream (us → peer).
/// \param server_ident Our IDENT string for the reply greeting.
/// \return The negotiated channel mode.
frame_v1::ChannelMode negotiate_greeting_v1(
    std::istream &sin,
    std::ostream &sout,
    const string &server_ident);

/// \brief Output streambuf that wraps each flush as one v1 frame.
///
/// The write side of the #33-2.6 framing tunnel. The legacy v0
/// marshaling code writes its `\0\0\1\NN` bursts unchanged; this
/// streambuf buffers those bytes and, on every \c sync (i.e. every
/// `std::ostream::flush`), wraps the accumulated payload in a single
/// \c write_frame_v1 frame and flushes the destination. v0 already
/// flushes once per turn (otherwise the peer would block), so that
/// existing rhythm maps one-to-one onto frame boundaries and the v0
/// byte stream is preserved verbatim inside the frame payloads.
///
/// An empty flush (nothing buffered) emits no frame — a no-op, which
/// keeps the two directions' frame counts symmetric with
/// \c FrameInStreambuf's empty-frame skip.
///
/// Install by swapping the stream's buffer, e.g.
/// `old = os.rdbuf(new FrameOutStreambuf(old, Type::RESPONSE))`.
class FrameOutStreambuf : public std::streambuf {
public:
  FrameOutStreambuf(std::streambuf *dest, frame_v1::Type frameType);
  FrameOutStreambuf(const FrameOutStreambuf &) = delete;
  FrameOutStreambuf &operator=(const FrameOutStreambuf &) = delete;
protected:
  int overflow(int c) override;
  std::streamsize xsputn(const char *s, std::streamsize n) override;
  int sync() override;
private:
  std::ostream dest;          ///< Destination, wrapped as an ostream.
  frame_v1::Type frameType;   ///< TYPE tag stamped on every emitted frame.
  vector<uint1> pending;      ///< Bytes buffered since the last frame.
};

/// \brief Input streambuf that unwraps each v1 frame into a byte run.
///
/// The read side of the #33-2.6 framing tunnel. Each \c underflow
/// reads exactly one v1 frame via \c read_frame_v1 and exposes its
/// payload as the get area, so the legacy v0 reader pulls the exact
/// bytes the peer marshaled — frame boundaries are invisible to it.
/// Zero-length-payload frames are skipped (the symmetric counterpart
/// of \c FrameOutStreambuf's no-op empty flush). A non-OK frame read
/// (EOF, CRC mismatch, …) ends the stream: \c underflow returns EOF
/// and the cause is retrievable via \c getLastError.
///
/// Install by swapping the stream's buffer, e.g.
/// `old = is.rdbuf(new FrameInStreambuf(old))`.
class FrameInStreambuf : public std::streambuf {
public:
  explicit FrameInStreambuf(std::streambuf *src);
  FrameInStreambuf(const FrameInStreambuf &) = delete;
  FrameInStreambuf &operator=(const FrameInStreambuf &) = delete;

  /// Error from the frame read that ended the stream (OK until then).
  frame_v1::Error getLastError() const { return lastError; }
  /// TYPE of the most recently delivered frame.
  frame_v1::Type lastType() const { return lastFrameType; }
protected:
  int underflow() override;
private:
  std::istream src;             ///< Source, wrapped as an istream.
  vector<uint1> payload;        ///< Backing store for the current get area.
  frame_v1::Error lastError;    ///< Reason the stream ended (or OK).
  frame_v1::Type lastFrameType; ///< TYPE of the current frame.
};

} // End namespace ghidra
#endif
