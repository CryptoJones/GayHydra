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

import ghidra.app.cmd.data.TypeDescriptorModel;
import ghidra.app.util.cpp.CppVTable;
import ghidra.app.util.cpp.CppVTableFeeder;
import ghidra.app.util.cpp.CppVTableFeeder.SlotSpec;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.FunctionDefinition;
import ghidra.program.model.data.FunctionDefinitionDataType;
import ghidra.program.model.data.InvalidDataTypeException;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;

/**
 * The vftable driver of the Rec 37 {@code #37-11} integration band ({@code #37-11c-1}): bridges one
 * located MSVC {@code vftable} ({@link VfTableModel}) to the ABI-neutral {@link CppVTableFeeder},
 * attaching the recovered virtual-function table to its owning class in a
 * {@link ghidra.app.util.cpp.CppTypeSystem}. This is the program-coupled scanner DD-0014 deferred:
 * it reads the function-pointer array at the vftable address, resolves each slot to its symbol name,
 * and sets {@link CppVTable#getTableAddress()}.
 *
 * <p><b>The owning class comes from the vftable's own RTTI.</b> An MSVC vftable is preceded by a
 * meta pointer to its {@code RTTICompleteObjectLocator}, whose type descriptor yields the same
 * {@link TypeDescriptorModel#getDescriptorName() demangled unqualified name} the
 * {@link CppMsvcRttiDecoder} keys classes by — so the fed vtable attaches to the very
 * {@link ghidra.app.util.cpp.CppClass} the RTTI harvest resolves, with no name translation layer.
 *
 * <p><b>Slot names come from the slots' primary symbols.</b> In the real pipeline the demangler has
 * named each virtual function ({@code Circle::draw} &rarr; symbol {@code draw} in namespace
 * {@code Circle}); the driver reads that name. A slot with no symbol or only a default one
 * ({@code FUN_...}) has no faithful method name, and a {@code _purecall} slot (an abstract class's
 * pure-virtual entry) names the runtime trap, not the method — each declines the <em>whole</em>
 * table rather than feed a misleading slot (never-wrong; recovering pure-virtual names is a later
 * slice, grounded against a real abstract-class binary).
 *
 * <p><b>Advisory, never wrong.</b> A null model, one that does not validate, or one whose class or
 * slots cannot be named contributes nothing ({@code null}), never an exception or a mis-fed table. A
 * null {@code feeder} is a programming error and is rejected (matching
 * {@link CppVTableFeeder}'s own null-argument contract). Re-feeding is idempotent: the feeder
 * replaces the class's vtable wholesale, so a repeated drive cannot duplicate slots.
 */
public final class CppMsvcVftableDriver {

	private static final String PURECALL_NAME = "_purecall";

	private CppMsvcVftableDriver() {
		// static driver utility
	}

	/**
	 * Recovers the vtable behind one MSVC {@code vftable} and feeds it to its owning class.
	 *
	 * @param vftable the {@code vftable} model; may be null
	 * @param feeder the type-system feeder to attach the vtable through; must not be null
	 * @return the fed {@link CppVTable} (table address set), or null if the model is null, does not
	 *         validate, or its class or any slot cannot be faithfully named
	 */
	public static CppVTable feedVtable(VfTableModel vftable, CppVTableFeeder feeder) {
		if (feeder == null) {
			throw new IllegalArgumentException("feeder must not be null");
		}
		if (vftable == null) {
			return null;
		}
		try {
			vftable.validate();
			TypeDescriptorModel rtti0 = vftable.getRtti0Model();
			if (rtti0 == null) {
				return null;
			}
			String className = rtti0.getDescriptorName();
			if (className == null || className.isBlank()) {
				return null;
			}
			List<SlotSpec> slots = recoverSlots(vftable);
			if (slots == null) {
				return null;
			}
			CppVTable fed = feeder.feedVtable(className, slots);
			fed.setTableAddress(vftable.getAddress());
			return fed;
		}
		catch (InvalidDataTypeException e) {
			return null;
		}
	}

	// Resolves every slot's function address to its primary symbol name, in layout order. Returns
	// null — declining the whole table — when any slot lacks a faithful name.
	private static List<SlotSpec> recoverSlots(VfTableModel vftable) {
		int count = vftable.getElementCount();
		if (count <= 0) {
			return null;
		}
		Program program = vftable.getProgram();
		List<SlotSpec> slots = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Address functionAddress = vftable.getVirtualFunctionPointer(i);
			if (functionAddress == null) {
				return null;
			}
			Symbol symbol = program.getSymbolTable().getPrimarySymbol(functionAddress);
			if (symbol == null || symbol.getSource() == SourceType.DEFAULT) {
				return null;
			}
			String methodName = symbol.getName();
			if (methodName == null || methodName.isBlank() || PURECALL_NAME.equals(methodName)) {
				return null;
			}
			slots.add(new SlotSpec(methodName, false, slotSignature(program, functionAddress)));
		}
		return slots;
	}

	// The slot function's resolved signature ({@code #37-12b}): when a Function is defined at the
	// slot's address the demangler analyzer has already applied its signature, so a definition built
	// from it matches what the listing shows. No Function (or any failure) feeds no signature -- the
	// slot still feeds by name (never-wrong over complete).
	private static FunctionDefinition slotSignature(Program program, Address functionAddress) {
		try {
			Function slotFunction = program.getFunctionManager().getFunctionAt(functionAddress);
			if (slotFunction == null) {
				return null;
			}
			return new FunctionDefinitionDataType(slotFunction.getSignature());
		}
		catch (Exception e) {
			return null;
		}
	}
}
