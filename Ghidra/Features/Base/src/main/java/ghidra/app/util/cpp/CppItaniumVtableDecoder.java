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

import ghidra.app.util.cpp.CppVTableFeeder.SlotSpec;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

/**
 * Decodes one Itanium-ABI {@code vtable} object ({@code _ZTV*}) into the {@link CppVTableFeeder}
 * facts — the Itanium counterpart of {@code CppMsvcVftableDriver} (Rec 37 {@code #37-4b-4}; the
 * leg the hint-recall corpus measured {@code VIRTUAL_CALL} at zero without).
 *
 * <p><b>Layout, grounded in the ABI.</b> An Itanium primary vtable for class C, published under
 * the symbol {@code _ZTV1C}, is laid out
 * {@code [ offset-to-top (ptrdiff_t) ][ &_ZTI1C (typeinfo ptr) ][ slot0 ][ slot1 ]… }. The
 * <em>address point</em> — the value an object's vptr actually holds — is {@code _ZTV1C +
 * 2*ptrSize}, i.e. the first function-pointer slot. The {@link CppVirtualCallRecognizer}'s
 * recovered slot index is relative to that address point, so this decoder reads the slots from
 * there, in order, and feeds them at the same indices.
 *
 * <p><b>Slot recovery and the never-wrong contract</b> (mirrors the MSVC vftable driver,
 * DD-0064). Each slot is a function pointer; the slot's method name is the
 * already-demangled name of the function it points at (the GNU Demangler analyzer ran first).
 * The run of slots ends at the first word that is not a pointer to a defined function (the next
 * sub-vtable's {@code offset-to-top}, padding, or the bracketing symbol) or at the next defined
 * symbol — so a multiple-inheritance secondary vtable is naturally excluded and only the primary
 * table is fed. If <b>any</b> slot in the run cannot be named (no function, a default
 * {@code FUN_…} name) or is {@code __cxa_pure_virtual}, the <b>whole table is declined</b>: the
 * slot index is the virtual-call renderer's dispatch contract, and a partial table would
 * mis-number every later slot. The owning class name comes from the {@code _ZTV} symbol's own
 * Itanium encoding ({@code _ZTV4Base} &rarr; {@code Base}), never from chasing pointers.
 *
 * <p>Advisory and total-failure-safe like every Rec 37 decoder: a null symbol, a non-{@code _ZTV}
 * symbol, an unreadable word, or a table that yields no slots contributes nothing ({@code null}).
 */
public final class CppItaniumVtableDecoder {

	/** A decoded vtable: the owning class's qualified name and its slots in address-point order. */
	public record DecodedVtable(String owningClassName, List<SlotSpec> slots) {}

	private static final String PURE_VIRTUAL = "__cxa_pure_virtual";
	/** Defensive bound on slot count — beyond this the run is not a real vtable. */
	private static final int MAX_SLOTS = 4096;

	private CppItaniumVtableDecoder() {
		// static decode utility
	}

	/**
	 * Decodes the vtable at the given {@code _ZTV*} symbol.
	 *
	 * @param program the program holding the vtable data
	 * @param vtableSymbol the {@code _ZTV*} symbol at the object's start; may be null
	 * @return the decoded vtable, or null when anything fails to validate (never-wrong)
	 */
	public static DecodedVtable decodeVtable(Program program, Symbol vtableSymbol) {
		if (program == null || vtableSymbol == null) {
			return null;
		}
		String owningClassName = classNameOf(vtableSymbol);
		if (owningClassName == null) {
			return null;
		}
		int ptrSize = program.getDefaultPointerSize();
		Address start = vtableSymbol.getAddress();
		Address limit = bracketLimit(program, start);

		// Slots begin at the address point: past offset-to-top and the typeinfo pointer.
		List<SlotSpec> slots = new ArrayList<>();
		try {
			Address slotAddr = start.add(2L * ptrSize);
			while (slotAddr.compareTo(limit) < 0 && slots.size() < MAX_SLOTS) {
				Address target = readPointer(program, slotAddr);
				if (target == null) {
					break;	// not a pointer / null word — end of the function-pointer run
				}
				Function function = program.getFunctionManager().getFunctionAt(target);
				if (function == null) {
					break;	// points at non-function (next sub-table's offset-to-top, padding)
				}
				String methodName = namedMethod(program, target, function);
				if (methodName == null) {
					return null;	// a slot we cannot name → decline the whole table
				}
				if (PURE_VIRTUAL.equals(methodName) || PURE_VIRTUAL.equals(function.getName())) {
					return null;	// abstract-class vtable → decline (MSVC parity)
				}
				slots.add(new SlotSpec(methodName, false));
				slotAddr = slotAddr.add(ptrSize);
			}
		}
		catch (Exception e) {
			return null;
		}
		if (slots.isEmpty()) {
			return null;
		}
		return new DecodedVtable(owningClassName, slots);
	}

	/**
	 * {@return the slot method's demangled name, or null if the function carries only a default
	 * {@code FUN_…} symbol} — a named virtual function is required (MSVC parity: a default-named
	 * slot declines the table).
	 */
	private static String namedMethod(Program program, Address target, Function function) {
		Symbol symbol = program.getSymbolTable().getPrimarySymbol(target);
		if (symbol == null || symbol.getSource() == SourceType.DEFAULT) {
			return null;
		}
		String name = symbol.getName();
		if (name == null || name.isBlank()) {
			return null;
		}
		return name;
	}

	private static Address readPointer(Program program, Address at) {
		try {
			Memory memory = program.getMemory();
			long value = program.getDefaultPointerSize() == 8 ? memory.getLong(at)
					: Integer.toUnsignedLong(memory.getInt(at));
			if (value == 0) {
				return null;
			}
			return at.getNewAddress(value);
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * The address bounding this vtable: the next defined symbol in the same block (vtables are
	 * emitted back-to-back, each with its own symbol), or block end.
	 */
	private static Address bracketLimit(Program program, Address start) {
		MemoryBlock block = program.getMemory().getBlock(start);
		Address blockEnd = (block != null) ? block.getEnd().add(1) : start;
		SymbolIterator it =
			program.getSymbolTable().getPrimarySymbolIterator(start.add(1), true);
		if (it.hasNext()) {
			Address next = it.next().getAddress();
			if (block != null && block.contains(next)) {
				return next;
			}
		}
		return blockEnd;
	}

	/**
	 * The qualified class name encoded in a {@code _ZTV*} symbol's own name. Parsed directly from
	 * the Itanium type encoding ({@code _ZTV4Base} &rarr; {@code Base},
	 * {@code _ZTVN2ns5InnerE} &rarr; {@code ns::Inner}) rather than through the registered
	 * demanglers, whose program-format gates do not engage on every program (the same reason the
	 * {@code #37-4b-1} RTTI decoder parses directly). Templates and other encodings decline.
	 */
	private static String classNameOf(Symbol symbol) {
		String name = symbol.getName();
		if (name == null || !name.startsWith("_ZTV")) {
			return null;
		}
		String encoded = name.substring(4);
		if (encoded.startsWith("N") && encoded.endsWith("E")) {
			return parseNestedName(encoded.substring(1, encoded.length() - 1));
		}
		return parseSourceName(encoded, new int[] { 0 }, true);
	}

	private static String parseNestedName(String encoded) {
		int[] pos = new int[] { 0 };
		StringBuilder qualified = new StringBuilder();
		while (pos[0] < encoded.length()) {
			String component = parseSourceName(encoded, pos, false);
			if (component == null) {
				return null;
			}
			if (qualified.length() > 0) {
				qualified.append("::");
			}
			qualified.append(component);
		}
		return qualified.length() > 0 ? qualified.toString() : null;
	}

	private static String parseSourceName(String encoded, int[] pos, boolean mustConsumeAll) {
		int i = pos[0];
		int len = 0;
		int digits = 0;
		while (i < encoded.length() && Character.isDigit(encoded.charAt(i))) {
			len = len * 10 + (encoded.charAt(i) - '0');
			i++;
			digits++;
		}
		if (digits == 0 || len <= 0 || i + len > encoded.length()) {
			return null;
		}
		String component = encoded.substring(i, i + len);
		pos[0] = i + len;
		if (mustConsumeAll && pos[0] != encoded.length()) {
			return null;
		}
		return component;
	}
}
