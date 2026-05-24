// Post-analysis script: assert the crackme's key[] and expected[] byte
// arrays survive compilation into the binary, and that the decoded
// password "ghidrarocks!2026" can be reconstructed from them.
//
// Scans raw program memory (not relying on Ghidra disassembling code
// or recognizing Go function bounds — which it can fail to do on
// recent Go versions, see NSA/ghidra#9219). The key + expected arrays
// land in .rodata / .noptrdata as contiguous 16-byte sequences.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;

public class DumpCrackme extends GhidraScript {

    private static final byte[] KEY = {
        (byte) 0x3F, (byte) 0xA1, (byte) 0x5C, (byte) 0xE2,
        (byte) 0x07, (byte) 0x91, (byte) 0x44, (byte) 0xB8,
        (byte) 0xD3, (byte) 0x6E, (byte) 0x25, (byte) 0x7A,
        (byte) 0xC9, (byte) 0x10, (byte) 0x88, (byte) 0xF5,
    };
    private static final byte[] EXPECTED = {
        (byte) 0x58, (byte) 0xC9, (byte) 0x35, (byte) 0x86,
        (byte) 0x75, (byte) 0xF0, (byte) 0x36, (byte) 0xD7,
        (byte) 0xB0, (byte) 0x05, (byte) 0x56, (byte) 0x5B,
        (byte) 0xFB, (byte) 0x20, (byte) 0xBA, (byte) 0xC3,
    };
    private static final String EXPECTED_PASSWORD = "ghidrarocks!2026";

    @Override
    public void run() throws Exception {
        println("=== DumpCrackme post-script ===");

        int keyHits = countBytePattern(KEY);
        int expHits = countBytePattern(EXPECTED);
        println("key[] (16 bytes) raw occurrences in initialized memory: " + keyHits);
        println("expected[] (16 bytes) raw occurrences in initialized memory: " + expHits);

        StringBuilder decoded = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            decoded.append((char) ((KEY[i] ^ EXPECTED[i]) & 0xFF));
        }
        String decodedStr = decoded.toString();
        println("XOR-decoded password: \"" + decodedStr + "\"");

        boolean decodeOk = EXPECTED_PASSWORD.equals(decodedStr);
        boolean arraysOk = keyHits >= 1 && expHits >= 1;
        println("");
        if (decodeOk && arraysOk) {
            println("RESULT: PASS — both arrays present in binary; XOR-decode reconstructs the expected password");
        } else if (arraysOk && !decodeOk) {
            println("RESULT: FAIL — arrays found but XOR-decode produces \"" + decodedStr
                + "\", not \"" + EXPECTED_PASSWORD + "\"");
        } else if (!arraysOk) {
            println("RESULT: FAIL — key[]=" + keyHits + " expected[]=" + expHits
                + " (each must be ≥1)");
        }
    }

    private int countBytePattern(byte[] needle) throws Exception {
        Memory mem = currentProgram.getMemory();
        int hits = 0;
        for (MemoryBlock blk : mem.getBlocks()) {
            if (!blk.isInitialized()) continue;
            int size = (int) Math.min(blk.getSize(), Integer.MAX_VALUE);
            byte[] buf = new byte[size];
            mem.getBytes(blk.getStart(), buf, 0, size);
            for (int i = 0; i <= buf.length - needle.length; i++) {
                boolean match = true;
                for (int j = 0; j < needle.length; j++) {
                    if (buf[i + j] != needle[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) hits++;
            }
        }
        return hits;
    }
}
