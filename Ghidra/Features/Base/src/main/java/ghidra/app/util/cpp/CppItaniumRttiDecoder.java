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

import ghidra.app.util.cpp.CppRttiFeeder.BaseSpec;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

/**
 * Decodes one Itanium-ABI {@code typeinfo} object into the {@link CppRttiFeeder} facts — the
 * Itanium counterpart of {@code CppMsvcRttiDecoder} (Rec 37 {@code #37-4b-1}; the
 * hint-recall corpus measured every type-resolving form at zero on ELF because this leg
 * never existed).
 *
 * <p><b>Symbol-anchored, length-bracketed, never-wrong.</b> The decode anchors entirely on
 * {@code _ZTI*} symbols: the class name is parsed from the symbol's own Itanium type encoding
 * ({@code _ZTI4Base} &rarr; {@code Base}, {@code _ZTIN2ns5InnerE} &rarr; {@code ns::Inner};
 * templates and other encodings decline for this slice), never from chasing the mangled
 * name-string pointer; a base entry must point <em>at</em> another {@code _ZTI} symbol to
 * count. The
 * typeinfo flavor is decided structurally from the object's word count, bracketed by the next
 * defined symbol (Itanium typeinfo objects are emitted back-to-back in {@code .rodata} /
 * {@code .data.rel.ro}, each with its own symbol):
 *
 * <ul>
 * <li><b>2 words</b> ({@code __class_type_info}: vtable ptr, name ptr) &mdash; no bases.</li>
 * <li><b>3 words</b> ({@code __si_class_type_info}: + base typeinfo ptr) &mdash; exactly one
 * public non-virtual base at offset 0, by ABI definition.</li>
 * <li><b>&ge; 5 words</b> ({@code __vmi_class_type_info}: + one word packing two 32-bit
 * {@code unsigned int}s ({@code __flags}, {@code __base_count}) + {@code count} entries of
 * (base typeinfo ptr, {@code __offset_flags} long)) &mdash; {@code offset_flags >> 8} is the
 * signed byte offset, bit 0 virtual, bit 1 public.</li>
 * </ul>
 *
 * <p>Anything else &mdash; an unreadable word, a base pointer naming no {@code _ZTI} symbol, a
 * count that disagrees with the bracketed length, a virtual base (its true offset rides the
 * vtable's virtual-base offsets, not this record) &mdash; declines the whole class with
 * {@code null}. Total-failure-safe like every Rec 37 decoder.
 */
public final class CppItaniumRttiDecoder {

	/** A decoded class: its qualified name and its direct bases, in declaration order. */
	public record DecodedClass(String derivedName, List<BaseSpec> directBases) {}

	private static final long VMI_VIRTUAL_MASK = 0x1;
	private static final long VMI_PUBLIC_MASK = 0x2;
	private static final int VMI_OFFSET_SHIFT = 8;
	/** Sanity bound on {@code __base_count} — beyond this the word is not a vmi header. */
	private static final int MAX_BASES = 64;

	private CppItaniumRttiDecoder() {
		// static decode utility
	}

	/**
	 * Decodes the typeinfo object at the given {@code _ZTI*} symbol.
	 *
	 * @param program the program holding the typeinfo data
	 * @param typeinfoSymbol the {@code _ZTI*} symbol at the object's start; may be null
	 * @return the decoded class, or null when anything fails to validate (never-wrong)
	 */
	public static DecodedClass decodeClass(Program program, Symbol typeinfoSymbol) {
		if (program == null || typeinfoSymbol == null) {
			return null;
		}
		String derivedName = classNameOf(program, typeinfoSymbol);
		if (derivedName == null) {
			return null;
		}
		Address start = typeinfoSymbol.getAddress();
		int ptrSize = program.getDefaultPointerSize();
		long length = bracketedLength(program, start);
		if (length < 2L * ptrSize || (length % ptrSize) != 0) {
			return null;
		}
		long words = length / ptrSize;
		if (words == 2) {
			return new DecodedClass(derivedName, List.of());
		}
		if (words == 3) {
			// __si_class_type_info: single public non-virtual base at offset 0 by definition.
			String baseName = baseNameAt(program, start, 2 * ptrSize);
			if (baseName == null) {
				return null;
			}
			return new DecodedClass(derivedName, List.of(new BaseSpec(baseName, 0, false, true)));
		}
		return decodeVmi(program, derivedName, start, ptrSize, words);
	}

	private static DecodedClass decodeVmi(Program program, String derivedName, Address start,
			int ptrSize, long words) {
		Memory memory = program.getMemory();
		try {
			// __flags and __base_count are 32-bit unsigned ints sharing the third word.
			int count = memory.getInt(start.add(2L * ptrSize + 4));
			if (count < 1 || count > MAX_BASES) {
				return null;
			}
			if (words != 3 + 2L * count) {
				return null;
			}
			List<BaseSpec> bases = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				long entry = start.getOffset() + 3L * ptrSize + i * 2L * ptrSize;
				String baseName =
					baseNameAt(program, start, entry - start.getOffset());
				if (baseName == null) {
					return null;
				}
				long offsetFlags =
					memory.getLong(start.add(entry - start.getOffset() + ptrSize));
				boolean isVirtual = (offsetFlags & VMI_VIRTUAL_MASK) != 0;
				if (isVirtual) {
					// A virtual base's true offset rides the vtable, not this record — decline
					// the class rather than record a wrong offset (the MSVC decoder's rule).
					return null;
				}
				long offset = offsetFlags >> VMI_OFFSET_SHIFT;
				boolean isPublic = (offsetFlags & VMI_PUBLIC_MASK) != 0;
				bases.add(new BaseSpec(baseName, offset, false, isPublic));
			}
			return new DecodedClass(derivedName, bases);
		}
		catch (Exception e) {
			return null;
		}
	}

	/** The qualified class name a base-entry pointer at {@code start+fieldOffset} names, or null. */
	private static String baseNameAt(Program program, Address start, long fieldOffset) {
		try {
			Address ptr = readPointer(program, start.add(fieldOffset));
			if (ptr == null) {
				return null;
			}
			Symbol base = program.getSymbolTable().getPrimarySymbol(ptr);
			if (base == null) {
				return null;
			}
			return classNameOf(program, base);
		}
		catch (Exception e) {
			return null;
		}
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
	 * The object length bracketed by the next defined symbol in the same memory block (typeinfo
	 * objects are emitted back-to-back, each with its own symbol), or to block end.
	 */
	private static long bracketedLength(Program program, Address start) {
		MemoryBlock block = program.getMemory().getBlock(start);
		if (block == null) {
			return 0;
		}
		long limit = block.getEnd().getOffset() + 1;
		SymbolIterator it =
			program.getSymbolTable().getPrimarySymbolIterator(start.add(1), true);
		if (it.hasNext()) {
			Address next = it.next().getAddress();
			if (block.contains(next)) {
				limit = next.getOffset();
			}
		}
		return limit - start.getOffset();
	}

	/**
	 * The qualified class name encoded in a {@code _ZTI*} symbol's own name. The Itanium type
	 * encoding for a class is parsed directly — {@code _ZTI4Base} &rarr; {@code Base},
	 * {@code _ZTIN2ns5InnerE} &rarr; {@code ns::Inner} — rather than through the registered
	 * demanglers, whose program-format applicability gates do not engage on every program this
	 * decoder may see (probe-grounded: {@code DemanglerUtil} yields nothing on a bare fixture).
	 * Any other encoding (templates, substitutions, pointers) declines with null for this slice;
	 * the class is simply not fed — never-wrong over partially-right.
	 */
	private static String classNameOf(Program program, Symbol symbol) {
		String name = symbol.getName();
		if (name == null || !name.startsWith("_ZTI")) {
			return null;
		}
		String encoded = name.substring(4);
		if (encoded.startsWith("N") && encoded.endsWith("E")) {
			return parseNestedName(encoded.substring(1, encoded.length() - 1));
		}
		return parseSourceName(encoded, new int[] { 0 }, true);
	}

	/** Parses {@code <len><chars>...} components joined with {@code ::}, consuming everything. */
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

	/**
	 * Parses one length-prefixed source name at {@code pos}, advancing it. With
	 * {@code mustConsumeAll}, trailing characters decline (a template or array encoding, not a
	 * plain class).
	 */
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
