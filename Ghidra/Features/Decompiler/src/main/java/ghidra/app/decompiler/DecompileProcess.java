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

import static ghidra.program.model.pcode.AttributeId.*;
import static ghidra.program.model.pcode.ElementId.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import ghidra.app.decompiler.ipc.CommandRequestCodec;
import ghidra.app.decompiler.ipc.DecompileRequestCodec;
import ghidra.app.decompiler.ipc.IpcCommandId;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.InjectPayload;
import ghidra.program.model.lang.UnknownInstructionException;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.pcode.*;
import ghidra.util.Msg;
import ghidra.util.exception.NotFoundException;
import ghidra.util.timer.GTimer;
import ghidra.util.timer.GTimerMonitor;

/**
 *
 *
 * Class for communicating with a single decompiler process.
 * The process controls decompilation for a single Program.
 * The process is initiated by the registerProgram method.
 * If the process is ready, the statusGood flag will be set
 * to true.  This flag must be checked via the isReady method
 * prior to invoking any of the public methods.  If the
 * process isn't ready, the only way to recover is by
 * reissuing the registerProgram call and making any other
 * necessary initialization calls.
 */

public class DecompileProcess {
	
	private static boolean errorDisplayed = false; // launch failure previously displayed

	//	public static DecompileProcess decompProcess = null;
	private final static byte[] command_start = { 0, 0, 1, 2 };
	private final static byte[] command_end = { 0, 0, 1, 3 };
	private final static byte[] query_response_start = { 0, 0, 1, 8 };
	private final static byte[] query_response_end = { 0, 0, 1, 9 };
	private final static byte[] string_start = { 0, 0, 1, 14 };
	private final static byte[] string_end = { 0, 0, 1, 15 };
	private final static byte[] exception_start = { 0, 0, 1, 10 };
	private final static byte[] exception_end = { 0, 0, 1, 11 };
	private final static byte[] byte_start = { 0, 0, 1, 12 };
	private final static byte[] byte_end = { 0, 0, 1, 13 };

	// Rec 33 IPC framing v1 (docs/decisions/0005-ipc-framing-v1.md). After
	// a successful greeting the legacy command-loop bytes tunnel inside v1
	// frames (one frame per flush). Since #34-10b (DD-0080) the schematized
	// commands additionally go out as schema-v1 FlatBuffers payloads when
	// the worker advertises the capability — adopted one command at a time
	// across #34-10b..e; everything else below remains v0 marshaling.
	private final static byte[] FRAME_MAGIC = { 0x47, 0x48, 0x01, 0x00 };
	private final static int FRAME_TYPE_GREETING = 0x00;
	private final static int FRAME_TYPE_COMMAND = 0x01;
	private final static int FRAME_FLAG_CRC_PRESENT = 0x01;
	private final static int FRAME_FLAGS_RESERVED = 0x06; // compression|continuation
	// Rec 34 #34-10 (DD-0080): frame body is [u8 command-id][FlatBuffers
	// bytes] instead of tunneled v0 bytes. Known at the frame layer (not
	// in FRAME_FLAGS_RESERVED); "reject unless negotiated" is the
	// dispatch layer's job. Armed per frame by sendSchemaCommand (#34-10b).
	final static int FRAME_FLAG_SCHEMA_PAYLOAD = 0x08;
	private final static int GREETING_VERSION_MAJOR = 0x01;
	private final static int GREETING_VERSION_MINOR = 0x00;
	private final static int GREETING_CAPAB_CRC_REQUIRED = 0x00000001;
	// Rec 34 #34-10a (DD-0080) capability bits, mirroring frame_v1.hh's
	// capab namespace (pinned cross-language by the unit tests). The
	// worker advertises REQUESTS once its dispatch decodes schema
	// payloads (#34-10b+); this host advertises RESPONSES once a Java
	// response decoder exists (#34-10f+).
	final static int GREETING_CAPAB_SCHEMA_V1_REQUESTS = 0x00000004;
	final static int GREETING_CAPAB_SCHEMA_V1_RESPONSES = 0x00000008;
	private final static int FRAME_MAX_PAYLOAD_LEN = 16 * 1024 * 1024;
	private final static String CLIENT_IDENT = "GayHydra-Ghidra (v1 framing)";

	// Configured framing preference: "auto" (send greeting, fall back to
	// v0 if the peer does not reply v1), "v1" (send greeting), or "v0"
	// (legacy: emit no greeting). Set by DecompInterface before each
	// registerProgram. Default matches DD-0005's flip of GayHydra clients
	// to v1-by-default.
	private volatile String framingMode = "auto";
	// True once a v1 greeting handshake has completed with the peer.
	private volatile boolean channelV1 = false;
	// The peer greeting's CAPABS bitmask, recorded by the v1 handshake
	// (#34-10a); 0 while the channel is v0. The payload-v1 go-live
	// slices key off this: requests go v1 only when the worker
	// advertised GREETING_CAPAB_SCHEMA_V1_REQUESTS.
	private volatile int peerCapabilities = 0;
	// The COMMAND-direction frame wrapper once v1 negotiates (#34-10b) —
	// the same object nativeOut refers to, kept under its concrete type
	// so schema sends can arm per-frame FLAGS. Null while v0.
	private volatile FrameOutputStream frameOut;
	// Schema-payload kill switch (#34-10b, the DD-0005 staging pattern):
	// "auto" sends schema-v1 request payloads whenever the worker
	// advertised the capability; "off" keeps v0 payloads on a v1 channel.
	// Set by DecompInterface from the decompiler.schemapayload property.
	private volatile String schemaPayloadMode = "auto";
	// Count of schema-v1 request frames sent (#34-10b). Package-private
	// observable for the e2e test: without it a silent fallback to v0
	// payloads would pass every byte-identical assertion.
	private volatile int schemaV1RequestsSent = 0;

	//private static final int MAXIMUM_RESULT_SIZE = 50 * 1024 * 1024; // maximum result size in bytes to allow from decompiler

	private Runtime runtime = Runtime.getRuntime();
	private String[] exepath; // Path to the decompiler executable
	private Runnable timeoutRunnable;

	// Note: volatile for variables modified when we shutdown, potential from different thread
	private volatile Process nativeProcess;
	private volatile InputStream nativeIn;   // Input from decompiler
	private volatile OutputStream nativeOut; // Output to decompiler
	private volatile boolean statusGood;     // true if decompiler process is running

	private int archId = -1;              // architecture id for decomp process
	private DecompileCallback callback;   // Callback interface for decompiler
	private String programSource;		// String describing program for error reports
	private int maxResultSizeMBYtes = 50; // maximum result size in MBytes to allow from decompiler

	private PackedDecode paramDecoder;			// Decoder to use for queries from the decompiler
	private PatchPackedEncode resultEncoder;	// Encoder to use for query responses
	private StringIngest stringDecoder;		// Ingest of exception and status messages

	public enum DisposeState {
		NOT_DISPOSED,        // Process was/is not disposed
		DISPOSED_ON_TIMEOUT, // A timeout occurred
		DISPOSED_ON_CANCEL, // The process was cancelled
		DISPOSED_ON_STARTUP_FAILURE // The executable failed to start
	}

	private volatile DisposeState disposestate = DisposeState.NOT_DISPOSED; // How this process was (or was not) disposed

	public DecompileProcess(String path) {

		exepath = new String[] { path };
//		exepath = new String[] { "/usr/bin/valgrind", "--tool=memcheck", "--leak-check=yes", "--track-origins=yes", "--error-limit=no", "--log-file=/tmp/decompvalgrindout%p.txt", path };

		timeoutRunnable = new Runnable() {
			@Override
			public void run() {
				dispose();
				disposestate = DisposeState.DISPOSED_ON_TIMEOUT;
			}
		};
		stringDecoder = new StringIngest();
	}
	
	private static synchronized boolean getAndSetErrorDisplayed() {
		boolean b = errorDisplayed;
		if (!b) {
			errorDisplayed = true;
		}
		return b;
	}

	public void dispose() {
		if (disposestate != DisposeState.NOT_DISPOSED) {
			return;
		}

		disposestate = DisposeState.DISPOSED_ON_CANCEL;
		statusGood = false;

		// Disposing sometimes hangs and we don't want to hang the swing thread.
		DecompilerDisposer.dispose(nativeProcess, nativeOut, nativeIn);
	}

	public DisposeState getDisposeState() {
		return disposestate;
	}

	private void setup() throws IOException {
		if (disposestate != DisposeState.NOT_DISPOSED) {
			throw new IOException("Decompiler has been disposed");
		}
		if (nativeProcess != null) {
			// Something bad happened to the process or the interface
			// and now we try to restart
			nativeProcess.destroy(); // Make sure previous bad process is killed           			
			nativeProcess = null;
		}
		if (exepath == null || exepath.length == 0 || exepath[0] == null) {
			throw new IOException("Could not find decompiler executable");
		}
		IOException exc = null;
		statusGood = false;
		
		try {
			nativeProcess = runtime.exec(exepath);
			
			// Give process time to load and report possible error
			try {
				nativeProcess.waitFor(200, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				// Restore interrupted thread state since we are continuing with setup.
				// It does not appear that the decompiler interface is affected by an
				// interrupted thread so why stop here.
				Thread.currentThread().interrupt();
			}
			
			nativeIn = nativeProcess.getInputStream();
			nativeOut = nativeProcess.getOutputStream();
			
			statusGood = nativeProcess.isAlive();
			if (!statusGood) {
				if (!getAndSetErrorDisplayed()) {
					String err = new String(nativeProcess.getErrorStream().readAllBytes());
					String errorDetail = "Decompiler executable may not be compatible with your system and may need to be rebuilt.\n" +
							"(see GettingStarted.md, 'Building Native Components').\n\n" +
							err;		
					Msg.showError(this, null, "Failed to launch Decompiler process", errorDetail);
				}
			}
		}
		catch (IOException e) {
			exc = e; // permission error, etc.
		}
		finally {
			if (!statusGood) {
				disposestate = DisposeState.DISPOSED_ON_STARTUP_FAILURE;
				if (nativeProcess != null) {
					nativeProcess.destroy();          			
					nativeProcess = null;
				}
				if (exc == null) {
					exc = new IOException("Decompiler process failed to launch (see log for details)");
				}
				throw exc;
			}
		}
	}

	private int readToBurst() throws IOException {
		if (nativeIn == null) {
			// we've been disposed!
			// (not sure if throwing an exception the best)
			throw new IOException("Decompiler disposed!");
		}

		int cur;
		for (;;) {
			do {
				cur = nativeIn.read();
			}
			while (cur > 0);
			if (cur == -1) {
				break;
			}
			do {
				cur = nativeIn.read();
			}
			while (cur == 0);
			if (cur == 1) {
				cur = nativeIn.read();
				if (cur == -1) {
					break;
				}
				return cur;
			}
			if (cur == -1) {
				break;
			}
		}
		throw new IOException("Decompiler process died");
	}

	private void readToResponse() throws IOException, DecompileException {
		nativeOut.flush(); // Make sure decompiler has access to all the info it has been sent
		int type;
		do {
			type = readToBurst();
		}
		while ((type & 1) == 1);
		if (type == 10) {
			generateException();
		}
		if (type == 6) {
			return;
		}
		throw new IOException("Ghidra/decompiler alignment error");
	}

	private int readToBuffer(ByteIngest buf) throws IOException {
		int cur;
		for (;;) {
			buf.ingestStreamToNextTerminator(nativeIn);
			do {
				cur = nativeIn.read();
			}
			while (cur == 0);
			if (cur == 1) {
				cur = nativeIn.read();
				if (cur > 0) {
					return cur;
				}
			}
			if (cur == -1) {
				break;
			}
		}
		throw new IOException("Decompiler process died");
	}

	private void readQueryParam(ByteIngest ingester) throws IOException {
		int type = readToBurst();
		if (type != 14) {
			throw new IOException("GHIDRA/decompiler alignment error");
		}
		ingester.open(1 << 16, programSource);
		type = readToBuffer(ingester);
		if (type != 15) {
			throw new IOException("GHIDRA/decompiler alignment error");
		}
		ingester.endIngest();
	}

	private void writeString(String msg) throws IOException {
		write(string_start);
		write(msg.getBytes());
		write(string_end);
	}

	private void writeString(CachedEncoder byteResult) throws IOException {
		if (nativeOut == null) {
			return;
		}
		write(string_start);
		byteResult.writeTo(nativeOut);
		write(string_end);
	}

	/**
	 * Set the IPC framing preference for the next {@link #registerProgram}.
	 * Accepts "auto", "v1", or "v0"; anything else defaults to "auto".
	 * @param mode the framing preference
	 */
	public void setFramingMode(String mode) {
		if ("v0".equals(mode) || "v1".equals(mode) || "auto".equals(mode)) {
			framingMode = mode;
		}
		else {
			framingMode = "auto";
		}
	}

	/**
	 * Set the schema-payload preference for subsequent commands (#34-10b).
	 * Accepts "auto" (send schema-v1 request payloads whenever the worker
	 * advertised the capability) or "off" (keep v0 payloads even on a v1
	 * channel); anything else defaults to "auto".
	 * @param mode the schema-payload preference
	 */
	public void setSchemaPayloadMode(String mode) {
		schemaPayloadMode = "off".equals(mode) ? "off" : "auto";
	}

	/**
	 * {@return the number of schema-v1 request frames sent to the peer.}
	 * Package-private test observable (#34-10b): distinguishes a real
	 * schema-payload exchange from a silent fallback to v0.
	 */
	int getSchemaV1RequestsSent() {
		return schemaV1RequestsSent;
	}

	/**
	 * {@return true if a v1 framing greeting was negotiated with the peer.}
	 */
	public boolean isChannelV1() {
		return channelV1;
	}

	/**
	 * {@return the peer greeting's CAPABS bitmask, or 0 while the channel
	 * is v0 (a v0 channel has no capability concept).}
	 */
	public int getPeerCapabilities() {
		return peerCapabilities;
	}

	/**
	 * {@return true if the negotiated peer advertised that it decodes
	 * FlatBuffers schema-v1 request payloads (DD-0080 #34-10).}
	 */
	public boolean peerAcceptsSchemaV1Requests() {
		return (peerCapabilities & GREETING_CAPAB_SCHEMA_V1_REQUESTS) != 0;
	}

	/**
	 * Build a complete v1 GREETING frame: MAGIC + TYPE + FLAGS + LENGTH +
	 * PAYLOAD + CRC32. The CRC (java.util.zip.CRC32 == IEEE 802.3) covers
	 * TYPE|FLAGS|LENGTH|PAYLOAD, not MAGIC — matching frame_v1.cc.
	 * Package-private for unit testing of the exact wire bytes.
	 * @param ident the free-text peer identity placed in the greeting payload
	 * @return the encoded greeting frame bytes
	 */
	static byte[] buildGreetingFrameV1(String ident) {
		return encodeFrameV1(FRAME_TYPE_GREETING, buildGreetingPayloadV1(ident));
	}

	/**
	 * Build the greeting payload: 2-byte BE VERSION (major.minor), 4-byte
	 * BE CAPABS (CRC-required), then the UTF-8 ident bytes.
	 */
	static byte[] buildGreetingPayloadV1(String ident) {
		byte[] id = ident.getBytes(StandardCharsets.UTF_8);
		byte[] payload = new byte[6 + id.length];
		payload[0] = (byte) GREETING_VERSION_MAJOR;
		payload[1] = (byte) GREETING_VERSION_MINOR;
		payload[2] = (byte) ((GREETING_CAPAB_CRC_REQUIRED >>> 24) & 0xff);
		payload[3] = (byte) ((GREETING_CAPAB_CRC_REQUIRED >>> 16) & 0xff);
		payload[4] = (byte) ((GREETING_CAPAB_CRC_REQUIRED >>> 8) & 0xff);
		payload[5] = (byte) (GREETING_CAPAB_CRC_REQUIRED & 0xff);
		System.arraycopy(id, 0, payload, 6, id.length);
		return payload;
	}

	/**
	 * Encode a single v1 frame. Always sets the CRC_PRESENT flag and
	 * appends the 4-byte BE CRC32 trailer.
	 */
	static byte[] encodeFrameV1(int type, byte[] payload) {
		return encodeFrameV1(type, 0, payload);
	}

	/**
	 * Encode a single v1 frame carrying extra FLAGS bits beyond
	 * CRC_PRESENT (#34-10b: {@link #FRAME_FLAG_SCHEMA_PAYLOAD}). The CRC
	 * covers TYPE|FLAGS|LENGTH|PAYLOAD, so the flags are checksummed.
	 */
	static byte[] encodeFrameV1(int type, int extraFlags, byte[] payload) {
		int len = payload.length;
		byte[] frame = new byte[14 + len];
		frame[0] = FRAME_MAGIC[0];
		frame[1] = FRAME_MAGIC[1];
		frame[2] = FRAME_MAGIC[2];
		frame[3] = FRAME_MAGIC[3];
		frame[4] = (byte) type;
		frame[5] = (byte) (FRAME_FLAG_CRC_PRESENT | extraFlags);
		frame[6] = (byte) ((len >>> 24) & 0xff);
		frame[7] = (byte) ((len >>> 16) & 0xff);
		frame[8] = (byte) ((len >>> 8) & 0xff);
		frame[9] = (byte) (len & 0xff);
		System.arraycopy(payload, 0, frame, 10, len);
		CRC32 crc = new CRC32();
		crc.update(frame, 4, 6 + len); // TYPE|FLAGS|LENGTH|PAYLOAD
		long c = crc.getValue();
		frame[10 + len] = (byte) ((c >>> 24) & 0xff);
		frame[11 + len] = (byte) ((c >>> 16) & 0xff);
		frame[12 + len] = (byte) ((c >>> 8) & 0xff);
		frame[13 + len] = (byte) (c & 0xff);
		return frame;
	}

	/**
	 * Emit the v1 greeting (unless framing is forced to v0) and read the
	 * peer's reply. On a well-formed GREETING reply with a matching major
	 * version, {@link #channelV1} is set. A v0 peer (the native command
	 * loop's default) leaves no reply path: a v0-mode client emits nothing
	 * and the peer's non-consuming peek keeps the stream byte-identical for
	 * the legacy reader.
	 */
	private void negotiateFramingV1() throws IOException {
		channelV1 = false;
		peerCapabilities = 0;
		frameOut = null;
		if ("v0".equals(framingMode)) {
			return;
		}
		write(buildGreetingFrameV1(CLIENT_IDENT));
		if (nativeOut != null) {
			nativeOut.flush();
		}
		channelV1 = readGreetingReplyV1();
		if (channelV1) {
			// Channel negotiated v1: tunnel the legacy command-loop bytes
			// through v1 frames. Each flush of nativeOut emits one COMMAND
			// frame; each refill of nativeIn unwraps one inbound frame. The
			// raw greeting exchange above is already fully consumed, and the
			// v0 marshaling inside the frames is untouched, so every write/
			// read helper rides transparently from here on. frameOut keeps
			// the wrapper's concrete type so schema-payload sends (#34-10b)
			// can arm per-frame FLAGS; both fields refer to the one object.
			frameOut = new FrameOutputStream(nativeOut, FRAME_TYPE_COMMAND);
			nativeOut = frameOut;
			nativeIn = new FrameInputStream(nativeIn);
		}
	}

	/**
	 * Read one v1 frame from the decompiler and validate it as a GREETING
	 * with a matching major version and correct CRC. Returns false (rather
	 * than throwing) on EOF / magic mismatch / bad CRC so the caller can
	 * proceed on the legacy v0 path.
	 */
	private boolean readGreetingReplyV1() throws IOException {
		if (nativeIn == null) {
			return false;
		}
		byte[] hdr = new byte[10]; // MAGIC(4) + TYPE(1) + FLAGS(1) + LENGTH(4)
		if (!readFully(hdr, 10)) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			if ((hdr[i] & 0xff) != (FRAME_MAGIC[i] & 0xff)) {
				return false;
			}
		}
		int type = hdr[4] & 0xff;
		int flags = hdr[5] & 0xff;
		long len = ((long) (hdr[6] & 0xff) << 24) | ((hdr[7] & 0xff) << 16) |
			((hdr[8] & 0xff) << 8) | (hdr[9] & 0xff);
		if (len > FRAME_MAX_PAYLOAD_LEN) {
			return false;
		}
		if ((flags & FRAME_FLAGS_RESERVED) != 0) {
			return false;
		}
		byte[] payload = new byte[(int) len];
		if (len > 0 && !readFully(payload, (int) len)) {
			return false;
		}
		byte[] crcBytes = new byte[4];
		if (!readFully(crcBytes, 4)) {
			return false;
		}
		CRC32 crc = new CRC32();
		crc.update(hdr, 4, 6); // TYPE|FLAGS|LENGTH
		if (len > 0) {
			crc.update(payload, 0, (int) len);
		}
		long expected = crc.getValue();
		long actual = ((long) (crcBytes[0] & 0xff) << 24) | ((crcBytes[1] & 0xff) << 16) |
			((crcBytes[2] & 0xff) << 8) | (crcBytes[3] & 0xff);
		if (actual != expected) {
			return false;
		}
		if (type != FRAME_TYPE_GREETING || payload.length < 6) {
			return false;
		}
		// Reject a mismatched major version; minor bumps are compatible.
		if ((payload[0] & 0xff) != GREETING_VERSION_MAJOR) {
			return false;
		}
		// Record the peer's capabilities (#34-10a) — the payload-v1
		// go-live slices key off these bits.
		peerCapabilities = parseGreetingPayloadCapabs(payload);
		return true;
	}

	/**
	 * Extract the 4-byte big-endian CAPABS bitmask from a greeting
	 * payload (VERSION | CAPABS | IDENT). Package-private for unit
	 * testing of the exact wire layout.
	 * @param payload the greeting payload (at least 6 bytes)
	 * @return the CAPABS bitmask
	 */
	static int parseGreetingPayloadCapabs(byte[] payload) {
		return ((payload[2] & 0xff) << 24) | ((payload[3] & 0xff) << 16) |
			((payload[4] & 0xff) << 8) | (payload[5] & 0xff);
	}

	/**
	 * Read exactly {@code n} bytes from the decompiler into {@code buf}.
	 * @return true on success, false on EOF before {@code n} bytes arrive
	 */
	private boolean readFully(byte[] buf, int n) throws IOException {
		int off = 0;
		while (off < n) {
			int r = nativeIn.read(buf, off, n - off);
			if (r < 0) {
				return false;
			}
			off += r;
		}
		return true;
	}

	private void generateException() throws IOException, DecompileException {
		readQueryParam(stringDecoder);
		String type = stringDecoder.toString();
		readQueryParam(stringDecoder);
		String message = stringDecoder.toString();
		readToBurst(); // Read exception terminator
		if (type.equals("alignment")) {
			throw new IOException("Alignment error: " + message);
		}
		throw new DecompileException(type, message);
	}

	private void readResponse(ByteIngest mainResponse) throws IOException, DecompileException {
		mainResponse.clear();
		readToResponse();
		int type = readToBurst();
		int commandId;
		ByteIngest currentResponse = null;

		while (type != 7) {
			switch (type) {
				case 4:
					readQueryParam(paramDecoder);
					try {
						commandId = paramDecoder.openElement();
						switch (commandId) {
							case COMMAND_ISNAMEUSED:
								isNameUsed();
								break;
							case COMMAND_GETBYTES:
								getBytes();						// getBytes
								break;
							case COMMAND_GETCOMMENTS:
								getComments();
								break;
							case COMMAND_GETCALLFIXUP:
								getPcodeInject(InjectPayload.CALLFIXUP_TYPE);
								break;
							case COMMAND_GETCALLOTHERFIXUP:
								getPcodeInject(InjectPayload.CALLOTHERFIXUP_TYPE);
								break;
							case COMMAND_GETCALLMECH:
								getPcodeInject(InjectPayload.CALLMECHANISM_TYPE);
								break;
							case COMMAND_GETPCODEEXECUTABLE:
								getPcodeInject(InjectPayload.EXECUTABLEPCODE_TYPE);
								break;
							case COMMAND_GETCPOOLREF:
								getCPoolRef();
								break;
							case COMMAND_GETEXTERNALREF:
								getExternalRef();
								break;
							case COMMAND_GETMAPPEDSYMBOLS:
								getMappedSymbols();
								break;
							case COMMAND_GETNAMESPACEPATH:
								getNamespacePath();
								break;
							case COMMAND_GETPCODE:
								getPcode();
								break;
							case COMMAND_GETREGISTER:
								getRegister();
								break;
							case COMMAND_GETREGISTERNAME:
								getRegisterName();
								break;
							case COMMAND_GETSTRINGDATA:
								getStringData();
								break;
							case COMMAND_GETCODELABEL:
								getCodeLabel();
								break;
							case COMMAND_GETDATATYPE:
								getDataType();
								break;
							case COMMAND_GETTRACKEDREGISTERS:
								getTrackedRegisters();
								break;
							case COMMAND_GETUSEROPNAME:
								getUserOpName();
								break;
							default:
								throw new Exception("Unsupported decompiler query");
						}
					}
					catch (Exception e) { // Catch ANY exception query generates
						// and pass it down to decompiler
						write(exception_start);
						String extype = e.getClass().getName();
						String msg = e.getMessage();
						if (msg == null) {
							msg = "";
						}
						writeString(extype);
						writeString(msg);
						write(exception_end);

						if (disposestate == DisposeState.NOT_DISPOSED) {
							Msg.error(this, "Unexpected Exception: " + e.getMessage(), e);
						}
					}
					nativeOut.flush(); // Make sure decompiler receives response
					readToBurst(); // Read query terminator
					break;
				case 6:
					throw new IOException("GHIDRA/decompiler out of alignment");
				case 10:
					generateException();
					break;
				case 14:			// Start of the main decompiler output
					if (currentResponse != null) {
						throw new IOException("Nested decompiler output");
					}
					// Allocate storage buffer for the result, which is generally not tiny. So we
					// start with any initial allocation of 1024 bytes, also give an absolute upper bound
					// determined by maxResultSizeMBYtes
					currentResponse = mainResponse;
					currentResponse.open(maxResultSizeMBYtes << 20, programSource);
					break;
				case 15:			// This is the end of the main decompiler output
					if (currentResponse == null) {
						throw new IOException("Mismatched string header");
					}
					currentResponse.endIngest();
					currentResponse = null;		// Reset current buffer as a native message may follow
					break;
				case 16:			// Beginning of any native message from the decompiler
					if (currentResponse != null) {	// Beginning of native message before end of main response
						currentResponse.clear();	// Don't try to parse main response
					}
					currentResponse = stringDecoder;
					currentResponse.open(1 << 20, programSource);
					break;
				case 17:			// End of the native message from the decompiler
					if (currentResponse == null) {
						throw new IOException("Mismatched message header");
					}
					currentResponse.endIngest();
					callback.setNativeMessage(currentResponse.toString());
					currentResponse = null;
					break;
				default:
					throw new IOException("GHIDRA/decompiler alignment error");

			}
			if (currentResponse == null) {
				type = readToBurst();
			}
			else {
				type = readToBuffer(currentResponse);
			}
		}
	}

	// Calls to the decompiler

	/**
	 * Initialize decompiler for a particular platform
	 * @param cback = callback object for decompiler
	 * @param pspecxml = string containing .pspec xml
	 * @param cspecxml = string containing .cspec xml
	 * @param tspecxml = XML string containing translator spec
	 * @param coretypesxml = XML description of core data-types
	 * @param program is the program being registered
	 * @throws IOException for problems with the pipe to the decompiler process
	 * @throws DecompileException for problems executing the command
	 */
	public synchronized void registerProgram(DecompileCallback cback, String pspecxml,
			String cspecxml, String tspecxml, String coretypesxml, Program program)
			throws IOException, DecompileException {
		callback = cback;
		programSource = program.getName();

		// Decompiler process may callback during the registerProgram operation
		// so provide query/reponse decoding/encoding
		paramDecoder = new PackedDecode(program.getAddressFactory());
		resultEncoder = new PatchPackedEncode();

		StringIngest response = new StringIngest();	// Don't use stringDecoder

		setup();
		negotiateFramingV1();
		try {
			if (schemaPayloadAvailable()) {
				// #34-10c: the greeting just negotiated, so the worker's
				// advertised capability is already known inside this very
				// call. The callback decoders above stay live — the worker
				// queries back during registration regardless of how the
				// request payload was encoded.
				writeSchemaRequest(IpcCommandId.REGISTER_PROGRAM,
					CommandRequestCodec.encodeRegisterProgramRequest(pspecxml, cspecxml, tspecxml,
						coretypesxml));
			}
			else {
				write(command_start);
				writeString("registerProgram");
				writeString(pspecxml);
				writeString(cspecxml);
				writeString(tspecxml);
				writeString(coretypesxml);
				write(command_end);
			}
			readResponse(response);
		}
		catch (IOException e) {
			statusGood = false;
			throw e;
		}
		archId = Integer.parseInt(response.toString());
	}

	/**
	 * Free decompiler resources
	 * @return 1 if a program was actively deregistered, 0 otherwise
	 * @throws IOException for problems with the pipe to the decompiler
	 * @throws DecompileException for problems executing the command
	 */
	public synchronized int deregisterProgram() throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException("deregisterProgram called on bad process");
		}
		// Once a program is deregistered, the process is never
		// used again
		statusGood = false;
		if (schemaPayloadAvailable()) {
			// #34-10c: same single-flagged-frame shape as the other
			// schematized commands; the response stays the v0 meta-command.
			writeSchemaRequest(IpcCommandId.DEREGISTER_PROGRAM,
				CommandRequestCodec.encodeDeregisterProgramRequest(Integer.toString(archId)));
		}
		else {
			write(command_start);
			writeString("deregisterProgram");
			writeString(Integer.toString(archId));
			write(command_end);
		}
		paramDecoder = null;		// Don't expect callback queries
		resultEncoder = null;
		StringIngest response = new StringIngest();		// Don't use stringResponse
		readResponse(response);
		int res = Integer.parseInt(response.toString());
		callback = null;
		programSource = null;
		paramDecoder = null;
		resultEncoder = null;
		return res;
	}

	/**
	 * Send a single command to the decompiler with no parameters and return response
	 * @param command is the name of the command to execute
	 * @param response the response accumulator
	 * @throws IOException for any problems with the pipe to the decompiler process
	 * @throws DecompileException for any problems executing the command
	 */
	public synchronized void sendCommand(String command, ByteIngest response)
			throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException(command + " called on bad process");
		}
		if ("flushNative".equals(command) && schemaPayloadAvailable()) {
			// #34-10b (DD-0080): first schema-v1 command go-live. The branch
			// keys on the literal command name — sendCommand is shared with
			// un-schematized commands (e.g. getSignatureSettings), which must
			// keep riding v0. #34-10c..e add matching branches per command.
			sendSchemaCommand(IpcCommandId.FLUSH_NATIVE,
				CommandRequestCodec.encodeFlushNativeRequest(Integer.toString(archId)), response);
			return;
		}
		paramDecoder = null;	// Don't expect callback queries
		resultEncoder = null;
		try {
			write(command_start);
			writeString(command);
			writeString(Integer.toString(archId));
			write(command_end);
			readResponse(response);
		}
		catch (IOException e) {
			statusGood = false;
			throw e;
		}
	}

	/**
	 * {@return true if requests may go out as schema-v1 payloads}: the v1
	 * channel is up, the worker advertised the capability, and the
	 * kill switch ({@code decompiler.schemapayload}) is not "off".
	 */
	private boolean schemaPayloadAvailable() {
		return frameOut != null && peerAcceptsSchemaV1Requests() &&
			!"off".equals(schemaPayloadMode);
	}

	/**
	 * {@return true if the commands whose schema-v1 form changes what the
	 * caller must encode will go out as schema-v1 requests}. For setOptions
	 * and structureGraph the document must be encoded as XML text via
	 * {@code XmlEncode}, not {@code PatchPackedEncode} (#34-10d-2, DD-0080
	 * addendum: packed binary cannot ride a FlatBuffers UTF-8 string field);
	 * for decompileAt the entry must be a default-space bare offset rather
	 * than a packed {@code <addr>} document (#34-10e). In each case the
	 * choice has to be made by {@link DecompInterface} <i>before</i> the
	 * send, so this gate is public where the string-field commands' gates
	 * stay behind the command-name branches.
	 */
	public synchronized boolean schemaDocumentCommandsAvailable() {
		return schemaPayloadAvailable();
	}

	/**
	 * Send the setOptions command as a schema-v1 request (#34-10d-2): the
	 * caller has already encoded the {@code <optionslist>} document as XML
	 * text. Callers must check {@link #schemaDocumentCommandsAvailable()}
	 * first; there is no packed fallback at this layer.
	 * @param optionsXml the XML-text {@code <optionslist>} document
	 * @param response the response accumulator
	 * @throws IOException for any problems with the pipe to the decompiler process
	 * @throws DecompileException for any problems executing the command
	 */
	public synchronized void sendSetOptionsSchema(String optionsXml, ByteIngest response)
			throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException("setOptions called on bad process");
		}
		sendSchemaCommand(IpcCommandId.SET_OPTIONS,
			CommandRequestCodec.encodeSetOptionsRequest(Integer.toString(archId), optionsXml),
			response);
	}

	/**
	 * Send the structureGraph command as a schema-v1 request (#34-10d-2) with
	 * the v0 {@link #sendCommandTimeout} semantics (timeout + callback
	 * decoders) preserved. The caller has already encoded the {@code <block>}
	 * control-flow document as XML text and must check
	 * {@link #schemaDocumentCommandsAvailable()} first; there is no packed
	 * fallback at this layer.
	 * @param controlFlowXml the XML-text {@code <block>} control-flow document
	 * @param timeoutSecs the number of seconds to run before timing out
	 * @param encodeSet contains the response container and callback channels
	 * @throws IOException for any problems with the pipe to the decompiler process
	 * @throws DecompileException for any problems while executing the command
	 */
	public synchronized void sendStructureGraphSchema(String controlFlowXml, int timeoutSecs,
			DecompInterface.EncodeDecodeSet encodeSet) throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException("structureGraph called on bad process");
		}
		sendSchemaCommandTimeout(IpcCommandId.STRUCTURE_GRAPH, CommandRequestCodec
				.encodeStructureGraphRequest(Integer.toString(archId), controlFlowXml),
			timeoutSecs, encodeSet);
	}

	/**
	 * Send the decompileAt command as a schema-v1 request (#34-10e, DD-0080
	 * addendum) with the v0 {@link #sendCommandTimeout} semantics (timeout +
	 * callback decoders) preserved. The entry rides as a bare offset in the
	 * program's <i>default</i> address space — the caller gates on exactly
	 * that space (and on {@link #schemaDocumentCommandsAvailable()}); any
	 * other space keeps the v0 {@code <addr>} form. The budget sub-table is
	 * absent on the wire by decision; {@code timeout_ms}/{@code flags} ride
	 * their schema defaults — this host-side timer remains the enforcement.
	 * @param functionOffset the entry's {@code getUnsignedOffset()} (verbatim,
	 *            the same number the v0 wire carries)
	 * @param timeoutSecs the number of seconds to run before timing out
	 * @param encodeSet contains the response container and callback channels
	 * @throws IOException for any problems with the pipe to the decompiler process
	 * @throws DecompileException for any problems while executing the command
	 */
	public synchronized void sendDecompileAtSchema(long functionOffset, int timeoutSecs,
			DecompInterface.EncodeDecodeSet encodeSet) throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException("decompileAt called on bad process");
		}
		sendSchemaCommandTimeout(IpcCommandId.DECOMPILE_AT,
			DecompileRequestCodec.encodeRequest(Integer.toString(archId), functionOffset, 30000, 0),
			timeoutSecs, encodeSet);
	}

	/**
	 * The shared timeout-preserving schema-send core (two users: structureGraph
	 * and decompileAt): callback decoders armed, the host-side timer running,
	 * one SCHEMA_PAYLOAD frame out, v0 response burst back.
	 */
	private void sendSchemaCommandTimeout(IpcCommandId id, byte[] requestBytes, int timeoutSecs,
			DecompInterface.EncodeDecodeSet encodeSet) throws IOException, DecompileException {
		paramDecoder = encodeSet.callbackQuery;
		resultEncoder = encodeSet.callbackResponse;
		int validatedTimeoutMs = getTimeoutMs(timeoutSecs);
		GTimerMonitor timerMonitor = GTimer.scheduleRunnable(validatedTimeoutMs, timeoutRunnable);
		try {
			writeSchemaRequest(id, requestBytes);
			readResponse(encodeSet.mainResponse);
		}
		catch (IOException e) {
			statusGood = false;
			if (timerMonitor.didRun()) {
				// Timeout occurred
				throw new DecompileException("process", "timeout");
			}
			throw e;
		}
		finally {
			timerMonitor.cancel();
		}
	}

	/**
	 * Send one command as a schema-v1 request (#34-10b, DD-0080): a single
	 * COMMAND frame flagged SCHEMA_PAYLOAD whose body is the one-byte wire
	 * command-id followed by the FlatBuffers request. The response is the
	 * unchanged v0 burst protocol (the worker replies v0 until #34-10f+).
	 * @param id the schema-v1 command id
	 * @param requestBytes the FlatBuffers-encoded request payload
	 * @param response the response accumulator
	 * @throws IOException for any problems with the pipe to the decompiler process
	 * @throws DecompileException for any problems executing the command
	 */
	private void sendSchemaCommand(IpcCommandId id, byte[] requestBytes, ByteIngest response)
			throws IOException, DecompileException {
		paramDecoder = null;	// Don't expect callback queries
		resultEncoder = null;
		try {
			writeSchemaRequest(id, requestBytes);
			readResponse(response);
		}
		catch (IOException e) {
			statusGood = false;
			throw e;
		}
	}

	/**
	 * Buffer one schema-v1 request: arm the SCHEMA_PAYLOAD flag for the next
	 * frame and write the one-byte wire command-id followed by the
	 * FlatBuffers request bytes. The flush inside the subsequent
	 * readResponse emits exactly one flagged frame. Split from
	 * {@link #sendSchemaCommand} because registerProgram must keep its
	 * callback decoders alive across the send (#34-10c) while the simple
	 * commands null them.
	 * @param id the schema-v1 command id
	 * @param requestBytes the FlatBuffers-encoded request payload
	 * @throws IOException for any problems with the pipe to the decompiler process
	 */
	private void writeSchemaRequest(IpcCommandId id, byte[] requestBytes) throws IOException {
		frameOut.setNextFrameFlags(FRAME_FLAG_SCHEMA_PAYLOAD);
		nativeOut.write(id.getWireId());
		nativeOut.write(requestBytes);
		schemaV1RequestsSent++;
	}

	public synchronized boolean isReady() {
		return statusGood;
	}

	/**
	 * Execute a command with a timeout.  Parameters are in the encodingSet.mainQuery.
	 * The response gets written to encodingSet.mainResponse.  
	 * @param command the decompiler should execute
	 * @param timeoutSecs the number of seconds to run before timing out
	 * @param encodeSet contains encoded parameters and the response container
	 * @throws IOException for any problems with the pipe to the decompiler process
	 * @throws DecompileException for any problems while executing the command
	 */
	public synchronized void sendCommandTimeout(String command, int timeoutSecs,
			DecompInterface.EncodeDecodeSet encodeSet) throws IOException, DecompileException {

		if (!statusGood) {
			throw new IOException(command + " called on bad process");
		}

		paramDecoder = encodeSet.callbackQuery;
		resultEncoder = encodeSet.callbackResponse;
		int validatedTimeoutMs = getTimeoutMs(timeoutSecs);
		GTimerMonitor timerMonitor = GTimer.scheduleRunnable(validatedTimeoutMs, timeoutRunnable);

		try {
			write(command_start);
			writeString(command);
			writeString(Integer.toString(archId));
			writeString(encodeSet.mainQuery);
			write(command_end);
			readResponse(encodeSet.mainResponse);
		}
		catch (IOException e) {
			statusGood = false;
			if (timerMonitor.didRun()) {
				// Timeout occurred
				throw new DecompileException("process", "timeout");
			}
			throw e;
		}
		finally {
			timerMonitor.cancel();
		}
	}

	private int getTimeoutMs(int timeoutSecs) {
		if (timeoutSecs == 0) {
			return -1;
		}
		return timeoutSecs * 1000;
	}

	/**
	 * Send a command with 2 parameters to the decompiler and read the result
	 * @param command string to send
	 * @param param1  is the first parameter string
	 * @param param2  is the second parameter string
	 * @param response the response accumulator
	 * @throws IOException for any problems with the pipe to the decompiler process
	 * @throws DecompileException for problems executing the command
	 */
	public synchronized void sendCommand2Params(String command, String param1, String param2,
			ByteIngest response) throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException(command + " called on bad process");
		}
		if ("setAction".equals(command) && schemaPayloadAvailable()) {
			// #34-10d: keyed on the literal command name like the other
			// schematized branches. An empty selector string stays the
			// legacy leave-unchanged value on both encodings.
			sendSchemaCommand(IpcCommandId.SET_ACTION, CommandRequestCodec
					.encodeSetActionRequest(Integer.toString(archId), param1, param2),
				response);
			return;
		}
		paramDecoder = null;	// Don't expect callback queries
		resultEncoder = null;
		try {
			write(command_start);
			writeString(command);
			writeString(Integer.toString(archId));
			writeString(param1);
			writeString(param2);
			write(command_end);
			readResponse(response);
		}
		catch (IOException e) {
			statusGood = false;
			throw e;
		}
	}

	/**
	 * Set an upper limit on the amount of data that can be sent back by the decompiler in response
	 * to a single command.
	 * @param maxResultSizeMBytes is the maximum size in megabytes
	 */
	public void setMaxResultSize(int maxResultSizeMBytes) {
		this.maxResultSizeMBYtes = maxResultSizeMBytes;
	}

	/**
	 * Send a command to the decompiler with one parameter and return the result
	 * @param command is the command string
	 * @param param1 is the encoded parameter
	 * @param response is the result accumulator
	 * @throws IOException for problems with the pipe to the decompiler process
	 * @throws DecompileException for problems executing the command
	 */
	public synchronized void sendCommand1Param(String command, CachedEncoder param1,
			ByteIngest response) throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException(command + " called on bad process");
		}
		paramDecoder = null;	// Don't expect callback queries
		resultEncoder = null;
		try {
			write(command_start);
			writeString(command);
			writeString(Integer.toString(archId));
			writeString(param1);
			write(command_end);
			readResponse(response);
		}
		catch (IOException e) {
			statusGood = false;
			throw e;
		}
	}

	/**
	 * Send a command to the decompiler with one parameter and return the result
	 * @param command is the command string
	 * @param param1 is the parameter encoded as a string
	 * @param response is the result accumulator
	 * @throws IOException for problems with the pipe to the decompiler process
	 * @throws DecompileException for problems executing the command
	 */
	public synchronized void sendCommand1Param(String command, String param1, ByteIngest response)
			throws IOException, DecompileException {
		if (!statusGood) {
			throw new IOException(command + " called on bad process");
		}
		paramDecoder = null;	// Don't expect callback queries
		resultEncoder = null;
		try {
			write(command_start);
			writeString(command);
			writeString(Integer.toString(archId));
			writeString(param1);
			write(command_end);
			readResponse(response);
		}
		catch (IOException e) {
			statusGood = false;
			throw e;
		}
	}

	// Calls from the decompiler

	private void getRegister() throws IOException, DecoderException {
		resultEncoder.clear();
		String name = paramDecoder.readString(ATTRIB_NAME);
		callback.getRegister(name, resultEncoder);
		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void getRegisterName() throws IOException, DecoderException {
		int el = paramDecoder.openElement(ELEM_ADDR);
		Address addr = AddressXML.decodeFromAttributes(paramDecoder);
		int size = (int) paramDecoder.readSignedInteger(ATTRIB_SIZE);
		paramDecoder.closeElement(el);

		String res = callback.getRegisterName(addr, size);
		write(query_response_start);
		writeString(res);
		write(query_response_end);
	}

	private void getTrackedRegisters() throws IOException, DecoderException {
		resultEncoder.clear();
		Address addr = AddressXML.decode(paramDecoder);
		callback.getTrackedRegisters(addr, resultEncoder);
		write(query_response_start);
		writeString(resultEncoder);
		write(query_response_end);
	}

	private void getUserOpName() throws IOException, DecoderException {
		int index = (int) paramDecoder.readSignedInteger(ATTRIB_INDEX);
		String res = callback.getUserOpName(index);
		if (res == null) {
			res = "";
		}
		write(query_response_start);
		writeString(res);
		write(query_response_end);
	}

	private void getPcode() throws IOException, DecoderException {
		resultEncoder.clear();
		Address addr = AddressXML.decode(paramDecoder);
		callback.getPcode(addr, resultEncoder);
		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void getPcodeInject(int type) throws IOException, DecoderException,
			UnknownInstructionException, MemoryAccessException, NotFoundException {
		resultEncoder.clear();
		String name = paramDecoder.readString(ATTRIB_NAME);
		callback.getPcodeInject(name, paramDecoder, type, resultEncoder);
		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void getCPoolRef() throws IOException, DecoderException {
		resultEncoder.clear();
		int size = (int) paramDecoder.readSignedInteger(ATTRIB_SIZE);
		long refs[] = new long[size];
		for (int i = 0; i < size; ++i) {
			int el = paramDecoder.openElement(ELEM_VALUE);
			refs[i] = paramDecoder.readUnsignedInteger(ATTRIB_CONTENT);
			paramDecoder.closeElement(el);
		}
		callback.getCPoolRef(refs, resultEncoder);
		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void getMappedSymbols() throws IOException, DecoderException {
		resultEncoder.clear();
		Address addr = AddressXML.decode(paramDecoder);
		callback.getMappedSymbols(addr, resultEncoder);

		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void getNamespacePath() throws IOException, DecoderException {
		resultEncoder.clear();
		long id = paramDecoder.readUnsignedInteger(ATTRIB_ID);
		callback.getNamespacePath(id, resultEncoder);
		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void isNameUsed() throws IOException, DecoderException {
		String name = paramDecoder.readString(ATTRIB_NAME);
		long startId = paramDecoder.readUnsignedInteger(ATTRIB_FIRST);
		long stopId = paramDecoder.readUnsignedInteger(ATTRIB_LAST);
		boolean res = callback.isNameUsed(name, startId, stopId);
		write(query_response_start);
		write(string_start);
		write(res ? 't' : 'f');
		write(string_end);
		write(query_response_end);
	}

	private void getExternalRef() throws IOException, DecoderException {
		resultEncoder.clear();
		Address addr = AddressXML.decode(paramDecoder);
		callback.getExternalRef(addr, resultEncoder);
		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void getCodeLabel() throws IOException, DecoderException {
		Address addr = AddressXML.decode(paramDecoder);
		String res = callback.getCodeLabel(addr);
		if (res == null) {
			res = "";
		}
		write(query_response_start);
		writeString(res);
		write(query_response_end);
	}

	private void getComments() throws IOException, DecoderException {
		resultEncoder.clear();
		int types = (int) paramDecoder.readUnsignedInteger(ATTRIB_TYPE);
		Address addr = AddressXML.decode(paramDecoder);

		callback.getComments(addr, types, resultEncoder);
		write(query_response_start);
		writeString(resultEncoder);
		write(query_response_end);
	}

	private void getDataType() throws IOException, DecoderException {
		resultEncoder.clear();
		String name = paramDecoder.readString(ATTRIB_NAME);
		long id = paramDecoder.readSignedInteger(ATTRIB_ID);
		callback.getDataType(name, id, resultEncoder);
		write(query_response_start);
		if (!resultEncoder.isEmpty()) {
			writeString(resultEncoder);
		}
		write(query_response_end);
	}

	private void getBytes() throws IOException, DecoderException {
		int el = paramDecoder.openElement(ELEM_ADDR);
		Address addr = AddressXML.decodeFromAttributes(paramDecoder);
		int size = (int) paramDecoder.readSignedInteger(ATTRIB_SIZE);
		paramDecoder.closeElement(el);
		byte[] res = callback.getBytes(addr, size);
		write(query_response_start);
		if ((res != null) && (res.length > 0)) {
			write(byte_start);
			byte[] dblres = new byte[res.length * 2];
			for (int i = 0; i < res.length; i++) {
				dblres[i * 2] = (byte) (((res[i] >> 4) & 0xf) + 65);
				dblres[i * 2 + 1] = (byte) ((res[i] & 0xf) + 65);
			}
			write(dblres);
			write(byte_end);
		}
		write(query_response_end);
	}

	private void getStringData() throws IOException, DecoderException {
		int maxChars = (int) paramDecoder.readSignedInteger(ATTRIB_MAXSIZE);
		String dtName = paramDecoder.readString(ATTRIB_TYPE);
		long dtId = paramDecoder.readUnsignedInteger(ATTRIB_ID);
		Address addr = AddressXML.decode(paramDecoder);
		DecompileCallback.StringData stringData =
			callback.getStringData(addr, maxChars, dtName, dtId);
		write(query_response_start);
		if (stringData != null) {
			byte[] res = stringData.byteData;
			int sz = res.length + 1;		// We add a null terminator character
			int sz1 = (sz & 0x3f) + 0x20;
			sz >>>= 6;
			int sz2 = (sz & 0x3f) + 0x20;
			write(byte_start);
			write(sz1);
			write(sz2);
			write(stringData.isTruncated ? 1 : 0);
			byte[] dblres = new byte[res.length * 2 + 2];
			for (int i = 0; i < res.length; i++) {
				dblres[i * 2] = (byte) (((res[i] >> 4) & 0xf) + 65);
				dblres[i * 2 + 1] = (byte) ((res[i] & 0xf) + 65);
			}
			dblres[res.length * 2] = 65;		// Adding null terminator
			dblres[res.length * 2 + 1] = 65;
			write(dblres);
			write(byte_end);
		}
		write(query_response_end);
	}

	private void write(byte[] bytes) throws IOException {
		if (nativeOut == null) {
			return;
		}

		nativeOut.write(bytes);
	}

	private void write(int i) throws IOException {
		if (nativeOut == null) {
			return;
		}

		nativeOut.write(i);
	}

	// Rec 33 #33-2.6 framing tunnel (Java side). These mirror the C++
	// FrameOutStreambuf/FrameInStreambuf in frame_v1.cc: in v1 mode they
	// wrap nativeOut/nativeIn so each flush becomes one v1 frame and each
	// frame yields one byte-run, leaving the v0 marshaling above untouched.
	// Wired in by negotiateFramingV1 on a successful greeting (the 26.2.0
	// flip); kept independently unit-testable without a live decompiler.

	/**
	 * Output filter that wraps each {@link #flush()} as exactly one v1 frame.
	 * Mirrors {@code FrameOutStreambuf}. Bytes written are buffered until a
	 * flush, which emits one {@code encodeFrameV1} frame carrying the buffered
	 * payload and then flushes the target. An empty flush (nothing buffered)
	 * emits no frame, keeping the two directions' frame counts symmetric with
	 * {@link FrameInputStream}'s empty-frame skip.
	 */
	static final class FrameOutputStream extends OutputStream {
		private final OutputStream target;
		private final int frameType;
		private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
		private int nextFrameExtraFlags = 0;

		FrameOutputStream(OutputStream target, int frameType) {
			this.target = target;
			this.frameType = frameType;
		}

		/**
		 * Arm extra FLAGS bits for the next emitted frame only (#34-10b).
		 * One-shot: every {@link #flush()} clears the armed bits — even an
		 * empty flush that emits no frame — so an armed flag can never leak
		 * onto a later command's frame. Callers arm immediately before the
		 * body writes of the frame they mean to flag.
		 * @param extraFlags FLAGS bits ORed onto CRC_PRESENT for one frame
		 */
		void setNextFrameFlags(int extraFlags) {
			nextFrameExtraFlags = extraFlags;
		}

		@Override
		public void write(int b) {
			pending.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			pending.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			int extraFlags = nextFrameExtraFlags;
			nextFrameExtraFlags = 0; // read-and-clear unconditionally
			if (pending.size() > 0) {
				target.write(encodeFrameV1(frameType, extraFlags, pending.toByteArray()));
				pending.reset();
			}
			target.flush();
		}

		@Override
		public void close() throws IOException {
			flush();
			target.close();
		}
	}

	/**
	 * Input filter that unwraps each v1 frame into a byte run. Mirrors
	 * {@code FrameInStreambuf}. Each refill reads exactly one v1 frame and
	 * exposes its payload; empty-payload frames are skipped (symmetric with
	 * {@link FrameOutputStream}'s no-op empty flush). A non-OK frame read
	 * (EOF, magic mismatch, bad CRC, reserved flag, oversize length) ends the
	 * stream: {@code read} returns -1 and the cause is available via
	 * {@link #getLastError()}.
	 */
	static final class FrameInputStream extends InputStream {

		/** Mirror of {@code frame_v1::Error} for the reader's terminal state. */
		enum FrameError {
			OK, MAGIC_MISMATCH, TRUNCATED, LENGTH_TOO_LARGE, CRC_MISMATCH, RESERVED_FLAG_SET
		}

		private final InputStream source;
		private byte[] payload = new byte[0];
		private int pos = 0;
		private FrameError lastError = FrameError.OK;
		private int lastType = -1;
		private boolean ended = false;

		FrameInputStream(InputStream source) {
			this.source = source;
		}

		/** {@return the reason the stream ended, or {@code OK} until then.} */
		FrameError getLastError() {
			return lastError;
		}

		/** {@return the TYPE of the most recently delivered frame, or -1.} */
		int getLastType() {
			return lastType;
		}

		@Override
		public int read() throws IOException {
			if (!ensure()) {
				return -1;
			}
			return payload[pos++] & 0xff;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			if (!ensure()) {
				return -1;
			}
			int n = Math.min(len, payload.length - pos);
			System.arraycopy(payload, pos, b, off, n);
			pos += n;
			return n;
		}

		/** Ensure the payload buffer holds at least one unread byte. */
		private boolean ensure() throws IOException {
			if (pos < payload.length) {
				return true;
			}
			if (ended) {
				return false;
			}
			for (;;) {
				byte[] next = readOneFrame();
				if (next == null) {
					ended = true;
					return false;
				}
				if (next.length == 0) {
					continue; // skip empty frame (symmetric with an empty flush)
				}
				payload = next;
				pos = 0;
				return true;
			}
		}

		/**
		 * Read exactly one v1 frame; return its payload, or null on any non-OK
		 * condition (with {@link #lastError} set to the cause).
		 */
		private byte[] readOneFrame() throws IOException {
			byte[] hdr = new byte[10]; // MAGIC(4) + TYPE(1) + FLAGS(1) + LENGTH(4)
			if (!readFully(hdr, 10)) {
				lastError = FrameError.TRUNCATED;
				return null;
			}
			for (int i = 0; i < 4; i++) {
				if ((hdr[i] & 0xff) != (FRAME_MAGIC[i] & 0xff)) {
					lastError = FrameError.MAGIC_MISMATCH;
					return null;
				}
			}
			int type = hdr[4] & 0xff;
			int flags = hdr[5] & 0xff;
			long len = ((long) (hdr[6] & 0xff) << 24) | ((hdr[7] & 0xff) << 16) |
				((hdr[8] & 0xff) << 8) | (hdr[9] & 0xff);
			if (len > FRAME_MAX_PAYLOAD_LEN) {
				lastError = FrameError.LENGTH_TOO_LARGE;
				return null;
			}
			if ((flags & FRAME_FLAGS_RESERVED) != 0) {
				lastError = FrameError.RESERVED_FLAG_SET;
				return null;
			}
			byte[] body = new byte[(int) len];
			if (len > 0 && !readFully(body, (int) len)) {
				lastError = FrameError.TRUNCATED;
				return null;
			}
			byte[] crcBytes = new byte[4];
			if (!readFully(crcBytes, 4)) {
				lastError = FrameError.TRUNCATED;
				return null;
			}
			CRC32 crc = new CRC32();
			crc.update(hdr, 4, 6); // TYPE|FLAGS|LENGTH
			if (len > 0) {
				crc.update(body, 0, (int) len);
			}
			long expected = crc.getValue();
			long actual = ((long) (crcBytes[0] & 0xff) << 24) | ((crcBytes[1] & 0xff) << 16) |
				((crcBytes[2] & 0xff) << 8) | (crcBytes[3] & 0xff);
			if (actual != expected) {
				lastError = FrameError.CRC_MISMATCH;
				return null;
			}
			lastType = type;
			return body;
		}

		private boolean readFully(byte[] buf, int n) throws IOException {
			int off = 0;
			while (off < n) {
				int r = source.read(buf, off, n - off);
				if (r < 0) {
					return false;
				}
				off += r;
			}
			return true;
		}

		@Override
		public void close() throws IOException {
			source.close();
		}
	}
}
