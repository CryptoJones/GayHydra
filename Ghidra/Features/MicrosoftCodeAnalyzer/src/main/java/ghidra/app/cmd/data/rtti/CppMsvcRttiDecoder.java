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
import ghidra.app.util.cpp.CppRttiFeeder.BaseSpec;
import ghidra.program.model.data.InvalidDataTypeException;

/**
 * The MSVC half of Rec 37 {@code #37-5}: decodes Microsoft Visual C++ RTTI descriptors into the
 * ABI-neutral {@link BaseSpec} inheritance facts that {@link ghidra.app.util.cpp.CppRttiFeeder} consumes.
 * This is the pure-decode core, the counterpart to the Itanium {@code #37-4b} work; it reads only the
 * already-laid-down {@link Rtti1Model RTTIBaseClassDescriptor} models and never scans a program (that is
 * the {@code #37-5} analyzer/driver slice that walks the binary and calls the feeder).
 *
 * <p><b>Two pure decodes.</b> {@link #decodeBase(Rtti1Model)} ({@code #37-5-1}) turns a
 * single {@code RTTIBaseClassDescriptor} into one direct-base fact:
 * <ul>
 * <li>the base class's name from its {@link TypeDescriptorModel#getDescriptorName() type descriptor}
 * (the demangled unqualified name, e.g. {@code Base} &mdash; the same form {@link
 * ghidra.app.util.cpp.CppTypeSystem} keys classes by),</li>
 * <li>its byte offset from the descriptor's {@code mdisp} (member displacement),</li>
 * <li>its access from the descriptor's {@code attributes}: {@code public} unless the
 * {@code BCD_PRIVORPROTBASE} bit is set.</li>
 * </ul>
 *
 * <p>{@link #decodeClass(Rtti3Model)} ({@code #37-5-2}) lifts that to a whole class: from a
 * {@code RTTIClassHierarchyDescriptor} it recovers the class's own name and the list of its
 * <em>direct</em> bases. MSVC lays the base-class array out as the <em>full</em> hierarchy in preorder
 * &mdash; index 0 is the class itself, then every transitive ancestor &mdash; with each
 * {@code RTTIBaseClassDescriptor} carrying a {@code numContainedBases} count of its own subtree. The
 * decoder walks that array skipping self (index 0) and, at each direct base, skips past the base's
 * {@code numContainedBases} contained entries, so a base reached only through another base (a
 * <em>transitive</em> ancestor) is never emitted as a direct base.
 *
 * <p><b>Non-virtual bases only.</b> A descriptor whose {@code pdisp} is not {@code -1} names a
 * <em>virtual</em> base, whose true subobject offset is {@code *(vbtable + vdisp) + pdisp} &mdash; it
 * depends on the runtime {@code vbtable} contents, program data this pure descriptor decode cannot reach.
 * Such a descriptor is declined here (yields {@code null}); recovering virtual-base offsets is deferred
 * to the program-scanning analyzer slice that has the {@code vbtable}. For a non-virtual base
 * ({@code pdisp == -1}) the offset is exactly {@code mdisp}.
 *
 * <p><b>Advisory, never wrong.</b> Like the rest of the Rec 37 recognition pipeline, the decoder is
 * total-failure-safe: a null descriptor, a descriptor that does not validate, a virtual base, or one
 * whose type descriptor yields no printable name contributes no fact ({@code null}), never an exception
 * or a mis-decode.
 */
public final class CppMsvcRttiDecoder {

	/**
	 * The {@code BCD_PRIVORPROTBASE} bit of an {@code RTTIBaseClassDescriptor}'s {@code attributes}
	 * field: when set, the base is inherited {@code private} or {@code protected} rather than
	 * {@code public}. From the MSVC {@code <rttidata.h>} base-class-descriptor attribute flags (Ghidra's
	 * {@link Rtti1Model} exposes the raw {@code attributes} word but does not interpret it).
	 */
	private static final int BCD_PRIVORPROTBASE = 0x04;

	/** Sentinel {@code pdisp} marking a non-virtual base (a virtual base carries a vbtable offset). */
	private static final int PDISP_NONVIRTUAL = -1;

	private CppMsvcRttiDecoder() {
		// static decode utility
	}

	/**
	 * Decodes one MSVC {@code RTTIBaseClassDescriptor} into a direct-base fact.
	 *
	 * @param descriptor the base class descriptor model to decode; may be null
	 * @return the recovered {@link BaseSpec}, or null if the descriptor is null, does not validate,
	 *         names a virtual base (offset needs the runtime vbtable), or yields no printable base name
	 */
	public static BaseSpec decodeBase(Rtti1Model descriptor) {
		if (descriptor == null) {
			return null;
		}
		try {
			descriptor.validate();
			// A virtual base's offset is *(vbtable + vdisp) + pdisp — runtime data this pure decode
			// cannot reach; defer it to the program-scanning analyzer that has the vbtable.
			if (descriptor.getPDisp() != PDISP_NONVIRTUAL) {
				return null;
			}
			TypeDescriptorModel rtti0 = descriptor.getRtti0Model();
			if (rtti0 == null) {
				return null;
			}
			String baseName = rtti0.getDescriptorName();
			if (baseName == null || baseName.isBlank()) {
				return null;
			}
			long offset = descriptor.getMDisp();
			boolean isPublic = (descriptor.getAttributes() & BCD_PRIVORPROTBASE) == 0;
			return new BaseSpec(baseName, offset, false, isPublic);
		}
		catch (InvalidDataTypeException e) {
			return null;
		}
	}

	/**
	 * One MSVC class decoded from its {@code RTTIClassHierarchyDescriptor}: the class's own
	 * (demangled, unqualified) name and the {@link BaseSpec} facts for its <em>direct</em> bases only.
	 *
	 * @param derivedName the class's own name (the form {@link ghidra.app.util.cpp.CppTypeSystem} keys by)
	 * @param directBases its direct non-virtual bases, in base-class-array order; never null, possibly empty
	 */
	public record DecodedClass(String derivedName, List<BaseSpec> directBases) {}

	/**
	 * Decodes one MSVC {@code RTTIClassHierarchyDescriptor} into its class name and direct-base list.
	 *
	 * <p>The descriptor's base-class array holds the full hierarchy in preorder: index 0 is the class
	 * itself, then every transitive ancestor, each carrying its own {@code numContainedBases} subtree
	 * size. The walk starts after self (index 1) and, at each entry it accepts as a direct base, jumps
	 * past that base's contained entries ({@code i += 1 + numContainedBases}); the entries it jumps over
	 * are exactly that base's own ancestors, i.e. <em>transitive</em> bases of this class, which are not
	 * emitted. Virtual bases (and any entry that fails to decode) are skipped by {@link
	 * #decodeBase(Rtti1Model)} but still advance the walk, so the preorder layout stays aligned.
	 *
	 * @param classHierarchy the class hierarchy descriptor model to decode; may be null
	 * @return the recovered {@link DecodedClass}, or null if it is null, does not validate, or yields no
	 *         printable class name
	 */
	public static DecodedClass decodeClass(Rtti3Model classHierarchy) {
		if (classHierarchy == null) {
			return null;
		}
		try {
			classHierarchy.validate();
			TypeDescriptorModel rtti0 = classHierarchy.getRtti0Model();
			if (rtti0 == null) {
				return null;
			}
			String derivedName = rtti0.getDescriptorName();
			if (derivedName == null || derivedName.isBlank()) {
				return null;
			}
			List<BaseSpec> directBases = new ArrayList<>();
			Rtti2Model baseArray = classHierarchy.getRtti2Model();
			int count = classHierarchy.getRtti1Count();
			// Preorder walk: skip self (index 0), then at each direct base jump past its contained
			// (transitive) entries so only direct bases are emitted.
			int i = 1;
			while (i < count) {
				Rtti1Model entry = baseArray.getRtti1Model(i);
				BaseSpec spec = decodeBase(entry);
				if (spec != null) {
					directBases.add(spec);
				}
				int contained = entry.getNumBases();
				if (contained < 0) {
					break;
				}
				i += 1 + contained;
			}
			return new DecodedClass(derivedName, directBases);
		}
		catch (InvalidDataTypeException e) {
			return null;
		}
	}
}
