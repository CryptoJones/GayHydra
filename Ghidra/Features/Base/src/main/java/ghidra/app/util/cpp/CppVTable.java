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
import java.util.Collections;
import java.util.List;

import ghidra.program.model.address.Address;

/**
 * The virtual-function table of a {@link CppClass}: an ordered list of {@link CppMethod} slots in
 * vtable layout order, plus the optional {@link Address} at which the table was recovered in the
 * program image.
 *
 * <p>Rec 37 {@code #37-2}, part of the model-only {@code CppTypeSystem} skeleton (DD-0011). This is
 * a plain mutable value holder. The {@code CppVTableFeeder} ({@code #37-6}) builds the table from
 * recovered slot facts and the program-scanning {@code CppVTableAnalyzer} ({@code #37-6b}) resolves
 * slot order from the recovered vftable; the skeleton only stores what it is given and preserves
 * insertion order. Slot index is the zero-based position within this single table and
 * does not attempt to model multiple-inheritance sub-vtables (deferred to a later slice).
 */
public final class CppVTable {

	private final List<CppMethod> slots = new ArrayList<>();
	private Address tableAddress;

	/**
	 * Appends a method to the end of the vtable in the next slot position.
	 *
	 * @param method the method occupying the slot; must not be null
	 * @return the zero-based index of the slot just added
	 */
	public int addSlot(CppMethod method) {
		if (method == null) {
			throw new IllegalArgumentException("vtable slot method must not be null");
		}
		slots.add(method);
		return slots.size() - 1;
	}

	/**
	 * {@return the method occupying the given zero-based slot index}
	 *
	 * @param index the slot index
	 * @throws IndexOutOfBoundsException if {@code index} is out of range
	 */
	public CppMethod getSlot(int index) {
		return slots.get(index);
	}

	/**
	 * Replaces the method occupying an existing slot in place, leaving the slot count and the order
	 * of every other slot unchanged. Used by the {@code CppVtableReconciler} ({@code #37-6c}) to
	 * point a slot at the canonical declared {@link CppMethod} once the two have been matched, so a
	 * single method object is reachable from both this table and {@link CppClass#getMethods()}.
	 *
	 * @param index the zero-based slot index to overwrite
	 * @param method the method to occupy the slot; must not be null
	 * @throws IndexOutOfBoundsException if {@code index} is out of range
	 * @throws IllegalArgumentException if {@code method} is null
	 */
	public void setSlot(int index, CppMethod method) {
		if (method == null) {
			throw new IllegalArgumentException("vtable slot method must not be null");
		}
		slots.set(index, method);
	}

	/**
	 * {@return the number of slots in this vtable}
	 */
	public int getSlotCount() {
		return slots.size();
	}

	/**
	 * {@return an unmodifiable view of the vtable slots in layout order}
	 */
	public List<CppMethod> getSlots() {
		return Collections.unmodifiableList(slots);
	}

	/**
	 * {@return the address at which this vtable was recovered, or null if unknown}
	 */
	public Address getTableAddress() {
		return tableAddress;
	}

	/**
	 * Sets the recovered address of this vtable.
	 *
	 * @param tableAddress the table address, or null to clear it
	 */
	public void setTableAddress(Address tableAddress) {
		this.tableAddress = tableAddress;
	}
}
