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

import ghidra.program.model.data.FunctionDefinition;

/**
 * A single C++ member (or free) function attached to a {@link CppClass}, carrying the C++-level
 * qualifiers that the backing {@link ghidra.program.model.data.Structure} cannot express on its
 * own (virtual / pure-virtual / const / static) plus an optional {@link CppCallingConvention}.
 *
 * <p>Rec 37 {@code #37-2}, part of the model-only {@code CppTypeSystem} skeleton (DD-0011). This is
 * a plain mutable value holder; the {@code CppDemanglingFeeder} ({@code #37-3}) populates the
 * qualifiers and signature from a demangled symbol, and a later slice maps the signature onto a
 * concrete {@link ghidra.program.model.listing.Function} prototype. The skeleton stores but does
 * not interpret these fields.
 */
public final class CppMethod {

	private final String name;
	private FunctionDefinition signature;
	private CppCallingConvention callingConvention;
	private boolean virtualMethod;
	private boolean pureVirtual;
	private boolean constMethod;
	private boolean staticMethod;

	/**
	 * Constructs a method with the given name and no signature, no calling convention, and all
	 * qualifiers cleared.
	 *
	 * @param name the (unqualified) method name; must not be null
	 */
	public CppMethod(String name) {
		if (name == null) {
			throw new IllegalArgumentException("method name must not be null");
		}
		this.name = name;
	}

	/**
	 * {@return the (unqualified) method name; never null}
	 */
	public String getName() {
		return name;
	}

	/**
	 * {@return the function signature projected for this method, or null if not yet resolved}
	 */
	public FunctionDefinition getSignature() {
		return signature;
	}

	/**
	 * Sets the function signature for this method.
	 *
	 * @param signature the signature, or null to clear it
	 */
	public void setSignature(FunctionDefinition signature) {
		this.signature = signature;
	}

	/**
	 * {@return the calling convention, or null if unknown}
	 */
	public CppCallingConvention getCallingConvention() {
		return callingConvention;
	}

	/**
	 * Sets the calling convention descriptor.
	 *
	 * @param callingConvention the convention, or null to clear it
	 */
	public void setCallingConvention(CppCallingConvention callingConvention) {
		this.callingConvention = callingConvention;
	}

	/**
	 * {@return true if this method is declared {@code virtual} (including pure-virtual)}
	 */
	public boolean isVirtual() {
		return virtualMethod;
	}

	/**
	 * Marks this method as virtual or non-virtual.
	 *
	 * @param virtualMethod whether the method is virtual
	 */
	public void setVirtual(boolean virtualMethod) {
		this.virtualMethod = virtualMethod;
	}

	/**
	 * {@return true if this method is pure-virtual (an abstract {@code = 0} slot)}
	 */
	public boolean isPureVirtual() {
		return pureVirtual;
	}

	/**
	 * Marks this method as pure-virtual or not. A pure-virtual method is implicitly virtual, but
	 * this holder leaves the {@link #isVirtual()} flag independent; the feeder is responsible for
	 * setting both consistently.
	 *
	 * @param pureVirtual whether the method is pure-virtual
	 */
	public void setPureVirtual(boolean pureVirtual) {
		this.pureVirtual = pureVirtual;
	}

	/**
	 * {@return true if this method is declared {@code const}}
	 */
	public boolean isConst() {
		return constMethod;
	}

	/**
	 * Marks this method as {@code const}-qualified or not.
	 *
	 * @param constMethod whether the method is const
	 */
	public void setConst(boolean constMethod) {
		this.constMethod = constMethod;
	}

	/**
	 * {@return true if this method is {@code static} (and therefore takes no implicit {@code this})}
	 */
	public boolean isStatic() {
		return staticMethod;
	}

	/**
	 * Marks this method as {@code static} or not.
	 *
	 * @param staticMethod whether the method is static
	 */
	public void setStatic(boolean staticMethod) {
		this.staticMethod = staticMethod;
	}
}
