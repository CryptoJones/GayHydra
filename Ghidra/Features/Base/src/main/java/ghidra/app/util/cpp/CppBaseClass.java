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

/**
 * One inheritance edge from a derived {@link CppClass} to a base {@link CppClass}, recording the
 * base subobject's byte offset within the derived layout and the {@code virtual} / access
 * qualifiers that the backing {@link ghidra.program.model.data.Structure} does not capture.
 *
 * <p>Rec 37 {@code #37-2}, part of the model-only {@code CppTypeSystem} skeleton (DD-0011). This is
 * an immutable value holder. For a virtual base the {@code offset} is the statically-recovered
 * offset where known; resolving the dynamic vbase offset is left to a later slice. The skeleton
 * stores the edge but performs no layout arithmetic of its own.
 */
public final class CppBaseClass {

	private final CppClass baseClass;
	private final int offset;
	private final boolean virtualBase;
	private final boolean publicBase;

	/**
	 * Constructs an inheritance edge.
	 *
	 * @param baseClass the base class; must not be null
	 * @param offset the byte offset of the base subobject within the derived layout
	 * @param virtualBase whether this is a {@code virtual} base
	 * @param publicBase whether this is a {@code public} base (false for protected/private)
	 */
	public CppBaseClass(CppClass baseClass, int offset, boolean virtualBase, boolean publicBase) {
		if (baseClass == null) {
			throw new IllegalArgumentException("base class must not be null");
		}
		this.baseClass = baseClass;
		this.offset = offset;
		this.virtualBase = virtualBase;
		this.publicBase = publicBase;
	}

	/**
	 * {@return the base class on the far end of this edge; never null}
	 */
	public CppClass getBaseClass() {
		return baseClass;
	}

	/**
	 * {@return the byte offset of the base subobject within the derived class layout}
	 */
	public int getOffset() {
		return offset;
	}

	/**
	 * {@return true if this is a {@code virtual} base}
	 */
	public boolean isVirtual() {
		return virtualBase;
	}

	/**
	 * {@return true if this is a {@code public} base}
	 */
	public boolean isPublic() {
		return publicBase;
	}
}
