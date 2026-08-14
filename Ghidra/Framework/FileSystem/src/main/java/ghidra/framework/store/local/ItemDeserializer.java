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

import generic.jar.ResourceFile;
import ghidra.security.SafeObjectInput;
import ghidra.util.MonitoredInputStream;
import ghidra.util.exception.IOCancelledException;
import ghidra.util.task.TaskMonitor;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <code>ItemDeserializer</code> facilitates the reading of a compressed data stream
 * contained within a "packed" file.  A "packed" file contains the following meta-data
 * which is available after construction:
 * <ul>
 * <li>Item name</li>
 * <li>Content type (int)</li>
 * <li>File type (int)</li>
 * <li>Data length</li>
 * </ul>
 */
public class ItemDeserializer {

	private static final long MAGIC_NUMBER = ItemSerializer.MAGIC_NUMBER;
	private static final int FORMAT_VERSION = ItemSerializer.FORMAT_VERSION;
	private static final String ZIP_ENTRY_NAME = ItemSerializer.ZIP_ENTRY_NAME;

	private final static int IO_BUFFER_SIZE = ItemSerializer.IO_BUFFER_SIZE;

	/**
	 * System property to override {@link #DEFAULT_MAX_ITEM_LENGTH} — the
	 * largest header-declared item length this deserializer will accept.
	 * Set with {@code -Dghidra.store.maxItemLength=<bytes>} for installations
	 * that legitimately store single items larger than the default ceiling.
	 */
	static final String MAX_ITEM_LENGTH_PROPERTY = "ghidra.store.maxItemLength";

	/**
	 * Default maximum declared length (64 GiB) for a single packed item.
	 * The header-declared length is attacker-controlled; a hostile packed
	 * file can claim any value up to {@link Long#MAX_VALUE}. Rec 18 #18-2:
	 * reject an absurd declaration up front so a tiny malicious file cannot
	 * drive an unbounded (zip-bomb-amplified) write into the output stream.
	 */
	static final long DEFAULT_MAX_ITEM_LENGTH = 64L * 1024 * 1024 * 1024;

	private InputStream in;
	private String itemName;
	private String contentType;
	private int fileType;
	private long length;

	private boolean saved = false;

	/**
	 * Constructor.
	 * @param packedFile item to deserialize.
	 * @throws IOException
	 */
	public ItemDeserializer(File packedFile) throws IOException {
		this(new ResourceFile(packedFile));
	}

	public ItemDeserializer(ResourceFile packedFile) throws IOException {

		in = new BufferedInputStream(packedFile.getInputStream());

		// Read header containing: original item name and content type
		boolean success = false;
		try {
			// Rec 19 #19-2: primitive-only header read through the
			// sanctioned SafeObjectInput.headerStream() helper — installs
			// a default-reject class filter so a future readObject() call
			// fails closed instead of silently widening the attack surface.
			ObjectInputStream objIn = SafeObjectInput.headerStream(in);
			if (objIn.readLong() != MAGIC_NUMBER) {
				throw new IOException("Invalid data");
			}
			if (objIn.readInt() != FORMAT_VERSION) {
				throw new IOException("Unsupported data format");
			}

			itemName = objIn.readUTF();
			contentType = objIn.readUTF();
			if (contentType.length() == 0) {
				contentType = null;
			}
			fileType = objIn.readInt();
			length = objIn.readLong();
			// Rec 18 #18-2 declared-size precheck: the length field is
			// attacker-controlled. Reject a negative value (which would
			// corrupt the (int) cast in saveItem and the copy arithmetic)
			// and an absurdly large one up front, before any bulk copy.
			if (length < 0) {
				throw new IOException("Invalid packed item length: " + length);
			}
			long maxItemLength = Long.getLong(MAX_ITEM_LENGTH_PROPERTY, DEFAULT_MAX_ITEM_LENGTH);
			if (length > maxItemLength) {
				throw new IOException("Packed item length " + length + " exceeds maximum " +
					maxItemLength + " (override with -D" + MAX_ITEM_LENGTH_PROPERTY + ")");
			}
			success = true;
		}
		catch (UTFDataFormatException e) {
			throw new IOException("Invalid item data");
		}
		finally {
			if (!success) {
				try {
					in.close();
				}
				catch (IOException e) {
				}
			}
		}
	}

	/**
	 * Close packed-file input stream and free resources.
	 * <p>
	 * All callers obtain an {@code ItemDeserializer} in a try/finally and call
	 * {@code dispose()} in the finally block, so cleanup is deterministic. The
	 * former {@code finalize()} override was a GC-timed backstop on top of that;
	 * it is removed because {@code Object.finalize()} is deprecated for removal.
	 */
	public void dispose() {
		if (in != null) {
			try {
				in.close();
			}
			catch (IOException e) {
			}
			finally {
				in = null;
			}
		}
	}

	/**
	 * Returns packed item name
	 */
	public String getItemName() {
		return itemName;
	}

	/**
	 * Returns packed content type
	 */
	public String getContentType() {
		return contentType;
	}

	/**
	 * Returns packed file type.
	 */
	public int getFileType() {
		return fileType;
	}

	/**
	 * Returns unpacked data length
	 */
	public long getLength() {
		return length;
	}

	/**
	 * Save the item to the specified output stream.
	 * This method may only be invoked once.
	 * @param out
	 * @param monitor
	 * @throws IOException
	 */
	public void saveItem(OutputStream out, TaskMonitor monitor) throws IOCancelledException,
			IOException {

		if (saved) {
			throw new IllegalStateException("Already saved");
		}
		saved = true;

		boolean success = false;
		try {
			ZipInputStream zipIn = new ZipInputStream(in);
			ZipEntry entry = zipIn.getNextEntry();
			if (entry == null || !ZIP_ENTRY_NAME.equals(entry.getName())) {
				throw new IOException("Data error");
			}

			InputStream itemIn = zipIn;
			if (monitor != null) {
				itemIn = new MonitoredInputStream(zipIn, monitor);
				// length is bounded by the constructor precheck; clamp the
				// monitor's int initialize for items larger than 2 GiB.
				monitor.initialize((int) Math.min(length, Integer.MAX_VALUE));
			}

			byte[] buffer = new byte[IO_BUFFER_SIZE];

			// Copy exactly the declared number of bytes, never more.
			long remaining = length;
			long totalWritten = 0;
			while (remaining > 0) {
				int toRead = (int) Math.min(remaining, IO_BUFFER_SIZE);
				int cnt = itemIn.read(buffer, 0, toRead);
				if (cnt < 0) {
					break; // stream ended before the declared length
				}
				out.write(buffer, 0, cnt);
				totalWritten += cnt;
				remaining -= cnt;
			}

			// Rec 18 #18-2 running-counter cap: the decompressed stream must
			// match the declared length exactly — symmetric with the
			// ItemSerializer.outputItem() lengthWritten==length invariant.
			// A short stream means a truncated/forged file; a stream that
			// still has bytes past the declared length is an over-producing
			// (zip-bomb) payload. Either way, fail closed.
			if (totalWritten != length) {
				throw new IOException("Packed item length mismatch: wrote " + totalWritten +
					" bytes, header declared " + length);
			}
			if (itemIn.read() != -1) {
				throw new IOException("Packed item stream exceeds declared length " + length);
			}

			success = true;
		}
		finally {
			// Rec 18 #18-2 clean-up on failure: a mid-copy error or cancel
			// (e.g. IOCancelledException from the monitor) must not leak the
			// underlying packed-file stream.
			if (!success) {
				dispose();
			}
		}
	}

}
