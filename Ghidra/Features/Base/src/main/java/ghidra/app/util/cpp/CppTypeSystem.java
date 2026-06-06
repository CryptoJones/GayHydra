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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.Structure;

/**
 * The entry point of the model-only C++ analysis frontend (Rec 37): a registry of {@link CppClass}
 * projections keyed by class name, optionally bound to the {@link DataTypeManager} that owns the
 * backing structures.
 *
 * <p>Rec 37 {@code #37-2}, the {@code CppTypeSystem} skeleton grounded by DD-0011. This is a plain
 * in-memory model holder with no analysis behaviour of its own: it does not scan a program, parse
 * symbols, or recover types. Those responsibilities belong to later slices — the demangling feeder
 * ({@code #37-3}), the RTTI analyzer ({@code #37-4}), and the vtable analyzer ({@code #37-5}) — which
 * call {@link #defineClass(Structure)} and populate the returned {@link CppClass}. The skeleton's
 * job is only to provide the data model those slices write into.
 *
 * <p>The {@link DataTypeManager} reference is nullable so the model can be exercised in isolation
 * (e.g. headless unit tests) against standalone {@link Structure} fixtures that are not yet bound
 * to a program's type manager.
 */
public final class CppTypeSystem {

	private final DataTypeManager dataTypeManager;
	private final Map<String, CppClass> classesByName = new LinkedHashMap<>();

	/**
	 * Constructs a type system not bound to any {@link DataTypeManager}.
	 */
	public CppTypeSystem() {
		this(null);
	}

	/**
	 * Constructs a type system bound to the given {@link DataTypeManager}.
	 *
	 * @param dataTypeManager the data type manager owning the backing structures, or null
	 */
	public CppTypeSystem(DataTypeManager dataTypeManager) {
		this.dataTypeManager = dataTypeManager;
	}

	/**
	 * Defines (or returns the already-defined) {@link CppClass} projecting over the given backing
	 * structure. Classes are keyed by {@link Structure#getName()}; defining a name that already
	 * exists returns the existing projection rather than clobbering it, so repeated feeder passes
	 * are idempotent.
	 *
	 * @param backingStructure the structure to project; must not be null
	 * @return the {@link CppClass} for that structure's name
	 */
	public CppClass defineClass(Structure backingStructure) {
		if (backingStructure == null) {
			throw new IllegalArgumentException("backing structure must not be null");
		}
		return classesByName.computeIfAbsent(backingStructure.getName(),
			n -> new CppClass(backingStructure));
	}

	/**
	 * {@return the {@link CppClass} registered under the given name, or null if none}
	 *
	 * @param name the class name
	 */
	public CppClass getCppClass(String name) {
		return classesByName.get(name);
	}

	/**
	 * {@return an unmodifiable view of all registered classes in definition order}
	 */
	public Map<String, CppClass> getCppClasses() {
		return Collections.unmodifiableMap(classesByName);
	}

	/**
	 * {@return the bound data type manager, or null if this model is unbound}
	 */
	public DataTypeManager getDataTypeManager() {
		return dataTypeManager;
	}
}
