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

import ghidra.app.util.cpp.CppItaniumRttiDecoder.DecodedClass;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The program-wide harvest half of Rec 37 {@code #37-4b}: walks a program's symbol table for
 * every {@code _ZTI*} typeinfo symbol, decodes each through {@link CppItaniumRttiDecoder}, and
 * feeds the recovered hierarchy through {@link CppRttiFeeder#feedClass} — the Itanium twin of
 * {@code CppMsvcRttiScan} ({@code #37-4b-2}).
 *
 * <p><b>Symbol harvest, not byte discovery.</b> Where the MSVC scan harvests the defined data
 * upstream's {@code RttiAnalyzer} lays down, ELF needs no prior analyzer: the linker itself
 * publishes every typeinfo object under its {@code _ZTI*} symbol, and the decoder anchors on
 * exactly those symbols. A stripped binary simply yields no classes — symbol presence is the
 * measured contract (see {@code samples/hint-recall-corpus/README.md}).
 *
 * <p><b>Feed order does not matter.</b> Symbol-table order can put a derived class before its
 * base; {@link CppRttiFeeder} resolves bases through placeholders, so a base fed later fills
 * the placeholder its derived class created.
 *
 * <p><b>Advisory, never wrong.</b> A symbol whose typeinfo declines to decode (template
 * encoding, virtual base, unreadable words) contributes nothing — never an exception or a
 * mis-fed class. Null arguments are programming errors and are rejected.
 */
public final class CppItaniumRttiScan {

	private static final String TYPEINFO_SYMBOL_PREFIX = "_ZTI";

	private CppItaniumRttiScan() {
		// static scan utility
	}

	/**
	 * Walks the program's {@code _ZTI*} symbols and feeds each decodable class hierarchy,
	 * without cancellation support.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the type-system feeder to attach the classes into; must not be null
	 * @return the fed derived {@link CppClass}es in symbol order (possibly empty, never null)
	 */
	public static List<CppClass> feedProgram(Program program, CppRttiFeeder feeder) {
		try {
			return feedProgram(program, feeder, TaskMonitor.DUMMY);
		}
		catch (CancelledException e) {
			throw new AssertionError("the DUMMY monitor cannot be cancelled", e);
		}
	}

	/**
	 * Walks the program's {@code _ZTI*} symbols and feeds each decodable class hierarchy,
	 * checking the monitor for cancellation per symbol.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the type-system feeder to attach the classes into; must not be null
	 * @param monitor the task monitor to poll for cancellation; must not be null
	 * @return the fed derived {@link CppClass}es in symbol order (possibly empty, never null)
	 * @throws CancelledException if the monitor is cancelled mid-walk
	 */
	public static List<CppClass> feedProgram(Program program, CppRttiFeeder feeder,
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
		List<CppClass> fed = new ArrayList<>();
		SymbolIterator symbols =
			program.getSymbolTable().getSymbolIterator(TYPEINFO_SYMBOL_PREFIX + "*", true);
		while (symbols.hasNext()) {
			monitor.checkCancelled();
			Symbol symbol = symbols.next();
			DecodedClass decoded = CppItaniumRttiDecoder.decodeClass(program, symbol);
			if (decoded == null) {
				continue;
			}
			fed.add(feeder.feedClass(decoded.derivedName(), decoded.directBases()));
		}
		return fed;
	}
}
