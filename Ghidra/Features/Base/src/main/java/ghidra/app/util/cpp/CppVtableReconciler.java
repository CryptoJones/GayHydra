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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles the two disjoint {@link CppMethod} views a {@link CppClass} accumulates after passing
 * through both feeders: the declared methods in {@link CppClass#getMethods()} (from the
 * {@code CppDemanglingFeeder} {@code #37-3} — rich {@code const}/{@code static}/calling-convention
 * qualifiers, but {@link CppMethod#isVirtual()}{@code == false}) and the fresh per-slot methods in
 * {@link CppClass#getVtable()} (from the {@code CppVTableFeeder} {@code #37-6} —
 * {@link CppMethod#isVirtual()}{@code == true}, no qualifiers).
 *
 * <p>Rec 37 {@code #37-6c}, grounded by DD-0015. This is the pure in-model coherence pass that
 * DD-0014 deferred: it scans no {@code Program}, touches no {@code DataTypeManager}, and parses no
 * name. For each class it matches vtable slots to declared methods by <b>unqualified name, and only
 * when the name is unique on both sides</b> (a 1:1 pairing). A name shared by overloads on either
 * side is left unreconciled — disambiguating overloads needs method signatures (the DTM-coupled
 * {@code #37-7+} work), so this slice does the safe subset and never stamps virtuality onto the
 * wrong overload.
 *
 * <p>For a unique match the <b>declared method is canonical</b> (it carries the richer demangled
 * qualifiers the slot lacks): the reconciler sets its {@link CppMethod#setVirtual(boolean)} and
 * copies the slot's pure-virtual flag, then rewrites the slot to reference that declared method via
 * {@link CppVTable#setSlot(int, CppMethod)} — leaving a single {@link CppMethod} per function
 * reachable from both {@link CppClass#getMethods()} and the vtable. It never adds or removes a
 * method and never mutates a backing {@code Structure} (the DD-0011 projection guardrail holds), and
 * it is idempotent and order-independent: re-running it is a no-op.
 */
public final class CppVtableReconciler {

	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a reconciler over the given model.
	 *
	 * @param typeSystem the model whose classes are reconciled; must not be null
	 */
	public CppVtableReconciler(CppTypeSystem typeSystem) {
		if (typeSystem == null) {
			throw new IllegalArgumentException("type system must not be null");
		}
		this.typeSystem = typeSystem;
	}

	/**
	 * Reconciles every class registered in the model. Each class is reconciled independently; the
	 * whole pass is idempotent.
	 */
	public void reconcileAll() {
		for (CppClass cppClass : typeSystem.getCppClasses().values()) {
			reconcile(cppClass);
		}
	}

	/**
	 * Reconciles the single class registered under the given name. A name unknown to the model is a
	 * no-op.
	 *
	 * @param className the registered class name; must not be null
	 */
	public void reconcile(String className) {
		if (className == null) {
			throw new IllegalArgumentException("class name must not be null");
		}
		CppClass cppClass = typeSystem.getCppClass(className);
		if (cppClass != null) {
			reconcile(cppClass);
		}
	}

	/**
	 * Reconciles a single class: matches its vtable slots to its declared methods by unique
	 * unqualified name and unifies each 1:1 match onto the canonical declared method. A class with
	 * no vtable, an empty vtable, or no declared methods is a no-op.
	 *
	 * @param cppClass the class to reconcile; must not be null
	 */
	public void reconcile(CppClass cppClass) {
		if (cppClass == null) {
			throw new IllegalArgumentException("class must not be null");
		}
		CppVTable vtable = cppClass.getVtable();
		if (vtable == null || vtable.getSlotCount() == 0) {
			return;
		}
		List<CppMethod> declared = cppClass.getMethods();
		if (declared.isEmpty()) {
			return;
		}

		Map<String, CppMethod> uniqueDeclared = uniqueByName(declared);
		if (uniqueDeclared.isEmpty()) {
			return;
		}

		Map<String, Integer> slotNameCounts = new HashMap<>();
		for (CppMethod slot : vtable.getSlots()) {
			slotNameCounts.merge(slot.getName(), 1, Integer::sum);
		}

		for (int i = 0; i < vtable.getSlotCount(); i++) {
			CppMethod slot = vtable.getSlot(i);
			String name = slot.getName();
			if (slotNameCounts.get(name) != 1) {
				// Overloaded among the vtable slots — no sound 1:1 pairing without signatures.
				continue;
			}
			CppMethod canonical = uniqueDeclared.get(name);
			if (canonical == null || canonical == slot) {
				// No unique declared counterpart, or already unified (idempotent).
				continue;
			}
			canonical.setVirtual(true);
			canonical.setPureVirtual(slot.isPureVirtual());
			vtable.setSlot(i, canonical);
		}
	}

	/**
	 * {@return the methods whose unqualified name occurs exactly once in the list, keyed by name}
	 */
	private static Map<String, CppMethod> uniqueByName(List<CppMethod> methods) {
		Map<String, CppMethod> firstByName = new HashMap<>();
		Set<String> duplicated = new HashSet<>();
		for (CppMethod method : methods) {
			String name = method.getName();
			if (firstByName.putIfAbsent(name, method) != null) {
				duplicated.add(name);
			}
		}
		for (String name : duplicated) {
			firstByName.remove(name);
		}
		return firstByName;
	}
}
