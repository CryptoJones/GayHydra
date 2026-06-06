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

import ghidra.program.model.data.StructureDataType;

/**
 * Shared class-resolution helper for the model feeders ({@link CppDemanglingFeeder},
 * {@link CppRttiFeeder}). A feeder routinely names a class for which the model holds no recovered
 * layout yet; this resolves the existing {@link CppClass} or defines one over a fresh empty
 * placeholder {@link StructureDataType}, preserving the model's non-null backing-{@code Structure}
 * invariant (DD-0011) without the feeder owning a layout.
 *
 * <p>Grounded by DD-0012 decision 4 (first introduced for the demangling feeder) and reused by
 * DD-0013 for the RTTI feeder's two edge endpoints. Lives in feeder-land rather than on
 * {@link CppTypeSystem} so the model holder keeps no knowledge of the concrete
 * {@link StructureDataType} placeholder type and no resolution behaviour of its own.
 */
final class CppClassResolution {

	private CppClassResolution() {
	}

	/**
	 * Resolves the {@link CppClass} for a class name, defining one over a fresh empty placeholder
	 * {@link StructureDataType} when the model has none yet. Never mutates an existing class's
	 * backing {@code Structure}.
	 *
	 * @param typeSystem the model to resolve within
	 * @param className the fully-qualified class name
	 * @return the existing or newly placeholder-backed {@link CppClass}
	 */
	static CppClass resolveOrPlaceholder(CppTypeSystem typeSystem, String className) {
		CppClass existing = typeSystem.getCppClass(className);
		if (existing != null) {
			return existing;
		}
		return typeSystem.defineClass(new StructureDataType(className, 0));
	}
}
