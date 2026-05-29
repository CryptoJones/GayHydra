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

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.junit.Test;

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
}
