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
 * How a {@link CppMethod}'s arguments are passed, with the placement of the implicit {@code this}
 * pointer called out explicitly (it is calling-convention dependent: {@code __thiscall} passes it
 * in {@code ecx}, the Itanium ABI passes it as an ordinary leading argument).
 *
 * <p>Rec 37 {@code #37-2}, part of the model-only {@code CppTypeSystem} skeleton (DD-0011). This is
 * a plain immutable value holder; the {@code CppDemanglingFeeder} ({@code #37-3}) populates it from
 * the demangled signature's calling convention, and later slices map it onto a concrete
 * {@code PrototypeModel}.
 */
public final class CppCallingConvention {

	/** Ordinal returned by {@link #getThisParameterOrdinal()} for a method with no {@code this}. */
	public static final int NO_THIS = -1;

	private final String name;
	private final int thisParameterOrdinal;

	/**
	 * Constructs a calling convention descriptor.
	 *
	 * @param name the convention name (e.g. {@code __thiscall}, {@code __cdecl}), may be null
	 * @param thisParameterOrdinal the zero-based argument position of the implicit {@code this}
	 *            pointer, or {@link #NO_THIS} for a static / free function
	 */
	public CppCallingConvention(String name, int thisParameterOrdinal) {
		this.name = name;
		this.thisParameterOrdinal = thisParameterOrdinal;
	}

	/**
	 * {@return the convention name, or null if unknown}
	 */
	public String getName() {
		return name;
	}

	/**
	 * {@return the zero-based argument position of the implicit {@code this} pointer, or
	 * {@link #NO_THIS} when the method does not take one}
	 */
	public int getThisParameterOrdinal() {
		return thisParameterOrdinal;
	}

	/**
	 * {@return true if the method is passed an implicit {@code this} pointer}
	 */
	public boolean hasImplicitThis() {
		return thisParameterOrdinal != NO_THIS;
	}
}
