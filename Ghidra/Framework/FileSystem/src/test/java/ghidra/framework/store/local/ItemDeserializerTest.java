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
package ghidra.framework.store.local;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.util.task.TaskMonitor;

/**
 * Rec 18 #18-2 — hardening tests for {@link ItemDeserializer}: the
 * declared-size precheck, the running-counter cap (the decompressed stream
 * must match the header-declared length exactly), and clean-up on failure.
 *
 * <p>The header length field is attacker-controlled. {@link #writePacked}
 * forges a packed file with an arbitrary declared length and an independently
 * chosen zip payload — bypassing the {@code lengthWritten==length} assertion
 * in {@link ItemSerializer#outputItem} — so the malicious mismatch cases can
 * be exercised directly.
 */
public class ItemDeserializerTest extends AbstractGenericTest {

	public ItemDeserializerTest() {
		super();
	}

	private File newPackedFile() throws IOException {
		return new File(createTempFilePath("item-deser", ".pf"));
	}

	private static void writePacked(File f, long declaredLength, byte[] payload)
			throws IOException {
		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
			ObjectOutputStream objOut = new ObjectOutputStream(out);
			objOut.writeLong(ItemSerializer.MAGIC_NUMBER);
			objOut.writeInt(ItemSerializer.FORMAT_VERSION);
			objOut.writeUTF("item");
			objOut.writeUTF("contentType");
			objOut.writeInt(0);
			objOut.writeLong(declaredLength);
			objOut.flush();

			ZipOutputStream zipOut = new ZipOutputStream(out);
			ZipEntry entry = new ZipEntry(ItemSerializer.ZIP_ENTRY_NAME);
			entry.setMethod(ZipEntry.DEFLATED);
			zipOut.putNextEntry(entry);
			zipOut.write(payload);
			zipOut.closeEntry();
			zipOut.finish();
		}
	}

	private static byte[] bytes(int n) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) {
			b[i] = (byte) (i % 251);
		}
		return b;
	}

	private static byte[] readItem(ItemDeserializer d) throws IOException {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		d.saveItem(sink, TaskMonitor.DUMMY);
		return sink.toByteArray();
	}

	@Test
	public void testRoundTripPreservesContent() throws Exception {
		byte[] content = bytes(5000);
		File f = newPackedFile();
		ItemSerializer.outputItem("name", "ctype", 7, content.length,
			new ByteArrayInputStream(content), f, TaskMonitor.DUMMY);

		ItemDeserializer d = new ItemDeserializer(f);
		try {
			assertEquals("name", d.getItemName());
			assertEquals("ctype", d.getContentType());
			assertEquals(7, d.getFileType());
			assertEquals(content.length, d.getLength());
			assertArrayEquals(content, readItem(d));
		}
		finally {
			d.dispose();
		}
	}

	@Test
	public void testEmptyItemRoundTrips() throws Exception {
		File f = newPackedFile();
		ItemSerializer.outputItem("empty", "ctype", 0, 0,
			new ByteArrayInputStream(new byte[0]), f, TaskMonitor.DUMMY);

		ItemDeserializer d = new ItemDeserializer(f);
		try {
			assertEquals(0, d.getLength());
			assertEquals(0, readItem(d).length);
		}
		finally {
			d.dispose();
		}
	}

	@Test
	public void testNegativeDeclaredLengthRejected() throws Exception {
		File f = newPackedFile();
		writePacked(f, -1, bytes(16));
		try {
			new ItemDeserializer(f);
			fail("expected IOException for a negative declared length");
		}
		catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Invalid packed item length"));
		}
	}

	@Test
	public void testExcessiveDeclaredLengthRejected() throws Exception {
		File f = newPackedFile();
		String prev = System.getProperty(ItemDeserializer.MAX_ITEM_LENGTH_PROPERTY);
		System.setProperty(ItemDeserializer.MAX_ITEM_LENGTH_PROPERTY, "1024");
		try {
			writePacked(f, 4096, bytes(16));
			new ItemDeserializer(f);
			fail("expected IOException for a declared length over the maximum");
		}
		catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("exceeds maximum"));
		}
		finally {
			if (prev == null) {
				System.clearProperty(ItemDeserializer.MAX_ITEM_LENGTH_PROPERTY);
			}
			else {
				System.setProperty(ItemDeserializer.MAX_ITEM_LENGTH_PROPERTY, prev);
			}
		}
	}

	@Test
	public void testStreamShorterThanDeclaredRejected() throws Exception {
		File f = newPackedFile();
		// Header claims 4096 bytes; the zip payload carries only 1000.
		writePacked(f, 4096, bytes(1000));
		ItemDeserializer d = new ItemDeserializer(f);
		try {
			readItem(d);
			fail("expected IOException for a stream shorter than declared");
		}
		catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("length mismatch"));
		}
		finally {
			d.dispose();
		}
	}

	@Test
	public void testOverProducingStreamRejected() throws Exception {
		File f = newPackedFile();
		// Header claims 1000 bytes; the zip payload carries 4096 (zip-bomb shape).
		writePacked(f, 1000, bytes(4096));
		ItemDeserializer d = new ItemDeserializer(f);
		try {
			readItem(d);
			fail("expected IOException for a stream exceeding the declared length");
		}
		catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("exceeds declared length"));
		}
		finally {
			d.dispose();
		}
	}
}
