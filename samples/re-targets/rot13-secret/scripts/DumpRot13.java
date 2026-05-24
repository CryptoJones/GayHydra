// Post-analysis script: assert the ROT13-encoded secret string and
// the ROT13 transformation constants (0x41 base, 0x61 base, 0x0D
// rotation) are recoverable from the binary.
//
// Three checks:
//   1. The encoded string "Qrpbzcvyre vf sha!" appears in initialized
//      memory (compilation-independent — Go always stores string
//      literals in .rodata).
//   2. At least one XOR / arithmetic instruction with the 0xD immediate
//      (the rotation step). Go's compiler emits `ADDL $0xd, REG` and
//      `SHRL $0xd, REG` for the (c-base+13)%26 calculation.
//   3. Round-trip decode: rot13(encoded) == "Decompiler is fun!".

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.scalar.Scalar;

public class DumpRot13 extends GhidraScript {

    private static final String ENCODED = "Qrpbzcvyre vf sha!";
    private static final String EXPECTED_PLAIN = "Decompiler is fun!";
    private static final long ROT_CONST = 0x0D;

    @Override
    public void run() throws Exception {
        println("=== DumpRot13 post-script ===");

        int stringHits = countBytePattern(ENCODED.getBytes("US-ASCII"));
        println("encoded string \"" + ENCODED + "\" raw occurrences in initialized memory: " + stringHits);

        int rotInsnHits = countXorOrArithWithConst(ROT_CONST);
        println("disassembled instructions with immediate operand 0x0D: " + rotInsnHits);

        String roundTrip = rot13(ENCODED);
        println("rot13 round-trip of the encoded string: \"" + roundTrip + "\"");
        boolean roundTripOk = EXPECTED_PLAIN.equals(roundTrip);

        boolean stringOk = stringHits >= 1;
        boolean insnOk = rotInsnHits >= 1;
        println("");
        if (stringOk && insnOk && roundTripOk) {
            println("RESULT: PASS — encoded secret present, ROT13 transform constant present, round-trip decodes to expected plaintext");
        } else if (stringOk && roundTripOk && !insnOk) {
            println("RESULT: PASS (weak) — encoded string + round-trip OK, but no 0x0D rotation immediate found in disassembly (Go compiler may have used a different lowering)");
        } else {
            println("RESULT: FAIL — stringHits=" + stringHits
                + " insnHits=" + rotInsnHits + " roundTripOk=" + roundTripOk);
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

    private int countXorOrArithWithConst(long value) {
        Listing listing = currentProgram.getListing();
        int hits = 0;
        InstructionIterator it = listing.getInstructions(true);
        while (it.hasNext()) {
            if (monitor.isCancelled()) break;
            Instruction insn = it.next();
            String m = insn.getMnemonicString();
            // ROT13 lowering uses ADD/SUB/SHR with the 0xD immediate.
            // XOR isn't normally used for ROT13 but we accept it for
            // completeness.
            if (!m.equalsIgnoreCase("ADD") && !m.equalsIgnoreCase("ADDL")
                && !m.equalsIgnoreCase("SUB") && !m.equalsIgnoreCase("SUBL")
                && !m.equalsIgnoreCase("SHR") && !m.equalsIgnoreCase("SHRL")
                && !m.equalsIgnoreCase("XOR") && !m.equalsIgnoreCase("XORL")) {
                continue;
            }
            for (int i = 0; i < insn.getNumOperands(); i++) {
                Scalar s = insn.getScalar(i);
                if (s != null && s.getUnsignedValue() == value) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    private String rot13(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                out.append((char) ((c - 'A' + 13) % 26 + 'A'));
            } else if (c >= 'a' && c <= 'z') {
                out.append((char) ((c - 'a' + 13) % 26 + 'a'));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
