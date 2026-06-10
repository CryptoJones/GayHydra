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

import ghidra.program.model.data.FunctionDefinition;

/**
 * Populates a {@link CppTypeSystem} with the virtual-function table recovered for a class. Given a
 * class's name and its already-recovered {@link SlotSpec slot facts} (one per vtable entry, in
 * layout order), the feeder resolves the owning {@link CppClass}, builds a {@link CppVTable} of one
 * {@link CppMethod} per slot, and attaches it to the class.
 *
 * <p>Rec 37 {@code #37-6}, grounded by DD-0014. Like the {@link CppDemanglingFeeder} and the
 * {@link CppRttiFeeder}, the feeder is a <b>pure consumer</b>: it does not read the vtable's
 * function-pointer array out of a {@code Program} and it does not depend on the in-tree vtable
 * recoverers (the script-land {@code classrecovery.Vftable} or the MSVC {@code VfTableModel}).
 * Reading the pointer array at the recovered vftable address, resolving each slot to a
 * {@code Function} and demangling its name belongs to the later program-scanning analyzer wrapper
 * ({@code #37-6b}); this is the translation core it calls.
 *
 * <p>Every slot method is marked {@code virtual} — occupying a vtable slot <em>is</em> virtual
 * dispatch — which is where {@link CppMethod#isVirtual()} is finally populated (DD-0011 and DD-0013
 * deferred it to this slice). The recovered slot methods are <em>fresh</em> {@link CppMethod}s
 * appended to the {@link CppVTable}; reconciling them with the (distinct) methods a
 * {@link CppDemanglingFeeder} attached to the same class is a later concern ({@code #37-7+}), and is
 * not done here. The feeder is {@code Address}-free, so {@link CppVTable#getTableAddress()} is left
 * null for the {@code #37-6b} scanner to set.
 */
public final class CppVTableFeeder {

	private final CppTypeSystem typeSystem;

	/**
	 * One recovered vtable-slot fact: the slot method's name, whether it is pure-virtual, and the
	 * slot function's resolved signature when the caller has one ({@code #37-12b}; null when the
	 * recoverer could not resolve a faithful signature &mdash; the slot still feeds without one).
	 * Slots are supplied to {@link #feedVtable} in vtable layout order.
	 *
	 * @param methodName the slot method's name; must not be null or blank
	 * @param isPureVirtual whether the slot is a pure-virtual ({@code = 0}) entry
	 * @param signature the slot function's resolved signature, or null when unrecovered
	 */
	public record SlotSpec(String methodName, boolean isPureVirtual,
			FunctionDefinition signature) {
		public SlotSpec {
			if (methodName == null || methodName.isBlank()) {
				throw new IllegalArgumentException("vtable slot method name must not be null or blank");
			}
		}

		/**
		 * Creates a slot fact with no resolved signature (the pre-{@code #37-12b} form).
		 *
		 * @param methodName the slot method's name; must not be null or blank
		 * @param isPureVirtual whether the slot is a pure-virtual ({@code = 0}) entry
		 */
		public SlotSpec(String methodName, boolean isPureVirtual) {
			this(methodName, isPureVirtual, null);
		}
	}

	/**
	 * Constructs a feeder writing into the given type system.
	 *
	 * @param typeSystem the model to populate; must not be null
	 */
	public CppVTableFeeder(CppTypeSystem typeSystem) {
		if (typeSystem == null) {
			throw new IllegalArgumentException("type system must not be null");
		}
		this.typeSystem = typeSystem;
	}

	/**
	 * Feeds one class's recovered vtable into the model. The owning {@link CppClass} is resolved (or
	 * created as an empty placeholder), a {@link CppVTable} is built with one {@link CppMethod} per
	 * element of {@code slots} (in order, each marked {@code virtual} and with its pure-virtual flag
	 * set), and the table is attached to the class. An empty {@code slots} list attaches an empty
	 * vtable and adds no slots.
	 *
	 * <p>An existing recovered {@link CppClass} is reused: neither its backing {@code Structure} nor
	 * its previously-fed {@link CppMethod}s are touched, and the new slot methods are appended to the
	 * {@link CppVTable} only (not to {@link CppClass#getMethods()}).
	 *
	 * @param owningClassName the owning class's fully-qualified name; must not be null or blank
	 * @param slots the recovered vtable slots in layout order; must not be null (may be empty)
	 * @return the {@link CppVTable} attached to the owning class
	 */
	public CppVTable feedVtable(String owningClassName, List<SlotSpec> slots) {
		if (owningClassName == null || owningClassName.isBlank()) {
			throw new IllegalArgumentException("owning class name must not be null or blank");
		}
		if (slots == null) {
			throw new IllegalArgumentException("slot list must not be null");
		}
		CppClass owner = CppClassResolution.resolveOrPlaceholder(typeSystem, owningClassName);
		CppVTable vtable = new CppVTable();
		for (SlotSpec slot : slots) {
			CppMethod method = new CppMethod(slot.methodName());
			method.setVirtual(true);
			method.setPureVirtual(slot.isPureVirtual());
			method.setSignature(slot.signature());
			vtable.addSlot(method);
		}
		owner.setVtable(vtable);
		return vtable;
	}
}
