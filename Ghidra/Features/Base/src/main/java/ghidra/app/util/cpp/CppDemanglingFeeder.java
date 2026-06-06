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

import ghidra.app.util.demangler.Demangled;
import ghidra.app.util.demangler.DemangledFunction;
import ghidra.app.util.demangler.DemangledObject;
import ghidra.program.model.data.StructureDataType;

/**
 * Populates a {@link CppTypeSystem} from already-demangled symbols. Given a {@link DemangledObject}
 * produced by any demangler (Itanium {@code GnuDemangler}, MSVC {@code MicrosoftDemangler}, …), the
 * feeder maps a member function onto a {@link CppClass} + {@link CppMethod} in the model.
 *
 * <p>Rec 37 {@code #37-3}, grounded by DD-0012. The feeder is a <b>pure consumer</b> of the
 * demangled-name model: it does not run a demangler and it does not scan a program. Choosing a
 * demangler and walking a program's symbol table belong to the later analyzer slices
 * ({@code #37-4} / {@code #37-5}); this is the translation core those slices call.
 *
 * <p>It is demangler- and ABI-agnostic because it reads only the common {@link DemangledObject}
 * model. ABI-specific inheritance and vtable recovery (base-class edges, vtable slots, virtual
 * detection) is <em>not</em> done here — a demangled name alone does not reveal vtable membership —
 * and arrives with the RTTI / vtable analyzers ({@code #37-4} / {@code #37-5} / {@code #37-6}).
 */
public final class CppDemanglingFeeder {

	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a feeder writing into the given type system.
	 *
	 * @param typeSystem the model to populate; must not be null
	 */
	public CppDemanglingFeeder(CppTypeSystem typeSystem) {
		if (typeSystem == null) {
			throw new IllegalArgumentException("type system must not be null");
		}
		this.typeSystem = typeSystem;
	}

	/**
	 * Maps a single demangled symbol onto the model. Only a {@link DemangledFunction} with a
	 * non-empty namespace (a member function) is fed: its enclosing class is resolved (or created
	 * as an empty placeholder) and a {@link CppMethod} carrying the symbol's name, {@code const} /
	 * {@code static} qualifiers, and calling convention is attached.
	 *
	 * <p>A free function (no enclosing namespace) and any non-function demangled object are a no-op
	 * in this slice and return null.
	 *
	 * @param demangled the demangled symbol, or null
	 * @return the {@link CppMethod} that was attached, or null if nothing was fed
	 */
	public CppMethod feed(DemangledObject demangled) {
		if (!(demangled instanceof DemangledFunction function)) {
			return null;
		}
		Demangled namespace = function.getNamespace();
		if (namespace == null) {
			return null;
		}
		// The enclosing class is the function's namespace path. Note getNamespaceString() on the
		// function itself would append the function's own name, so qualify off the namespace node.
		String className = namespace.getNamespaceString();
		if (className == null || className.isBlank()) {
			return null;
		}

		CppClass cppClass = resolveClass(className);
		CppMethod method = new CppMethod(function.getName());
		method.setConst(function.isTrailingConst());
		method.setStatic(function.isStatic());
		method.setCallingConvention(toCallingConvention(function));
		cppClass.addMethod(method);
		return method;
	}

	/**
	 * Resolves the {@link CppClass} for a fully-qualified class name, defining one over a fresh
	 * empty placeholder {@link StructureDataType} when the model has none yet. This preserves the
	 * model's non-null backing-{@code Structure} invariant without the feeder needing a recovered
	 * layout; a later layout-recovery slice fills the placeholder in.
	 */
	private CppClass resolveClass(String className) {
		CppClass existing = typeSystem.getCppClass(className);
		if (existing != null) {
			return existing;
		}
		return typeSystem.defineClass(new StructureDataType(className, 0));
	}

	/**
	 * Builds the {@link CppCallingConvention} for a member function. The implicit {@code this} sits
	 * at ordinal 0 for a non-static member (as {@code __thiscall} surfaces it) and is absent for a
	 * {@code static} member. The convention name is taken verbatim from the demangler and may be
	 * null when the demangler did not record one.
	 */
	private static CppCallingConvention toCallingConvention(DemangledFunction function) {
		int thisOrdinal =
			function.isStatic() ? CppCallingConvention.NO_THIS : 0;
		return new CppCallingConvention(function.getCallingConvention(), thisOrdinal);
	}
}
