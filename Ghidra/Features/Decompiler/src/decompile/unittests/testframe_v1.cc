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
#include "test.hh"
#include <sstream>

namespace ghidra {

using std::stringstream;

// ------------------------------------------------------------------
// CRC32 sanity — must match the IEEE 802.3 / java.util.zip.CRC32
// vector for "123456789" (one of the canonical test vectors).
// ------------------------------------------------------------------

TEST(frame_v1_crc32_canonical_vector) {
  const char *s = "123456789";
  uint4 c = crc32_ieee802_3((const uint1 *)s, 9);
  // Canonical IEEE 802.3 CRC32 of "123456789" is 0xCBF43926.
  ASSERT_EQUALS(c, 0xcbf43926u);
}

TEST(frame_v1_crc32_empty) {
  uint4 c = crc32_ieee802_3((const uint1 *)"", 0);
  // CRC32 of empty input under (init 0xFFFFFFFF, final XOR
  // 0xFFFFFFFF) is 0x00000000.
  ASSERT_EQUALS(c, 0u);
}

// ------------------------------------------------------------------
// Round-trip encode + decode for every TYPE.
// ------------------------------------------------------------------

static void roundtrip_check(frame_v1::Type type, const string &payload) {
  vector<uint1> wire = encode_frame_v1(type, payload);

  // Wire size sanity: 14 + payload.size()
  ASSERT_EQUALS(wire.size(), (size_t)(14 + payload.size()));

  // First 4 bytes must be MAGIC
  ASSERT_EQUALS(wire[0], frame_v1::MAGIC[0]);
  ASSERT_EQUALS(wire[1], frame_v1::MAGIC[1]);
  ASSERT_EQUALS(wire[2], frame_v1::MAGIC[2]);
  ASSERT_EQUALS(wire[3], frame_v1::MAGIC[3]);

  // TYPE byte
  ASSERT_EQUALS(wire[4], (uint1)type);

  // FLAGS must have CRC_PRESENT
  ASSERT(wire[5] & frame_v1::flags::CRC_PRESENT);

  // Round-trip
  frame_v1::Header hdr;
  vector<uint1> got_payload;
  size_t next = 999;
  frame_v1::Error e = decode_frame_v1(wire, 0, hdr, got_payload, next);

  ASSERT_EQUALS((int)e, (int)frame_v1::Error::OK);
  ASSERT_EQUALS((int)hdr.type, (int)type);
  ASSERT_EQUALS(hdr.length, (uint4)payload.size());
  ASSERT_EQUALS(got_payload.size(), payload.size());
  ASSERT_EQUALS(next, wire.size());
  for (size_t i = 0; i < payload.size(); ++i)
    ASSERT_EQUALS((char)got_payload[i], payload[i]);
}

TEST(frame_v1_roundtrip_greeting) {
  roundtrip_check(frame_v1::Type::GREETING, "GayHydra/26.1.15 build=linux_x86_64");
}

TEST(frame_v1_roundtrip_command_empty) {
  roundtrip_check(frame_v1::Type::COMMAND, "");
}

TEST(frame_v1_roundtrip_response) {
  roundtrip_check(frame_v1::Type::RESPONSE, "<result/>");
}

TEST(frame_v1_roundtrip_response_byte_data) {
  roundtrip_check(frame_v1::Type::RESPONSE_BYTE_DATA, string("\x00\x01\x02\x03\xff", 5));
}

TEST(frame_v1_roundtrip_response_string) {
  roundtrip_check(frame_v1::Type::RESPONSE_STRING, "hello world");
}

TEST(frame_v1_roundtrip_exception) {
  roundtrip_check(frame_v1::Type::EXCEPTION, "LowlevelError: oh no");
}

TEST(frame_v1_roundtrip_continue) {
  roundtrip_check(frame_v1::Type::CONTINUE, "");
}

TEST(frame_v1_roundtrip_ping) {
  roundtrip_check(frame_v1::Type::PING, "");
}

TEST(frame_v1_roundtrip_error) {
  roundtrip_check(frame_v1::Type::ERROR_, "");
}

// ------------------------------------------------------------------
// Resync: garbage bytes before a valid frame.
// ------------------------------------------------------------------

TEST(frame_v1_resync_garbage_prefix) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::COMMAND, "payload");

  // Prepend some garbage that includes near-magic bytes
  vector<uint1> stream;
  stream.push_back(0x00);
  stream.push_back(0x47);  // first byte of magic, no follow-through
  stream.push_back(0xff);
  stream.push_back(0x47);  // again
  stream.push_back(0x48);  // and second byte
  stream.push_back(0xaa);  // but no protocol-version bytes
  for (uint1 b : wire)
    stream.push_back(b);

  // Walk forward until we find a valid frame
  frame_v1::Header hdr;
  vector<uint1> payload;
  size_t at = 0;
  while (at < stream.size()) {
    size_t next;
    frame_v1::Error e = decode_frame_v1(stream, at, hdr, payload, next);
    if (e == frame_v1::Error::OK) break;
    if (e != frame_v1::Error::MAGIC_MISMATCH) {
      cerr << "  unexpected error " << (int)e << " at offset " << at << endl;
      throw 0;
    }
    at = next;
  }
  ASSERT_EQUALS((int)hdr.type, (int)frame_v1::Type::COMMAND);
  ASSERT_EQUALS(hdr.length, 7u);
}

// ------------------------------------------------------------------
// Failure paths.
// ------------------------------------------------------------------

TEST(frame_v1_truncated_just_magic) {
  vector<uint1> stream;
  for (int i = 0; i < 4; ++i)
    stream.push_back(frame_v1::MAGIC[i]);

  frame_v1::Header hdr;
  vector<uint1> payload;
  size_t next;
  frame_v1::Error e = decode_frame_v1(stream, 0, hdr, payload, next);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
}

TEST(frame_v1_truncated_header) {
  // Magic + partial header (only 3 bytes of length)
  vector<uint1> stream;
  for (int i = 0; i < 4; ++i)
    stream.push_back(frame_v1::MAGIC[i]);
  stream.push_back((uint1)frame_v1::Type::COMMAND);
  stream.push_back(frame_v1::flags::CRC_PRESENT);
  stream.push_back(0x00); stream.push_back(0x00); stream.push_back(0x00);

  frame_v1::Header hdr;
  vector<uint1> payload;
  size_t next;
  frame_v1::Error e = decode_frame_v1(stream, 0, hdr, payload, next);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
}

TEST(frame_v1_truncated_payload) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::COMMAND, "hello world");
  // Drop the last 5 bytes (cuts into the CRC trailer + tail of payload)
  vector<uint1> truncated(wire.begin(), wire.end() - 5);

  frame_v1::Header hdr;
  vector<uint1> payload;
  size_t next;
  frame_v1::Error e = decode_frame_v1(truncated, 0, hdr, payload, next);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
}

TEST(frame_v1_length_too_large) {
  // Forge a frame with LENGTH = MAX_PAYLOAD_LEN + 1
  vector<uint1> stream;
  for (int i = 0; i < 4; ++i)
    stream.push_back(frame_v1::MAGIC[i]);
  stream.push_back((uint1)frame_v1::Type::COMMAND);
  stream.push_back(frame_v1::flags::CRC_PRESENT);
  uint4 too_big = frame_v1::MAX_PAYLOAD_LEN + 1;
  stream.push_back((uint1)((too_big >> 24) & 0xff));
  stream.push_back((uint1)((too_big >> 16) & 0xff));
  stream.push_back((uint1)((too_big >> 8)  & 0xff));
  stream.push_back((uint1)(too_big         & 0xff));

  frame_v1::Header hdr;
  vector<uint1> payload;
  size_t next;
  frame_v1::Error e = decode_frame_v1(stream, 0, hdr, payload, next);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::LENGTH_TOO_LARGE);
}

TEST(frame_v1_crc_mismatch) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::COMMAND, "abc");
  // Flip a payload byte without recomputing CRC
  wire[10] ^= 0xff;

  frame_v1::Header hdr;
  vector<uint1> payload;
  size_t next;
  frame_v1::Error e = decode_frame_v1(wire, 0, hdr, payload, next);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::CRC_MISMATCH);
}

TEST(frame_v1_reserved_flag_set) {
  // Forge: valid magic, valid type, FLAGS has COMPRESSION bit set
  vector<uint1> stream;
  for (int i = 0; i < 4; ++i)
    stream.push_back(frame_v1::MAGIC[i]);
  stream.push_back((uint1)frame_v1::Type::COMMAND);
  stream.push_back(frame_v1::flags::CRC_PRESENT | frame_v1::flags::COMPRESSION);
  stream.push_back(0); stream.push_back(0); stream.push_back(0); stream.push_back(0);  // length=0

  frame_v1::Header hdr;
  vector<uint1> payload;
  size_t next;
  frame_v1::Error e = decode_frame_v1(stream, 0, hdr, payload, next);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::RESERVED_FLAG_SET);
}

// ------------------------------------------------------------------
// Wire-format pinning: the exact bytes for a known input.
// Lets a future reader confirm we haven't accidentally changed
// the protocol layout.
// ------------------------------------------------------------------

TEST(frame_v1_wire_format_pin_ping) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::PING, "");

  // 14 bytes total: magic(4) + type(1) + flags(1) + length(4) + crc(4)
  ASSERT_EQUALS(wire.size(), (size_t)14);
  ASSERT_EQUALS(wire[0], 0x47);  // 'G'
  ASSERT_EQUALS(wire[1], 0x48);  // 'H'
  ASSERT_EQUALS(wire[2], 0x01);
  ASSERT_EQUALS(wire[3], 0x00);
  ASSERT_EQUALS(wire[4], 0x7e);  // PING
  ASSERT_EQUALS(wire[5], 0x01);  // CRC_PRESENT
  ASSERT_EQUALS(wire[6], 0x00);  // length BE: 0x00000000
  ASSERT_EQUALS(wire[7], 0x00);
  ASSERT_EQUALS(wire[8], 0x00);
  ASSERT_EQUALS(wire[9], 0x00);
  // CRC over 6 bytes: 0x7e 0x01 0x00 0x00 0x00 0x00
  uint1 body[6] = { 0x7e, 0x01, 0x00, 0x00, 0x00, 0x00 };
  uint4 expected_crc = crc32_ieee802_3(body, 6);
  uint4 wire_crc = ((uint4)wire[10] << 24)
                 | ((uint4)wire[11] << 16)
                 | ((uint4)wire[12] << 8)
                 |  (uint4)wire[13];
  ASSERT_EQUALS(wire_crc, expected_crc);
}

// ------------------------------------------------------------------
// Stream-based reader (#33-2.2). Same coverage matrix as the
// buffer-based decoder, but driven through std::stringstream.
// ------------------------------------------------------------------

static stringstream make_stream(const vector<uint1> &bytes) {
  stringstream s(std::ios::in | std::ios::out | std::ios::binary);
  s.write((const char *)bytes.data(), (std::streamsize)bytes.size());
  return s;
}

TEST(frame_v1_read_stream_roundtrip_command) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::COMMAND, "<query/>");
  stringstream s = make_stream(wire);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::OK);
  ASSERT_EQUALS((int)hdr.type, (int)frame_v1::Type::COMMAND);
  ASSERT_EQUALS(payload.size(), (size_t)8);
  ASSERT_EQUALS(peeked.size(), (size_t)0);
}

TEST(frame_v1_read_stream_roundtrip_empty_payload) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::PING, "");
  stringstream s = make_stream(wire);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::OK);
  ASSERT_EQUALS((int)hdr.type, (int)frame_v1::Type::PING);
  ASSERT_EQUALS(payload.size(), (size_t)0);
}

TEST(frame_v1_read_stream_two_frames_sequential) {
  vector<uint1> w1 = encode_frame_v1(frame_v1::Type::GREETING, "GayHydra/26.1.x");
  vector<uint1> w2 = encode_frame_v1(frame_v1::Type::COMMAND, "decompileAt");
  vector<uint1> combined = w1;
  for (uint1 b : w2) combined.push_back(b);
  stringstream s = make_stream(combined);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;

  // First frame
  frame_v1::Error e1 = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e1, (int)frame_v1::Error::OK);
  ASSERT_EQUALS((int)hdr.type, (int)frame_v1::Type::GREETING);

  // Second frame from same stream
  frame_v1::Error e2 = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e2, (int)frame_v1::Error::OK);
  ASSERT_EQUALS((int)hdr.type, (int)frame_v1::Type::COMMAND);
  ASSERT_EQUALS(payload.size(), (size_t)11);
}

TEST(frame_v1_read_stream_magic_mismatch_returns_peek) {
  // Send 4 bytes that aren't magic, expect MAGIC_MISMATCH and
  // those exact bytes back in `peeked` so the caller can feed
  // them into the v0 path.
  vector<uint1> bytes = { 0x00, 0x00, 0x01, 0x0e };  // v0 string-start marker
  stringstream s = make_stream(bytes);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::MAGIC_MISMATCH);
  ASSERT_EQUALS(peeked.size(), (size_t)4);
  ASSERT_EQUALS(peeked[0], 0x00);
  ASSERT_EQUALS(peeked[1], 0x00);
  ASSERT_EQUALS(peeked[2], 0x01);
  ASSERT_EQUALS(peeked[3], 0x0e);
}

TEST(frame_v1_read_stream_eof_on_empty) {
  stringstream s;  // empty
  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
  ASSERT_EQUALS(peeked.size(), (size_t)0);
}

TEST(frame_v1_read_stream_eof_after_partial_magic) {
  vector<uint1> bytes = { 0x47, 0x48, 0x01 };  // 3 of 4 magic bytes
  stringstream s = make_stream(bytes);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
  ASSERT_EQUALS(peeked.size(), (size_t)3);
}

TEST(frame_v1_read_stream_eof_after_header) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::COMMAND, "hello");
  // Keep only magic + header (no payload, no CRC)
  vector<uint1> truncated(wire.begin(), wire.begin() + 10);
  stringstream s = make_stream(truncated);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
}

TEST(frame_v1_read_stream_eof_during_payload) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::RESPONSE, "abcdefghij");
  // Keep magic + header + half the payload
  vector<uint1> truncated(wire.begin(), wire.begin() + 15);
  stringstream s = make_stream(truncated);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
}

TEST(frame_v1_read_stream_eof_before_crc) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::RESPONSE, "abc");
  // Drop the 4-byte CRC trailer
  vector<uint1> truncated(wire.begin(), wire.end() - 4);
  stringstream s = make_stream(truncated);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::TRUNCATED);
}

TEST(frame_v1_read_stream_length_too_large) {
  vector<uint1> bytes;
  for (int i = 0; i < 4; ++i) bytes.push_back(frame_v1::MAGIC[i]);
  bytes.push_back((uint1)frame_v1::Type::COMMAND);
  bytes.push_back(frame_v1::flags::CRC_PRESENT);
  uint4 too_big = frame_v1::MAX_PAYLOAD_LEN + 1;
  bytes.push_back((uint1)((too_big >> 24) & 0xff));
  bytes.push_back((uint1)((too_big >> 16) & 0xff));
  bytes.push_back((uint1)((too_big >> 8)  & 0xff));
  bytes.push_back((uint1)(too_big         & 0xff));
  stringstream s = make_stream(bytes);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::LENGTH_TOO_LARGE);
}

TEST(frame_v1_read_stream_reserved_flag) {
  vector<uint1> bytes;
  for (int i = 0; i < 4; ++i) bytes.push_back(frame_v1::MAGIC[i]);
  bytes.push_back((uint1)frame_v1::Type::COMMAND);
  bytes.push_back(frame_v1::flags::CRC_PRESENT | frame_v1::flags::COMPRESSION);
  bytes.push_back(0); bytes.push_back(0); bytes.push_back(0); bytes.push_back(0);
  stringstream s = make_stream(bytes);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::RESERVED_FLAG_SET);
}

TEST(frame_v1_read_stream_crc_mismatch) {
  vector<uint1> wire = encode_frame_v1(frame_v1::Type::RESPONSE, "abc");
  wire[wire.size() - 1] ^= 0xff;  // flip last CRC byte
  stringstream s = make_stream(wire);

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::CRC_MISMATCH);
}

// ------------------------------------------------------------------
// Stream-based writer (#33-2.3). Verifies the bytes written to an
// ostream match the documented wire layout (and the buffer-based
// encoder's output, byte-for-byte).
// ------------------------------------------------------------------

static vector<uint1> stream_bytes(const stringstream &s) {
  string str = s.str();
  vector<uint1> out;
  out.reserve(str.size());
  for (char c : str)
    out.push_back((uint1)c);
  return out;
}

TEST(frame_v1_write_stream_matches_buffer_encoder) {
  // The stream writer and the buffer encoder must produce the
  // exact same bytes — neither is supposed to invent layout.
  vector<uint1> wire_buf = encode_frame_v1(frame_v1::Type::COMMAND, "<query/>");
  stringstream s(std::ios::out | std::ios::binary);
  write_frame_v1(s, frame_v1::Type::COMMAND, string("<query/>"));
  vector<uint1> wire_str = stream_bytes(s);
  ASSERT_EQUALS(wire_buf.size(), wire_str.size());
  for (size_t i = 0; i < wire_buf.size(); ++i)
    ASSERT_EQUALS(wire_buf[i], wire_str[i]);
}

TEST(frame_v1_write_stream_empty_payload) {
  stringstream s(std::ios::out | std::ios::binary);
  write_frame_v1(s, frame_v1::Type::PING, string(""));
  vector<uint1> bytes = stream_bytes(s);

  // 14 bytes total: 4 magic + 1 type + 1 flags + 4 length + 0 payload + 4 crc
  ASSERT_EQUALS(bytes.size(), (size_t)14);
  ASSERT_EQUALS(bytes[4], (uint1)frame_v1::Type::PING);
  ASSERT_EQUALS(bytes[5], frame_v1::flags::CRC_PRESENT);
  // length = 0
  ASSERT_EQUALS(bytes[6], 0);
  ASSERT_EQUALS(bytes[7], 0);
  ASSERT_EQUALS(bytes[8], 0);
  ASSERT_EQUALS(bytes[9], 0);
}

TEST(frame_v1_write_then_read_roundtrip) {
  // Writer -> Reader on the same stringstream.
  stringstream s(std::ios::in | std::ios::out | std::ios::binary);
  string payload = "GayHydra/26.1.x build=linux_x86_64";
  write_frame_v1(s, frame_v1::Type::GREETING, payload);

  frame_v1::Header hdr;
  vector<uint1> got_payload, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, got_payload, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::OK);
  ASSERT_EQUALS((int)hdr.type, (int)frame_v1::Type::GREETING);
  ASSERT_EQUALS(got_payload.size(), payload.size());
  for (size_t i = 0; i < payload.size(); ++i)
    ASSERT_EQUALS((char)got_payload[i], payload[i]);
}

TEST(frame_v1_write_two_frames_back_to_back) {
  // The writer leaves the stream positioned right after each frame
  // it emits — two consecutive writes should produce two
  // back-to-back parseable frames.
  stringstream s(std::ios::in | std::ios::out | std::ios::binary);
  write_frame_v1(s, frame_v1::Type::COMMAND, string("first"));
  write_frame_v1(s, frame_v1::Type::COMMAND, string("second"));

  frame_v1::Header hdr;
  vector<uint1> payload, peeked;

  frame_v1::Error e1 = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e1, (int)frame_v1::Error::OK);
  ASSERT_EQUALS(payload.size(), (size_t)5);

  frame_v1::Error e2 = read_frame_v1(s, hdr, payload, peeked);
  ASSERT_EQUALS((int)e2, (int)frame_v1::Error::OK);
  ASSERT_EQUALS(payload.size(), (size_t)6);
}

TEST(frame_v1_write_binary_payload) {
  // 0x00 bytes in the payload must not confuse anything — the
  // length prefix is what bounds the read, not a terminator scan.
  vector<uint1> body;
  for (int i = 0; i < 256; ++i) body.push_back((uint1)i);
  stringstream s(std::ios::in | std::ios::out | std::ios::binary);
  write_frame_v1(s, frame_v1::Type::RESPONSE_BYTE_DATA, body);

  frame_v1::Header hdr;
  vector<uint1> got, peeked;
  frame_v1::Error e = read_frame_v1(s, hdr, got, peeked);
  ASSERT_EQUALS((int)e, (int)frame_v1::Error::OK);
  ASSERT_EQUALS(got.size(), body.size());
  for (size_t i = 0; i < body.size(); ++i)
    ASSERT_EQUALS(got[i], body[i]);
}

} // End namespace ghidra
