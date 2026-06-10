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
package ghidra.app.cmd.data.rtti;

import java.util.ArrayList;
import java.util.List;

import ghidra.app.util.cpp.CppVTable;
import ghidra.app.util.cpp.CppVTableFeeder;
import ghidra.app.util.datatype.microsoft.DataValidationOptions;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The program-wide vftable harvest of the Rec 37 {@code #37-11} band ({@code #37-11c-2}): walks a
 * program for every MSVC {@code vftable} that Ghidra's RTTI machinery has already laid down and feeds
 * each one through {@link CppMsvcVftableDriver#feedVtable} into a
 * {@link ghidra.app.util.cpp.CppTypeSystem} — the {@link CppMsvcRttiScan} twin for the vtable half.
 *
 * <p><b>Harvest, not re-discovery (the DD-0061 posture).</b> Upstream's {@code RttiAnalyzer} chain
 * ({@code CreateRtti4BackgroundCmd} &rarr; its associated-vftable pass) has already discovered each
 * vftable by its meta pointer and published it as a {@code vftable}-named symbol in the class's
 * namespace ({@code RttiUtil.createSymbolFromDemangledType}). The scan iterates the symbol table for
 * that published name and re-validates each address through {@link VfTableModel} in the driver;
 * re-implementing the meta-pointer search would duplicate a byte walk this pass does not own. A
 * program the upstream analyzer has not processed (or one with no vftables) simply yields no tables.
 *
 * <p><b>Advisory, never wrong.</b> An entry that no longer validates as a vftable, or whose class or
 * slots cannot be faithfully named, contributes nothing (the driver's per-table decline) &mdash;
 * never an exception or a mis-fed table. Null arguments are programming errors and are rejected.
 */
public final class CppMsvcVftableScan {

	// The label upstream's associated-vftable pass publishes (CreateVfTableBackgroundCmd's
	// VF_TABLE_LABEL); upstream uses the same spelling for the vftable datatype name.
	private static final String VFTABLE_SYMBOL_NAME = VfTableModel.DATA_TYPE_NAME;

	private CppMsvcVftableScan() {
		// static scan utility
	}

	/**
	 * Walks the program's symbol table for laid-down {@code vftable} symbols and feeds each table,
	 * without cancellation support.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the type-system feeder to attach the vtables through; must not be null
	 * @param validationOptions options governing {@link VfTableModel} re-validation; must not be null
	 * @return the fed {@link CppVTable}s in symbol-table order (possibly empty, never null)
	 */
	public static List<CppVTable> feedProgram(Program program, CppVTableFeeder feeder,
			DataValidationOptions validationOptions) {
		try {
			return feedProgram(program, feeder, validationOptions, TaskMonitor.DUMMY);
		}
		catch (CancelledException e) {
			throw new AssertionError("the DUMMY monitor cannot be cancelled", e);
		}
	}

	/**
	 * Walks the program's symbol table for laid-down {@code vftable} symbols and feeds each table,
	 * checking the monitor for cancellation per symbol.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the type-system feeder to attach the vtables through; must not be null
	 * @param validationOptions options governing {@link VfTableModel} re-validation; must not be null
	 * @param monitor the task monitor to poll for cancellation; must not be null
	 * @return the fed {@link CppVTable}s in symbol-table order (possibly empty, never null)
	 * @throws CancelledException if the monitor is cancelled mid-walk
	 */
	public static List<CppVTable> feedProgram(Program program, CppVTableFeeder feeder,
			DataValidationOptions validationOptions, TaskMonitor monitor)
			throws CancelledException {
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		if (feeder == null) {
			throw new IllegalArgumentException("feeder must not be null");
		}
		if (validationOptions == null) {
			throw new IllegalArgumentException("validationOptions must not be null");
		}
		if (monitor == null) {
			throw new IllegalArgumentException("monitor must not be null");
		}
		List<CppVTable> fed = new ArrayList<>();
		SymbolIterator vftableSymbols = program.getSymbolTable().getSymbols(VFTABLE_SYMBOL_NAME);
		while (vftableSymbols.hasNext()) {
			monitor.checkCancelled();
			Symbol vftableSymbol = vftableSymbols.next();
			CppVTable fedTable = CppMsvcVftableDriver.feedVtable(
				new VfTableModel(program, vftableSymbol.getAddress(), validationOptions), feeder);
			if (fedTable != null) {
				fed.add(fedTable);
			}
		}
		return fed;
	}
}
