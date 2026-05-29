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

} // End namespace ghidra
