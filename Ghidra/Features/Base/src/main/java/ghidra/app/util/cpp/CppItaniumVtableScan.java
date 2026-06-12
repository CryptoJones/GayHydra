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
package ghidra.app.util.cpp;

import java.util.ArrayList;
import java.util.List;

import ghidra.app.util.cpp.CppItaniumVtableDecoder.DecodedVtable;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The program-wide harvest half of Rec 37 {@code #37-4b-4}: walks a program's symbol table for
 * every {@code _ZTV*} vtable symbol, decodes each through {@link CppItaniumVtableDecoder}, and
 * feeds the recovered slots through {@link CppVTableFeeder#feedVtable} — the Itanium twin of
 * {@code CppMsvcVftableScan}, and the {@code CppItaniumRttiScan} sibling.
 *
 * <p><b>Symbol harvest, not byte discovery.</b> The linker publishes every vtable under its
 * {@code _ZTV*} symbol; the decoder anchors on exactly those, so a stripped binary yields
 * nothing. The fed {@link CppVTable}'s table address is set from the {@code _ZTV} symbol so a
 * consumer can locate it.
 *
 * <p><b>Advisory, never wrong.</b> A symbol whose vtable declines to decode (abstract class with
 * {@code __cxa_pure_virtual}, an unnamed slot, a template encoding) contributes nothing. Null
 * arguments are programming errors and are rejected.
 */
public final class CppItaniumVtableScan {

	private static final String VTABLE_SYMBOL_PREFIX = "_ZTV";

	private CppItaniumVtableScan() {
		// static scan utility
	}

	/**
	 * Walks the program's {@code _ZTV*} symbols and feeds each decodable vtable, without
	 * cancellation support.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the vtable feeder to attach the tables into; must not be null
	 * @return the fed {@link CppVTable}s in symbol order (possibly empty, never null)
	 */
	public static List<CppVTable> feedProgram(Program program, CppVTableFeeder feeder) {
		try {
			return feedProgram(program, feeder, TaskMonitor.DUMMY);
		}
		catch (CancelledException e) {
			throw new AssertionError("the DUMMY monitor cannot be cancelled", e);
		}
	}

	/**
	 * Walks the program's {@code _ZTV*} symbols and feeds each decodable vtable, checking the
	 * monitor for cancellation per symbol.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the vtable feeder to attach the tables into; must not be null
	 * @param monitor the task monitor to poll for cancellation; must not be null
	 * @return the fed {@link CppVTable}s in symbol order (possibly empty, never null)
	 * @throws CancelledException if the monitor is cancelled mid-walk
	 */
	public static List<CppVTable> feedProgram(Program program, CppVTableFeeder feeder,
			TaskMonitor monitor) throws CancelledException {
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		if (feeder == null) {
			throw new IllegalArgumentException("feeder must not be null");
		}
		if (monitor == null) {
			throw new IllegalArgumentException("monitor must not be null");
		}
		List<CppVTable> fed = new ArrayList<>();
		SymbolIterator symbols =
			program.getSymbolTable().getSymbolIterator(VTABLE_SYMBOL_PREFIX + "*", true);
		while (symbols.hasNext()) {
			monitor.checkCancelled();
			Symbol symbol = symbols.next();
			DecodedVtable decoded = CppItaniumVtableDecoder.decodeVtable(program, symbol);
			if (decoded == null) {
				continue;
			}
			CppVTable table = feeder.feedVtable(decoded.owningClassName(), decoded.slots());
			table.setTableAddress(symbol.getAddress());
			fed.add(table);
		}
		return fed;
	}
}
