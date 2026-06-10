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

import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppRttiFeeder;
import ghidra.app.util.datatype.microsoft.DataValidationOptions;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The program-wide harvest half of Rec 37 {@code #37-5}: walks a program for every MSVC
 * {@code RTTICompleteObjectLocator} (RTTI4) that Ghidra's RTTI machinery has already laid down and
 * feeds each one's class hierarchy through {@link CppMsvcRttiDriver#feedClass} into a
 * {@link ghidra.app.util.cpp.CppTypeSystem}.
 *
 * <p><b>Harvest, not re-discovery ({@code #37-5-4}).</b> In the real analysis pipeline Ghidra's
 * {@code RttiAnalyzer} (enabled by default for Visual Studio / Clang PEs) has already performed the
 * byte-search discovery &mdash; type_info vftable &rarr; {@code TypeDescriptor} references &rarr;
 * validated RTTI4s &mdash; and laid each one down as defined {@link Data} of type
 * {@link Rtti4Model#DATA_TYPE_NAME}. Re-implementing that search here would duplicate a fragile
 * byte-level walk this pass does not own; instead the scan iterates the program's defined data,
 * selects the {@code RTTICompleteObjectLocator} entries by datatype name, re-validates each through
 * {@link Rtti4Model}, and feeds it. A program the upstream analyzer has not processed (or one with no
 * RTTI) simply yields no classes.
 *
 * <p><b>Feed order does not matter.</b> The scan feeds in defined-data (address) order, which can put a
 * derived class before its base (the grounded complete-flow fixture lays Circle's RTTI4 below Base's);
 * {@link CppRttiFeeder} resolves bases through
 * {@link ghidra.app.util.cpp.CppClassResolution#resolveOrPlaceholder}, so a base fed later fills the
 * placeholder created when its derived class arrived.
 *
 * <p><b>Advisory, never wrong.</b> Like the driver and decoder beneath it, an entry that no longer
 * validates as an RTTI4, or that yields no class, contributes nothing &mdash; never an exception or a
 * mis-fed class. Null arguments, by contrast, are programming errors and are rejected.
 */
public final class CppMsvcRttiScan {

	private CppMsvcRttiScan() {
		// static scan utility
	}

	/**
	 * Walks the program's defined data for laid-down {@code RTTICompleteObjectLocator} entries and
	 * feeds each one's class hierarchy, without cancellation support.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the type-system feeder to attach the classes into; must not be null
	 * @param validationOptions options governing {@link Rtti4Model} re-validation; must not be null
	 * @return the fed derived {@link CppClass}es in defined-data order (possibly empty, never null)
	 */
	public static List<CppClass> feedProgram(Program program, CppRttiFeeder feeder,
			DataValidationOptions validationOptions) {
		try {
			return feedProgram(program, feeder, validationOptions, TaskMonitor.DUMMY);
		}
		catch (CancelledException e) {
			throw new AssertionError("the DUMMY monitor cannot be cancelled", e);
		}
	}

	/**
	 * Walks the program's defined data for laid-down {@code RTTICompleteObjectLocator} entries and
	 * feeds each one's class hierarchy, checking the monitor for cancellation per entry.
	 *
	 * @param program the program to harvest; must not be null
	 * @param feeder the type-system feeder to attach the classes into; must not be null
	 * @param validationOptions options governing {@link Rtti4Model} re-validation; must not be null
	 * @param monitor the task monitor to poll for cancellation; must not be null
	 * @return the fed derived {@link CppClass}es in defined-data order (possibly empty, never null)
	 * @throws CancelledException if the monitor is cancelled mid-walk
	 */
	public static List<CppClass> feedProgram(Program program, CppRttiFeeder feeder,
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
		List<CppClass> fed = new ArrayList<>();
		DataIterator definedData = program.getListing().getDefinedData(true);
		while (definedData.hasNext()) {
			monitor.checkCancelled();
			Data data = definedData.next();
			DataType type = data.getDataType();
			if (type == null || !Rtti4Model.DATA_TYPE_NAME.equals(type.getName())) {
				continue;
			}
			CppClass fedClass = CppMsvcRttiDriver.feedClass(
				new Rtti4Model(program, data.getAddress(), validationOptions), feeder);
			if (fedClass != null) {
				fed.add(fedClass);
			}
		}
		return fed;
	}
}
