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
package ghidra.app.decompiler;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.junit.Test;

import ghidra.app.decompiler.DecompileProcess.FrameInputStream;
import ghidra.app.decompiler.DecompileProcess.FrameOutputStream;

/**
 * Wire-format unit tests for the Rec 33 v1 framing client emitted by
 * {@link DecompileProcess} (docs/decisions/0005-ipc-framing-v1.md). These
 * assert the exact bytes produced by the package-private encoders so any
 * drift from the {@code frame_v1.hh}/{@code frame_v1.cc} contract on the
 * native side fails fast. Pure byte math — no Ghidra runtime, so this lives
 * in the fast {@code test} sourceset and runs in CI's {@code gradle test}.
 */
public class DecompileProcessFramingV1Test {

	// Mirrors of the contract constants, duplicated here on purpose: the
	// test must fail if DecompileProcess silently changes a constant.
	private static final byte[] MAGIC = { 0x47, 0x48, 0x01, 0x00 };
	private static final int TYPE_GREETING = 0x00;
	private static final int FLAG_CRC_PRESENT = 0x01;

	private static long beU32(byte[] b, int off) {
		return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) |
			((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
	}

	/** Independently recompute the CRC over TYPE|FLAGS|LENGTH|PAYLOAD. */
	private static long expectedCrc(byte[] frame) {
		CRC32 crc = new CRC32();
		crc.update(frame, 4, frame.length - 8); // skip MAGIC(4) and trailer(4)
		return crc.getValue();
	}

	@Test
	public void testEmptyPayloadFrameStructure() {
		byte[] frame = DecompileProcess.encodeFrameV1(TYPE_GREETING, new byte[0]);
		// MAGIC(4)+TYPE(1)+FLAGS(1)+LEN(4)+CRC(4) = 14 bytes, no payload.
		assertEquals("empty frame length", 14, frame.length);
		assertArrayEquals("magic", MAGIC, new byte[] { frame[0], frame[1], frame[2], frame[3] });
		assertEquals("type", TYPE_GREETING, frame[4] & 0xff);
		assertEquals("flags = CRC_PRESENT", FLAG_CRC_PRESENT, frame[5] & 0xff);
		assertEquals("length BE", 0L, beU32(frame, 6));
		assertEquals("crc trailer matches recompute", expectedCrc(frame), beU32(frame, 10));
	}

	@Test
	public void testGreetingPayloadStructure() {
		byte[] payload = DecompileProcess.buildGreetingPayloadV1("X");
		// 2B BE VERSION + 4B BE CAPABS + UTF-8 ident("X")
		assertEquals("payload length", 7, payload.length);
		assertEquals("version major", 0x01, payload[0] & 0xff);
		assertEquals("version minor", 0x00, payload[1] & 0xff);
		assertEquals("capabs = CRC_REQUIRED", 0x00000001L, beU32(payload, 2));
		assertEquals("ident byte", 'X', payload[6] & 0xff);
	}

	@Test
	public void testSchemaCapabAndFlagPinnedValues() {
		// Rec 34 #34-10a (DD-0080): wire contract shared with
		// frame_v1.hh's capab/flags namespaces, pinned on the C++ side
		// by testframe_v1.cc. Numeric literals on purpose — the test
		// must fail if either side silently changes a value.
		assertEquals("SCHEMA_PAYLOAD flag", 0x08, DecompileProcess.FRAME_FLAG_SCHEMA_PAYLOAD);
		assertEquals("SCHEMA_V1_REQUESTS capab", 0x00000004,
			DecompileProcess.GREETING_CAPAB_SCHEMA_V1_REQUESTS);
		assertEquals("SCHEMA_V1_RESPONSES capab", 0x00000008,
			DecompileProcess.GREETING_CAPAB_SCHEMA_V1_RESPONSES);
	}

	@Test
	public void testParseGreetingPayloadCapabs() {
		// Round-trip our own builder: the host advertises CRC_REQUIRED
		// only (the RESPONSES bit flips in #34-10f when a Java response
		// decoder exists).
		byte[] payload = DecompileProcess.buildGreetingPayloadV1("X");
		assertEquals("own greeting capabs", 0x00000001,
			DecompileProcess.parseGreetingPayloadCapabs(payload));

		// A worker reply advertising schema-v1 requests (CRC|REQUESTS)
		// parses to the full bitmask — big-endian, low byte last.
		byte[] reply = { 0x01, 0x00, 0x00, 0x00, 0x00, 0x05, 'w' };
		assertEquals("peer greeting capabs", 0x00000005,
			DecompileProcess.parseGreetingPayloadCapabs(reply));

		// High-byte bits survive the widening (no sign smear).
		byte[] high = { 0x01, 0x00, (byte) 0x80, 0x00, 0x00, 0x01 };
		assertEquals("high-bit capabs", 0x80000001,
			DecompileProcess.parseGreetingPayloadCapabs(high));
	}

	@Test
	public void testGreetingFrameExactBytes() {
		String ident = "GayHydra-Ghidra (v1 framing)";
		byte[] identBytes = ident.getBytes(StandardCharsets.UTF_8);
		byte[] frame = DecompileProcess.buildGreetingFrameV1(ident);

		int expectedLen = 14 + 6 + identBytes.length; // header+trailer + payload(version+capabs+ident)
		assertEquals("total frame length", expectedLen, frame.length);

		// Header.
		assertArrayEquals("magic", MAGIC, new byte[] { frame[0], frame[1], frame[2], frame[3] });
		assertEquals("type = GREETING", TYPE_GREETING, frame[4] & 0xff);
		assertEquals("flags = CRC_PRESENT", FLAG_CRC_PRESENT, frame[5] & 0xff);
		assertEquals("payload length BE", 6 + identBytes.length, beU32(frame, 6));

		// Payload.
		assertEquals("version major", 0x01, frame[10] & 0xff);
		assertEquals("version minor", 0x00, frame[11] & 0xff);
		assertEquals("capabs = CRC_REQUIRED", 0x00000001L, beU32(frame, 12));
		byte[] identInFrame = new byte[identBytes.length];
		System.arraycopy(frame, 16, identInFrame, 0, identBytes.length);
		assertArrayEquals("ident bytes", identBytes, identInFrame);

		// Trailer: CRC over TYPE|FLAGS|LENGTH|PAYLOAD, computed independently.
		assertEquals("crc trailer matches recompute", expectedCrc(frame),
			beU32(frame, frame.length - 4));
	}

	@Test
	public void testCrcCoversTypeFlagsLengthPayloadNotMagic() {
		// Two frames with different MAGIC bytes but identical TYPE|FLAGS|LEN|PAYLOAD
		// would carry the same CRC, because MAGIC is excluded from the checksum.
		// We verify the documented region directly: flipping a MAGIC byte must
		// NOT change the trailer, but flipping a payload byte MUST.
		byte[] frame = DecompileProcess.buildGreetingFrameV1("AB");
		long crc = beU32(frame, frame.length - 4);

		byte[] magicFlipped = frame.clone();
		magicFlipped[0] ^= 0xff;
		CRC32 c1 = new CRC32();
		c1.update(magicFlipped, 4, magicFlipped.length - 8);
		assertEquals("magic excluded from CRC", crc, c1.getValue());

		byte[] payloadFlipped = frame.clone();
		payloadFlipped[10] ^= 0xff; // version-major byte, inside payload
		CRC32 c2 = new CRC32();
		c2.update(payloadFlipped, 4, payloadFlipped.length - 8);
		assertNotEquals("payload included in CRC", crc, c2.getValue());
	}

	// ------------------------------------------------------------------
	// #33-2.6 framing tunnel (FrameOutputStream / FrameInputStream).
	// Mirrors the C++ testframe_v1.cc tunnel tests: byte-exact framing on
	// flush, transparent unwrap on read, empty-frame skip, error surfacing.
	// Pure byte math over ByteArray streams — no Ghidra runtime.
	// ------------------------------------------------------------------

	private static final int TYPE_RESPONSE = 0x02;

	/** Run a payload through a FrameOutputStream and return the framed wire. */
	private static byte[] tunnelOut(byte[] payload) throws IOException {
		ByteArrayOutputStream transport = new ByteArrayOutputStream();
		FrameOutputStream out = new FrameOutputStream(transport, TYPE_RESPONSE);
		out.write(payload);
		out.flush();
		return transport.toByteArray();
	}

	@Test
	public void testEncodeFrameV1ExtraFlags() {
		// #34-10b: the 3-arg overload ORs extra FLAGS onto CRC_PRESENT and
		// the CRC (which covers TYPE|FLAGS|LENGTH|PAYLOAD) still verifies;
		// the 2-arg form stays byte-identical to extraFlags=0.
		byte[] payload = { 0x03, (byte) 0xAA };
		byte[] plain = DecompileProcess.encodeFrameV1(0x01, payload);
		byte[] zero = DecompileProcess.encodeFrameV1(0x01, 0, payload);
		assertArrayEquals("2-arg == 3-arg with 0", plain, zero);

		byte[] flagged =
			DecompileProcess.encodeFrameV1(0x01, DecompileProcess.FRAME_FLAG_SCHEMA_PAYLOAD,
				payload);
		assertEquals("flags = CRC_PRESENT|SCHEMA_PAYLOAD", 0x09, flagged[5] & 0xff);
		assertEquals("crc trailer matches recompute", expectedCrc(flagged),
			beU32(flagged, flagged.length - 4));
	}

	@Test
	public void testTunnelOneShotFrameFlags() throws IOException {
		// #34-10b: setNextFrameFlags marks exactly one frame; the following
		// frame reverts to CRC_PRESENT only.
		ByteArrayOutputStream transport = new ByteArrayOutputStream();
		FrameOutputStream out = new FrameOutputStream(transport, TYPE_RESPONSE);
		out.setNextFrameFlags(DecompileProcess.FRAME_FLAG_SCHEMA_PAYLOAD);
		out.write(new byte[] { 0x03, 0x01 });
		out.flush();
		out.write(new byte[] { 'v', '0' });
		out.flush();
		byte[] wire = transport.toByteArray();

		assertEquals("two frames", 14 + 2 + 14 + 2, wire.length);
		assertEquals("first frame flagged", 0x09, wire[5] & 0xff);
		assertEquals("first crc verifies", expectedCrc(java.util.Arrays.copyOfRange(wire, 0, 16)),
			beU32(wire, 12));
		assertEquals("second frame unflagged", FLAG_CRC_PRESENT, wire[16 + 5] & 0xff);
	}

	@Test
	public void testTunnelArmedFlagClearedByEmptyFlush() throws IOException {
		// An armed flag must not survive an empty flush — otherwise a later,
		// unrelated command's frame would be mis-marked as schema payload.
		ByteArrayOutputStream transport = new ByteArrayOutputStream();
		FrameOutputStream out = new FrameOutputStream(transport, TYPE_RESPONSE);
		out.setNextFrameFlags(DecompileProcess.FRAME_FLAG_SCHEMA_PAYLOAD);
		out.flush(); // nothing buffered: no frame, but the armed flag clears
		out.write(new byte[] { 'v', '0' });
		out.flush();
		byte[] wire = transport.toByteArray();

		assertEquals("one frame only", 14 + 2, wire.length);
		assertEquals("frame unflagged after cleared arm", FLAG_CRC_PRESENT, wire[5] & 0xff);
	}

	@Test
	public void testTunnelOutOneFramePerFlush() throws IOException {
		byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
		byte[] wire = tunnelOut(payload);

		// Exactly one frame: 14 + 5 bytes, MAGIC + TYPE + recomputed CRC.
		assertEquals("one frame, 14 + payload", 14 + payload.length, wire.length);
		assertArrayEquals("magic", MAGIC, new byte[] { wire[0], wire[1], wire[2], wire[3] });
		assertEquals("type", TYPE_RESPONSE, wire[4] & 0xff);
		assertEquals("flags = CRC_PRESENT", FLAG_CRC_PRESENT, wire[5] & 0xff);
		assertEquals("length BE", payload.length, beU32(wire, 6));
		assertEquals("crc trailer matches recompute", expectedCrc(wire), beU32(wire, wire.length - 4));
	}

	@Test
	public void testTunnelEmptyFlushIsNoop() throws IOException {
		ByteArrayOutputStream transport = new ByteArrayOutputStream();
		FrameOutputStream out = new FrameOutputStream(transport, TYPE_RESPONSE);
		out.flush(); // nothing buffered
		assertEquals("empty flush emits nothing", 0, transport.toByteArray().length);
	}

	@Test
	public void testTunnelRoundtripSingle() throws IOException {
		byte[] payload = "round-trip".getBytes(StandardCharsets.UTF_8);
		byte[] wire = tunnelOut(payload);

		FrameInputStream in = new FrameInputStream(new ByteArrayInputStream(wire));
		byte[] got = in.readNBytes(payload.length);
		assertArrayEquals("payload survives the tunnel", payload, got);
		assertEquals("no error after a clean frame", FrameInputStream.FrameError.OK, in.getLastError());
	}

	@Test
	public void testTunnelMultipleFlushesConcatenate() throws IOException {
		// Two flushes => two frames => the reader sees the bytes concatenated,
		// frame boundaries invisible.
		ByteArrayOutputStream transport = new ByteArrayOutputStream();
		FrameOutputStream out = new FrameOutputStream(transport, TYPE_RESPONSE);
		out.write("AAA".getBytes(StandardCharsets.UTF_8));
		out.flush();
		out.write("BBBB".getBytes(StandardCharsets.UTF_8));
		out.flush();

		byte[] wire = transport.toByteArray();
		assertEquals("two frames", (14 + 3) + (14 + 4), wire.length);

		FrameInputStream in = new FrameInputStream(new ByteArrayInputStream(wire));
		byte[] got = in.readNBytes(7);
		assertArrayEquals("AAABBBB".getBytes(StandardCharsets.UTF_8), got);
	}

	@Test
	public void testTunnelInSkipsEmptyPayloadFrame() throws IOException {
		// An empty-payload frame ahead of a real one must be transparently
		// skipped (symmetric with the out-stream's no-op empty flush).
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		wire.write(DecompileProcess.encodeFrameV1(0x01, new byte[0]));
		wire.write(DecompileProcess.encodeFrameV1(TYPE_RESPONSE, new byte[] { 'Z' }));

		FrameInputStream in = new FrameInputStream(new ByteArrayInputStream(wire.toByteArray()));
		assertEquals('Z', in.read());
		assertEquals(FrameInputStream.FrameError.OK, in.getLastError());
	}

	@Test
	public void testTunnelInEofSetsTruncated() throws IOException {
		byte[] wire = tunnelOut("hi".getBytes(StandardCharsets.UTF_8));
		FrameInputStream in = new FrameInputStream(new ByteArrayInputStream(wire));
		assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), in.readNBytes(2));
		assertEquals("exhausted stream returns EOF", -1, in.read());
		assertEquals("EOF on the next frame is truncation",
			FrameInputStream.FrameError.TRUNCATED, in.getLastError());
	}

	@Test
	public void testTunnelInCrcMismatchSetsError() throws IOException {
		byte[] wire = DecompileProcess.encodeFrameV1(TYPE_RESPONSE,
			"data".getBytes(StandardCharsets.UTF_8));
		wire[10] ^= 0xff; // corrupt first payload byte without fixing the trailer

		FrameInputStream in = new FrameInputStream(new ByteArrayInputStream(wire));
		assertEquals("corrupt frame yields EOF", -1, in.read());
		assertEquals(FrameInputStream.FrameError.CRC_MISMATCH, in.getLastError());
	}

	@Test
	public void testTunnelPreservesV0MarkerBytes() throws IOException {
		// The whole point: v0 \0\0\1\NN bursts (NULs + high bytes) must ride
		// through a frame payload byte-for-byte.
		byte[] payload = {
			0x00, 0x00, 0x01, 0x04,   // a v0 burst marker
			0x00, 0x00, 0x01, 0x0E,   // string-start marker
			'h', 'i',
			0x00, 0x00, 0x01, 0x0F,   // string-end marker
			(byte) 0xFF, 0x00, 0x7F,
		};
		byte[] wire = tunnelOut(payload);
		FrameInputStream in = new FrameInputStream(new ByteArrayInputStream(wire));
		assertArrayEquals(payload, in.readNBytes(payload.length));
	}

	@Test
	public void testTunnelLargePayloadRoundtrip() throws IOException {
		byte[] payload = new byte[100000];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (i & 0xff);
		}
		byte[] wire = tunnelOut(payload);
		assertEquals(14 + payload.length, wire.length);

		FrameInputStream in = new FrameInputStream(new ByteArrayInputStream(wire));
		assertArrayEquals(payload, in.readNBytes(payload.length));
	}
}
