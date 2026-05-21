/* ###
 * IP: GHIDRA
 *
 * Fuzz harness for the Mach-O loader.
 *
 * Scaffold for Rec 14: see docs/security/LOADER_FUZZING.md.
 *
 * Entry point: MachHeader(BinaryReader).
 *
 * Catches loader-defined exceptions and IOException; everything else
 * escapes and triggers a finding.
 */
package ghidra.app.util.bin.format.fuzz;

import java.io.IOException;

import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteArrayProvider;
import ghidra.app.util.bin.format.macho.MachException;
import ghidra.app.util.bin.format.macho.MachHeader;

public final class MachoFuzz {

    public static void fuzzerTestOneInput(byte[] data) {
        if (data.length == 0) {
            return;
        }
        ByteArrayProvider provider = new ByteArrayProvider(data);
        try {
            BinaryReader reader = new BinaryReader(provider, /* isLittleEndian: */ true);
            MachHeader header = new MachHeader(reader);
            header.parse();
        }
        catch (MachException | IOException e) {
            /* Expected on malformed input. */
        }
        finally {
            try {
                provider.close();
            }
            catch (IOException e) {
                /* close-on-error: ignore. */
            }
        }
    }

    private MachoFuzz() {
        // not instantiable
    }
}
