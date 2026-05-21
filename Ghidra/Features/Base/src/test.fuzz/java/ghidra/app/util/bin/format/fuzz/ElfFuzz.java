/* ###
 * Fuzz harness for the ELF loader.
 *
 * Scaffold for Rec 14: see docs/security/LOADER_FUZZING.md.
 *
 * Entry point: ElfHeader.createElfHeader(...) over a ByteArrayProvider.
 *
 * Catches loader-defined exceptions and IOException as expected
 * outcomes for malformed input. Any other exception escapes and
 * triggers a Jazzer finding.
 */
package ghidra.app.util.bin.format.fuzz;

import java.io.IOException;

import ghidra.app.util.bin.ByteArrayProvider;
import ghidra.app.util.bin.format.elf.ElfException;
import ghidra.app.util.bin.format.elf.ElfHeader;

public final class ElfFuzz {

    public static void fuzzerTestOneInput(byte[] data) {
        if (data.length == 0) {
            return;
        }
        ByteArrayProvider provider = new ByteArrayProvider(data);
        try {
            ElfHeader header = new ElfHeader(provider, msg -> { /* swallow log */ });
            header.parse();
        }
        catch (ElfException | IOException e) {
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

    private ElfFuzz() {
        // not instantiable
    }
}
