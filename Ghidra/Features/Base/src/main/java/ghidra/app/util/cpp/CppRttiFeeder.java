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

import java.util.List;

/**
 * Populates a {@link CppTypeSystem} with inheritance edges recovered from C++ RTTI. Given a derived
 * class's name and its already-recovered direct {@link BaseSpec base facts}, the feeder resolves the
 * derived and base {@link CppClass}es and attaches a {@link CppBaseClass} edge for each base.
 *
 * <p>Rec 37 {@code #37-4}, grounded by DD-0013. Like the {@link CppDemanglingFeeder}, the feeder is a
 * <b>pure consumer</b>: it does not walk a program's RTTI and it does not depend on the (script-land)
 * {@code classrecovery.GccTypeinfo} recoverer. Decoding the Itanium {@code offset_flags} word (the
 * {@code __virtual_mask}/{@code __public_mask} bits and the offset shift) and the MSVC descriptors
 * into the {@link BaseSpec} facts below belongs to the later program-scanning analyzer wrappers
 * ({@code #37-4b} Itanium, {@code #37-5} MSVC); this is the ABI-neutral translation core they call.
 *
 * <p>It records the inheritance graph only. Mapping vtable slots to {@link CppMethod}s and setting
 * {@code isVirtual} on a method is <em>not</em> done here — that arrives with the vtable analyzer
 * ({@code #37-6}).
 */
public final class CppRttiFeeder {

	private final CppTypeSystem typeSystem;

	/**
	 * One recovered direct-base fact: the base class's name and the {@code virtual}/access and offset
	 * already decoded from the ABI's inheritance record. The offset is the byte offset of the base
	 * subobject within the derived layout (the statically-known offset for a {@code virtual} base).
	 *
	 * @param baseName the base class's fully-qualified name; must not be null or blank
	 * @param offset the base subobject's byte offset within the derived layout
	 * @param isVirtual whether this is a {@code virtual} base
	 * @param isPublic whether this is a {@code public} base (false for protected/private)
	 */
	public record BaseSpec(String baseName, long offset, boolean isVirtual, boolean isPublic) {
		public BaseSpec {
			if (baseName == null || baseName.isBlank()) {
				throw new IllegalArgumentException("base class name must not be null or blank");
			}
		}
	}

	/**
	 * Constructs a feeder writing into the given type system.
	 *
	 * @param typeSystem the model to populate; must not be null
	 */
	public CppRttiFeeder(CppTypeSystem typeSystem) {
		if (typeSystem == null) {
			throw new IllegalArgumentException("type system must not be null");
		}
		this.typeSystem = typeSystem;
	}

	/**
	 * Feeds one class's recovered inheritance into the model. The derived {@link CppClass} is
	 * resolved (or created as an empty placeholder), and one {@link CppBaseClass} edge is attached
	 * per element of {@code bases}, in order. An empty {@code bases} list (an Itanium
	 * {@code __class_type_info}, i.e. a class with no bases) simply ensures the derived class exists
	 * and adds no edges.
	 *
	 * <p><b>Idempotent:</b> a base identical to an already-attached edge (same resolved base class,
	 * offset, virtuality, and access) is skipped, so a contributor that runs repeatedly — an analyzer
	 * re-triggered as analysis progresses — re-feeds without duplicating the graph. A genuinely
	 * different edge to the same base (a repeated non-virtual base at another offset) still attaches.
	 *
	 * <p>The base subobject offset is narrowed {@code long}→{@code int}: an offset within a single
	 * class layout fits {@code int}, and {@link CppBaseClass} models it as {@code int}.
	 *
	 * @param derivedName the derived class's fully-qualified name; must not be null or blank
	 * @param bases the recovered direct bases; must not be null (may be empty)
	 * @return the derived {@link CppClass}
	 */
	public CppClass feedClass(String derivedName, List<BaseSpec> bases) {
		if (derivedName == null || derivedName.isBlank()) {
			throw new IllegalArgumentException("derived class name must not be null or blank");
		}
		if (bases == null) {
			throw new IllegalArgumentException("base list must not be null");
		}
		CppClass derived = CppClassResolution.resolveOrPlaceholder(typeSystem, derivedName);
		for (BaseSpec base : bases) {
			CppClass baseClass = CppClassResolution.resolveOrPlaceholder(typeSystem, base.baseName());
			if (!hasIdenticalEdge(derived, baseClass, base)) {
				derived.addBaseClass(new CppBaseClass(baseClass, (int) base.offset(),
					base.isVirtual(), base.isPublic()));
			}
		}
		return derived;
	}

	private static boolean hasIdenticalEdge(CppClass derived, CppClass baseClass, BaseSpec base) {
		for (CppBaseClass existing : derived.getBaseClasses()) {
			if (existing.getBaseClass() == baseClass && existing.getOffset() == (int) base.offset() &&
				existing.isVirtual() == base.isVirtual() && existing.isPublic() == base.isPublic()) {
				return true;
			}
		}
		return false;
	}
}
