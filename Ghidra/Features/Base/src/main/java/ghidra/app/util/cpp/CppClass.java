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

import ghidra.program.model.data.Structure;
import ghidra.program.model.listing.GhidraClass;

/**
 * A C++ class modelled as a <em>projection</em> over Ghidra's existing types: a required backing
 * {@link Structure} that owns the physical field layout, an optional backing {@link GhidraClass}
 * that owns the symbol scope, plus the C++-level annotations (base-class edges, methods, vtable)
 * that neither of those can express on its own.
 *
 * <p>Rec 37 {@code #37-2}, part of the model-only {@code CppTypeSystem} skeleton (DD-0011). The
 * key invariant is <b>projection, not replacement</b>: this class never mutates its backing
 * {@link Structure}, so every existing tool that reads the {@code Structure} keeps working
 * unchanged. The annotations layered here are additive C++ metadata only. Populating them is the
 * job of later slices (the demangling feeder {@code #37-3} and the RTTI / vtable analyzers
 * {@code #37-4} / {@code #37-5}); the skeleton just holds them.
 */
public final class CppClass {

	private final Structure backingStructure;
	private GhidraClass backingNamespace;
	private final List<CppBaseClass> baseClasses = new ArrayList<>();
	private final List<CppMethod> methods = new ArrayList<>();
	private CppVTable vtable;

	/**
	 * Constructs a C++ class projecting over the given backing structure.
	 *
	 * @param backingStructure the structure owning the physical layout; must not be null
	 */
	public CppClass(Structure backingStructure) {
		if (backingStructure == null) {
			throw new IllegalArgumentException("backing structure must not be null");
		}
		this.backingStructure = backingStructure;
	}

	/**
	 * {@return the class name, delegated to the backing structure so the two never diverge}
	 */
	public String getName() {
		return backingStructure.getName();
	}

	/**
	 * {@return the backing structure that owns this class's physical layout; never null}
	 */
	public Structure getBackingStructure() {
		return backingStructure;
	}

	/**
	 * {@return the backing namespace that owns this class's symbol scope, or null if none}
	 */
	public GhidraClass getBackingNamespace() {
		return backingNamespace;
	}

	/**
	 * Sets the backing namespace for this class.
	 *
	 * @param backingNamespace the owning namespace, or null to clear it
	 */
	public void setBackingNamespace(GhidraClass backingNamespace) {
		this.backingNamespace = backingNamespace;
	}

	/**
	 * Adds a base-class inheritance edge.
	 *
	 * @param baseClass the edge to add; must not be null
	 */
	public void addBaseClass(CppBaseClass baseClass) {
		if (baseClass == null) {
			throw new IllegalArgumentException("base class edge must not be null");
		}
		baseClasses.add(baseClass);
	}

	/**
	 * {@return an unmodifiable view of this class's base-class edges in declaration order}
	 */
	public List<CppBaseClass> getBaseClasses() {
		return Collections.unmodifiableList(baseClasses);
	}

	/**
	 * Attaches a method to this class.
	 *
	 * @param method the method to add; must not be null
	 */
	public void addMethod(CppMethod method) {
		if (method == null) {
			throw new IllegalArgumentException("method must not be null");
		}
		methods.add(method);
	}

	/**
	 * {@return an unmodifiable view of this class's methods in insertion order}
	 */
	public List<CppMethod> getMethods() {
		return Collections.unmodifiableList(methods);
	}

	/**
	 * {@return this class's virtual-function table, or null if it has none / not yet recovered}
	 */
	public CppVTable getVtable() {
		return vtable;
	}

	/**
	 * Sets this class's virtual-function table.
	 *
	 * @param vtable the vtable, or null to clear it
	 */
	public void setVtable(CppVTable vtable) {
		this.vtable = vtable;
	}
}
