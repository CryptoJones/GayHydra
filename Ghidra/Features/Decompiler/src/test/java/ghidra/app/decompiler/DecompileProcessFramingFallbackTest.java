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

import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #491: the "auto" framing mode must not block forever on a peer that never
 * answers the v1 greeting (a stock-protocol decompiler core such as
 * kuna_ghidra). These tests drive {@link DecompileProcess#negotiateFramingV1()}
 * against fake peers attached with {@code attachPeerStreams} — no native
 * process, no Ghidra runtime — so they live in the fast {@code test}
 * sourceset next to {@link DecompileProcessFramingV1Test}.
 */
public class DecompileProcessFramingFallbackTest {

	private static final byte[] MAGIC = { 0x47, 0x48, 0x01, 0x00 };
	private static final int TYPE_GREETING = 0x00;

	/** A deliberately short bound so the tests stay fast. */
	private static final long TEST_TIMEOUT_MS = 150;

	/**
	 * A peer that answers nothing: {@code available()} stays 0 and any read
	 * blocks until {@link #hangUp()} closes the stream (EOF). Counts reads so
	 * a test can assert the Java side consumed nothing while waiting.
	 */
	private static final class SilentPeer extends InputStream {
		private final CountDownLatch eof = new CountDownLatch(1);
		final AtomicInteger reads = new AtomicInteger();

		@Override
		public int available() {
			return 0;
		}

		@Override
		public int read() throws IOException {
			reads.incrementAndGet();
			try {
				eof.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException();
			}
			return -1;
		}

		void hangUp() {
			eof.countDown();
		}
	}

	private String savedTimeoutProperty;

	@Before
	public void setUp() {
		savedTimeoutProperty = System.getProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY);
		System.setProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY,
			Long.toString(TEST_TIMEOUT_MS));
	}

	@After
	public void tearDown() {
		if (savedTimeoutProperty == null) {
			System.clearProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY);
		}
		else {
			System.setProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY, savedTimeoutProperty);
		}
	}

	private static DecompileProcess newProcess(String mode, InputStream in, OutputStream out) {
		DecompileProcess p = new DecompileProcess("/nonexistent/fake-decompiler");
		p.setFramingMode(mode);
		p.attachPeerStreams(in, out);
		return p;
	}

	private static void assertGreetingFrame(byte[] sent) {
		assertTrue("a greeting frame went down the pipe", sent.length >= 14);
		assertArrayEquals("greeting magic", MAGIC,
			new byte[] { sent[0], sent[1], sent[2], sent[3] });
		assertEquals("greeting type", TYPE_GREETING, sent[4] & 0xff);
	}

	@Test
	public void testAutoModeFallsBackToV0WhenPeerIsSilent() throws Exception {
		SilentPeer peer = new SilentPeer();
		ByteArrayOutputStream toPeer = new ByteArrayOutputStream();
		DecompileProcess p = newProcess("auto", peer, toPeer);
		try {
			long start = System.nanoTime();
			p.negotiateFramingV1();
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			assertTrue("fallback fired", p.isGreetingTimedOut());
			assertFalse("channel stays v0", p.isChannelV1());
			assertTrue("waited at least the bound (" + elapsedMs + " ms)",
				elapsedMs >= TEST_TIMEOUT_MS - 1);
			// The bound is what matters: before #491 this call never returned.
			// Allow generous slack for a loaded CI box.
			assertTrue("returned within the bound (" + elapsedMs + " ms)",
				elapsedMs < TEST_TIMEOUT_MS + 2000);
			assertGreetingFrame(toPeer.toByteArray());
			assertEquals("nothing consumed from the peer while waiting", 0, peer.reads.get());
		}
		finally {
			peer.hangUp();
		}
	}

	@Test
	public void testV1ModeDoesNotFallBack() throws Exception {
		SilentPeer peer = new SilentPeer();
		ByteArrayOutputStream toPeer = new ByteArrayOutputStream();
		DecompileProcess p = newProcess("v1", peer, toPeer);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				p.negotiateFramingV1();
			}
			catch (Throwable e) {
				failure.set(e);
			}
		}, "v1-negotiation");
		t.setDaemon(true);
		t.start();

		// Well past the auto-mode bound: v1 mode must still be waiting.
		t.join(TEST_TIMEOUT_MS * 6);
		assertTrue("v1 mode still waiting for the peer", t.isAlive());
		assertFalse("no fallback in v1 mode", p.isGreetingTimedOut());
		assertFalse(p.isChannelV1());
		assertGreetingFrame(toPeer.toByteArray());

		// Release the peer (EOF) so the negotiation ends the way it does today.
		peer.hangUp();
		t.join(TimeUnit.SECONDS.toMillis(10));
		assertFalse("negotiation finished after EOF", t.isAlive());
		assertNull("no exception on EOF", failure.get());
		assertFalse("still not flagged as a timeout fallback", p.isGreetingTimedOut());
		assertFalse(p.isChannelV1());
	}

	@Test
	public void testAutoModeNegotiatesV1WhenPeerReplies() throws Exception {
		// A v1 peer's reply is already in the pipe: no timeout, channel v1.
		byte[] reply = DecompileProcess.encodeFrameV1(TYPE_GREETING,
			DecompileProcess.buildGreetingPayloadV1("fake-v1-peer"));
		ByteArrayOutputStream toPeer = new ByteArrayOutputStream();
		DecompileProcess p = newProcess("auto", new ByteArrayInputStream(reply), toPeer);
		p.negotiateFramingV1();
		assertTrue("v1 negotiated", p.isChannelV1());
		assertFalse("no fallback", p.isGreetingTimedOut());
		assertGreetingFrame(toPeer.toByteArray());
	}

	@Test
	public void testV0ModeSendsNoGreetingAndDoesNotWait() throws Exception {
		SilentPeer peer = new SilentPeer();
		ByteArrayOutputStream toPeer = new ByteArrayOutputStream();
		DecompileProcess p = newProcess("v0", peer, toPeer);
		try {
			p.negotiateFramingV1();
			assertEquals("no bytes sent in v0 mode", 0, toPeer.size());
			assertFalse(p.isChannelV1());
			assertFalse(p.isGreetingTimedOut());
		}
		finally {
			peer.hangUp();
		}
	}

	@Test
	public void testGreetingTimeoutPropertyOverridesDefault() {
		System.setProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY, "1234");
		assertEquals(1234L, DecompileProcess.resolveGreetingTimeoutMs());

		System.clearProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY);
		assertEquals(DecompileProcess.GREETING_TIMEOUT_MS_DEFAULT,
			DecompileProcess.resolveGreetingTimeoutMs());

		System.setProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY, "garbage");
		assertEquals("unparsable falls back to the default",
			DecompileProcess.GREETING_TIMEOUT_MS_DEFAULT,
			DecompileProcess.resolveGreetingTimeoutMs());

		System.setProperty(DecompileProcess.GREETING_TIMEOUT_PROPERTY, "-5");
		assertEquals("non-positive falls back to the default",
			DecompileProcess.GREETING_TIMEOUT_MS_DEFAULT,
			DecompileProcess.resolveGreetingTimeoutMs());

		assertTrue("default is a few hundred milliseconds",
			DecompileProcess.GREETING_TIMEOUT_MS_DEFAULT >= 100 &&
				DecompileProcess.GREETING_TIMEOUT_MS_DEFAULT <= 2000);
	}
}
