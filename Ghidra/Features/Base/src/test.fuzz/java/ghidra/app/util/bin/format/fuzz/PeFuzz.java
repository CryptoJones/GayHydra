/* ###
 * Fuzz harness for the PE loader.
 *
 * Scaffold for Rec 14: see docs/security/LOADER_FUZZING.md.
 *
 * Entry point: PortableExecutable.createPortableExecutable(...).
 *
 * Catches loader-defined exceptions and IOException as expected;
 * everything else escapes.
 */
package ghidra.app.util.bin.format.fuzz;

import java.io.IOException;

import ghidra.app.util.bin.ByteArrayProvider;
import ghidra.app.util.bin.format.pe.PortableExecutable;
import ghidra.app.util.bin.format.pe.PortableExecutable.SectionLayout;
import ghidra.util.exception.DuplicateNameException;

public final class PeFuzz {

    public static void fuzzerTestOneInput(byte[] data) {
        if (data.length == 0) {
            return;
        }
        ByteArrayProvider provider = new ByteArrayProvider(data);
        try {
            PortableExecutable pe = PortableExecutable.createPortableExecutable(
                /* factory: */ null,
                provider,
                SectionLayout.FILE,
                /* advancedProcessing: */ false,
                /* parseCliHeaders: */ false);
            // Walk the structure once to exercise lazy parsers.
            if (pe.getNTHeader() != null) {
                pe.getNTHeader().getOptionalHeader();
            }
        }
        catch (IOException | DuplicateNameException e) {
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

    private PeFuzz() {
        // not instantiable
    }
}
