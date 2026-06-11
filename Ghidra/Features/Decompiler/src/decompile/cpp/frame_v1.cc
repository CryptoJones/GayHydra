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
#include "frame_v1.hh"
#include "crc32.hh"

namespace ghidra {

uint4 crc32_ieee802_3(const uint1 *bytes, size_t len)
{
  uint4 reg = 0xffffffff;
  for (size_t i = 0; i < len; ++i)
    reg = crc_update(reg, bytes[i]);
  return reg ^ 0xffffffff;
}

/// Push a big-endian uint4 into \c out (4 bytes, MSB first).
static void push_be_u32(vector<uint1> &out, uint4 v)
{
  out.push_back((uint1)((v >> 24) & 0xff));
  out.push_back((uint1)((v >> 16) & 0xff));
  out.push_back((uint1)((v >> 8)  & 0xff));
  out.push_back((uint1)(v         & 0xff));
}

/// Read a big-endian uint4 from \c buf at offset \c off.
static uint4 read_be_u32(const vector<uint1> &buf, size_t off)
{
  return ((uint4)buf[off]     << 24)
       | ((uint4)buf[off + 1] << 16)
       | ((uint4)buf[off + 2] << 8)
       |  (uint4)buf[off + 3];
}

vector<uint1> encode_frame_v1(frame_v1::Type type, const vector<uint1> &payload)
{
  vector<uint1> out;
  out.reserve(14 + payload.size());

  // 4-byte magic
  for (int i = 0; i < 4; ++i)
    out.push_back(frame_v1::MAGIC[i]);

  // 1-byte type
  out.push_back((uint1)type);

  // 1-byte flags
  out.push_back(frame_v1::flags::CRC_PRESENT);

  // 4-byte big-endian length
  push_be_u32(out, (uint4)payload.size());

  // payload
  for (uint1 b : payload)
    out.push_back(b);

  // CRC over type | flags | length | payload (offset 4..end of payload)
  uint4 crc = crc32_ieee802_3(out.data() + 4, out.size() - 4);
  push_be_u32(out, crc);

  return out;
}

vector<uint1> encode_frame_v1(frame_v1::Type type, const string &payload)
{
  vector<uint1> bytes;
  bytes.reserve(payload.size());
  for (char c : payload)
    bytes.push_back((uint1)c);
  return encode_frame_v1(type, bytes);
}

frame_v1::Error decode_frame_v1(
    const vector<uint1> &buf,
    size_t start,
    frame_v1::Header &hdr_out,
    vector<uint1> &payload_out,
    size_t &next_out)
{
  // Need at least 4 bytes to check magic
  if (start + 4 > buf.size()) {
    next_out = start;
    return frame_v1::Error::TRUNCATED;
  }

  // Magic check
  for (int i = 0; i < 4; ++i) {
    if (buf[start + i] != frame_v1::MAGIC[i]) {
      next_out = start + 1;  // advance one byte for resync
      return frame_v1::Error::MAGIC_MISMATCH;
    }
  }

  // Need 4 + 1 + 1 + 4 = 10 bytes for header
  if (start + 10 > buf.size()) {
    next_out = start;
    return frame_v1::Error::TRUNCATED;
  }

  uint1 raw_type  = buf[start + 4];
  uint1 raw_flags = buf[start + 5];
  uint4 length    = read_be_u32(buf, start + 6);

  // Length cap
  if (length > frame_v1::MAX_PAYLOAD_LEN) {
    next_out = start;
    return frame_v1::Error::LENGTH_TOO_LARGE;
  }

  // Reserved flags
  if ((raw_flags & (frame_v1::flags::COMPRESSION | frame_v1::flags::CONTINUATION)) != 0) {
    next_out = start;
    return frame_v1::Error::RESERVED_FLAG_SET;
  }

  // Need header + payload + CRC trailer
  size_t total = 10 + (size_t)length + 4;
  if (start + total > buf.size()) {
    next_out = start;
    return frame_v1::Error::TRUNCATED;
  }

  // CRC check (over type | flags | length | payload)
  uint4 expected_crc = crc32_ieee802_3(buf.data() + start + 4, 6 + (size_t)length);
  uint4 actual_crc   = read_be_u32(buf, start + 10 + (size_t)length);
  if (actual_crc != expected_crc) {
    next_out = start;
    return frame_v1::Error::CRC_MISMATCH;
  }

  hdr_out.type     = (frame_v1::Type)raw_type;
  hdr_out.flagbits = raw_flags;
  hdr_out.length   = length;

  payload_out.clear();
  payload_out.reserve(length);
  for (uint4 i = 0; i < length; ++i)
    payload_out.push_back(buf[start + 10 + i]);

  next_out = start + total;
  return frame_v1::Error::OK;
}

void write_frame_v1(std::ostream &s, frame_v1::Type type, const vector<uint1> &payload)
{
  // Build header bytes: type | flags | length(BE).
  uint1 hdr[6];
  hdr[0] = (uint1)type;
  hdr[1] = frame_v1::flags::CRC_PRESENT;
  uint4 len = (uint4)payload.size();
  hdr[2] = (uint1)((len >> 24) & 0xff);
  hdr[3] = (uint1)((len >> 16) & 0xff);
  hdr[4] = (uint1)((len >> 8)  & 0xff);
  hdr[5] = (uint1)(len         & 0xff);

  // Compute CRC over header + payload incrementally.
  uint4 reg = 0xffffffff;
  for (int i = 0; i < 6; ++i) reg = crc_update(reg, hdr[i]);
  for (uint1 b : payload)     reg = crc_update(reg, b);
  uint4 crc = reg ^ 0xffffffff;

  // Write magic, header, payload, CRC.
  s.write((const char *)frame_v1::MAGIC, 4);
  s.write((const char *)hdr, 6);
  if (!payload.empty())
    s.write((const char *)payload.data(), (std::streamsize)payload.size());
  uint1 trailer[4] = {
    (uint1)((crc >> 24) & 0xff),
    (uint1)((crc >> 16) & 0xff),
    (uint1)((crc >> 8)  & 0xff),
    (uint1)(crc         & 0xff),
  };
  s.write((const char *)trailer, 4);
}

void write_frame_v1(std::ostream &s, frame_v1::Type type, const string &payload)
{
  // Reuse the vector path; cheap copy + payload is typically small
  // for control messages (greeting, command markers). For large
  // XML payloads we'd want a single-pass write, which the
  // vector<uint1> overload above already does.
  vector<uint1> bytes;
  bytes.reserve(payload.size());
  for (char c : payload)
    bytes.push_back((uint1)c);
  write_frame_v1(s, type, bytes);
}

/// Read exactly \c n bytes from \c s into \c out. Returns true on
/// success, false on EOF before \c n bytes are available.
static bool read_exact(std::istream &s, size_t n, vector<uint1> &out)
{
  size_t start = out.size();
  out.resize(start + n);
  s.read((char *)(out.data() + start), (std::streamsize)n);
  if (s.gcount() != (std::streamsize)n) {
    out.resize(start + (size_t)s.gcount());
    return false;
  }
  return true;
}

frame_v1::Error read_frame_v1(
    std::istream &s,
    frame_v1::Header &hdr_out,
    vector<uint1> &payload_out,
    vector<uint1> &peeked_out)
{
  peeked_out.clear();
  payload_out.clear();

  // Read 4 bytes for magic check.
  vector<uint1> magic_buf;
  if (!read_exact(s, 4, magic_buf)) {
    // Hand back whatever we got so the caller can decide
    // (probably channel closed).
    peeked_out = magic_buf;
    return frame_v1::Error::TRUNCATED;
  }

  // Magic check
  for (int i = 0; i < 4; ++i) {
    if (magic_buf[i] != frame_v1::MAGIC[i]) {
      peeked_out = magic_buf;
      return frame_v1::Error::MAGIC_MISMATCH;
    }
  }

  // Read 6-byte header (type + flags + 4-byte length)
  vector<uint1> hdr_buf;
  if (!read_exact(s, 6, hdr_buf)) {
    return frame_v1::Error::TRUNCATED;
  }

  uint1 raw_type  = hdr_buf[0];
  uint1 raw_flags = hdr_buf[1];
  uint4 length    = ((uint4)hdr_buf[2] << 24)
                  | ((uint4)hdr_buf[3] << 16)
                  | ((uint4)hdr_buf[4] << 8)
                  |  (uint4)hdr_buf[5];

  // Length cap
  if (length > frame_v1::MAX_PAYLOAD_LEN) {
    return frame_v1::Error::LENGTH_TOO_LARGE;
  }

  // Reserved flags
  if ((raw_flags & (frame_v1::flags::COMPRESSION | frame_v1::flags::CONTINUATION)) != 0) {
    return frame_v1::Error::RESERVED_FLAG_SET;
  }

  // Read payload
  vector<uint1> payload_buf;
  if (length > 0 && !read_exact(s, (size_t)length, payload_buf)) {
    return frame_v1::Error::TRUNCATED;
  }

  // Read CRC trailer
  vector<uint1> crc_buf;
  if (!read_exact(s, 4, crc_buf)) {
    return frame_v1::Error::TRUNCATED;
  }
  uint4 actual_crc = ((uint4)crc_buf[0] << 24)
                   | ((uint4)crc_buf[1] << 16)
                   | ((uint4)crc_buf[2] << 8)
                   |  (uint4)crc_buf[3];

  // Compute expected CRC over hdr_buf + payload_buf
  uint4 reg = 0xffffffff;
  for (uint1 b : hdr_buf) reg = crc_update(reg, b);
  for (uint1 b : payload_buf) reg = crc_update(reg, b);
  uint4 expected_crc = reg ^ 0xffffffff;

  if (actual_crc != expected_crc) {
    return frame_v1::Error::CRC_MISMATCH;
  }

  hdr_out.type     = (frame_v1::Type)raw_type;
  hdr_out.flagbits = raw_flags;
  hdr_out.length   = length;
  payload_out      = std::move(payload_buf);
  return frame_v1::Error::OK;
}

vector<uint1> build_greeting_payload_v1(const string &ident)
{
  return build_greeting_payload_v1(ident, 0);
}

vector<uint1> build_greeting_payload_v1(const string &ident, uint4 extra_capabs)
{
  vector<uint1> p;
  p.reserve(6 + ident.size());

  // 2-byte big-endian VERSION (major, then minor)
  p.push_back(frame_v1::GREETING_VERSION_MAJOR);
  p.push_back(frame_v1::GREETING_VERSION_MINOR);

  // 4-byte big-endian CAPABS — v1 always advertises CRC-required;
  // the go-live slices OR in their schema-payload bits (DD-0080).
  uint4 caps = frame_v1::capab::CRC_REQUIRED | extra_capabs;
  p.push_back((uint1)((caps >> 24) & 0xff));
  p.push_back((uint1)((caps >> 16) & 0xff));
  p.push_back((uint1)((caps >> 8)  & 0xff));
  p.push_back((uint1)(caps         & 0xff));

  // IDENT (UTF-8) for the remainder.
  for (char c : ident)
    p.push_back((uint1)c);

  return p;
}

bool parse_greeting_payload_v1(
    const vector<uint1> &payload,
    uint2 &version_out,
    uint4 &capabs_out,
    string &ident_out)
{
  // Need VERSION(2) + CAPABS(4) at minimum; IDENT may be empty.
  if (payload.size() < 6)
    return false;

  version_out = (uint2)(((uint2)payload[0] << 8) | (uint2)payload[1]);
  capabs_out  = ((uint4)payload[2] << 24)
              | ((uint4)payload[3] << 16)
              | ((uint4)payload[4] << 8)
              |  (uint4)payload[5];

  ident_out.clear();
  ident_out.reserve(payload.size() - 6);
  for (size_t i = 6; i < payload.size(); ++i)
    ident_out.push_back((char)payload[i]);

  return true;
}

frame_v1::ChannelMode negotiate_greeting_v1(
    std::istream &sin,
    std::ostream &sout,
    const string &server_ident)
{
  uint4 peer_capabs;
  return negotiate_greeting_v1(sin, sout, server_ident, 0, peer_capabs);
}

frame_v1::ChannelMode negotiate_greeting_v1(
    std::istream &sin,
    std::ostream &sout,
    const string &server_ident,
    uint4 server_extra_capabs,
    uint4 &peer_capabs_out)
{
  peer_capabs_out = 0;

  // v0 streams always begin with 0x00 (the \0\0\1\NN burst); the v1
  // greeting begins with MAGIC[0] (0x47). A single non-consuming peek
  // distinguishes them, leaving a v0 peer's stream byte-identical for
  // the legacy readToAnyBurst path.
  if (sin.peek() != (int)frame_v1::MAGIC[0])
    return frame_v1::ChannelMode::V0;

  // First byte is the v1 magic lead-in — a peer no v0 client produces.
  // Consume and validate the client greeting frame.
  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error err = read_frame_v1(sin, hdr, payload, peeked);
  if (err != frame_v1::Error::OK || hdr.type != frame_v1::Type::GREETING)
    return frame_v1::ChannelMode::V0;

  uint2 version;
  uint4 capabs;
  string ident;
  if (!parse_greeting_payload_v1(payload, version, capabs, ident))
    return frame_v1::ChannelMode::V0;

  // Reject a mismatched major version; minor bumps are compatible.
  if (((version >> 8) & 0xff) != frame_v1::GREETING_VERSION_MAJOR)
    return frame_v1::ChannelMode::V0;

  // Reply with our own greeting and switch the channel to v1. The
  // peer's capabilities are surfaced only on a successful v1
  // negotiation — a v0 channel has no capability concept.
  vector<uint1> reply = build_greeting_payload_v1(server_ident, server_extra_capabs);
  write_frame_v1(sout, frame_v1::Type::GREETING, reply);
  sout.flush();
  peer_capabs_out = capabs;
  return frame_v1::ChannelMode::V1;
}

// ------------------------------------------------------------------
// Framing tunnel streambufs (#33-2.6). FrameOutStreambuf turns each
// flush into one v1 frame; FrameInStreambuf turns each frame back
// into a byte run. Together they make the v0 command-loop bytes ride
// transparently inside v1 frames once a channel negotiates v1.
// ------------------------------------------------------------------

FrameOutStreambuf::FrameOutStreambuf(std::streambuf *destbuf, frame_v1::Type type)
  : dest(destbuf), frameType(type)
{
}

int FrameOutStreambuf::overflow(int c)
{
  if (c == traits_type::eof())
    return traits_type::not_eof(c);
  pending.push_back((uint1)(c & 0xff));
  return c;
}

std::streamsize FrameOutStreambuf::xsputn(const char *s, std::streamsize n)
{
  if (n > 0) {
    size_t base = pending.size();
    pending.resize(base + (size_t)n);
    for (std::streamsize i = 0; i < n; ++i)
      pending[base + (size_t)i] = (uint1)s[i];
  }
  return n;
}

int FrameOutStreambuf::sync()
{
  if (!pending.empty()) {
    write_frame_v1(dest, frameType, pending);
    pending.clear();
  }
  dest.flush();
  return dest.good() ? 0 : -1;
}

FrameInStreambuf::FrameInStreambuf(std::streambuf *srcbuf)
  : src(srcbuf),
    lastError(frame_v1::Error::OK),
    lastFrameType(frame_v1::Type::GREETING)
{
}

int FrameInStreambuf::underflow()
{
  // Pull whole frames until one carries a non-empty payload.
  for (;;) {
    frame_v1::Header hdr;
    vector<uint1> peeked;
    frame_v1::Error err = read_frame_v1(src, hdr, payload, peeked);
    if (err != frame_v1::Error::OK) {
      lastError = err;
      setg(nullptr, nullptr, nullptr);
      return traits_type::eof();
    }
    lastFrameType = hdr.type;
    if (payload.empty())
      continue;  // no-op frame (symmetric with an empty flush) — skip
    char *base = (char *)payload.data();
    setg(base, base, base + payload.size());
    return traits_type::to_int_type((char)payload[0]);
  }
}

} // End namespace ghidra
