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

/**
 * The C++ decompiler-hint renderer (RFC-0001 §5): a stateless producer of C++-style rendering
 * strings from <em>already-resolved</em> {@link CppTypeSystem} model facts plus the operand
 * expressions supplied by its caller.
 *
 * <p>Rec 37 {@code #37-7} (virtual-method-call form, DD-0016) and {@code #37-8} (up/down-cast form,
 * DD-0017). This is the headless half of RFC §5. It holds no
 * {@link ghidra.program.model.listing.Program}, no
 * {@link ghidra.program.model.data.DataTypeManager}, and no decompiler / {@code HighFunction}
 * reference; it never scans, demangles, parses, or mutates the model. Its inputs are model objects
 * (a {@link CppClass}, a vtable slot index, an inheritance offset) plus operand expressions — the
 * receiver, arguments, or source pointer — handed in as opaque strings by whatever drives it. Its
 * output is the rendered string. The decompiler-side pattern-recognition pass that walks a
 * {@code HighFunction}, recognises the raw C-style idiom, recovers the {@code (class, slot)} or
 * {@code (derived, offset, direction)} it denotes, and calls this renderer is the deferred
 * {@code #37-7b}/{@code #37-8b} Program-coupled wrapper, not part of this slice.
 *
 * <p>Hints are advisory and additive: this renderer only produces a string; it never rewrites
 * p-code or replaces an analysis pass.
 */
public final class CppDecompilerHints {

	/**
	 * Renders a virtual method call through a vtable slot as C++ source — {@code receiver->name(args)}
	 * for a pointer receiver, {@code receiver.name(args)} for a value receiver — using the
	 * name-resolved slot method the {@code CppVTableFeeder} ({@code #37-6}) and
	 * {@code CppVtableReconciler} ({@code #37-6c}) produced.
	 *
	 * <p>When the slot cannot be named — the class has no vtable, the index is out of range, or the
	 * slot method's name is blank/unresolved — the renderer falls back to a neutral indirect-call
	 * form {@code receiver->vtable[i](args)} rather than throwing or fabricating a name, so a caller
	 * driving this from a partially-recovered model still gets a well-formed rendering.
	 *
	 * @param owningClass the class whose vtable the call dispatches through; must not be null
	 * @param slotIndex the zero-based vtable slot index the call targets
	 * @param receiverExpr the already-rendered receiver expression; must not be null or blank
	 * @param receiverIsPointer true to render pointer access ({@code ->}), false for value access
	 *            ({@code .})
	 * @param argumentExprs the already-rendered argument expressions in call order; must not be null
	 *            and must contain no null element (may be empty for a no-argument call)
	 * @return the rendered C++ call expression
	 * @throws IllegalArgumentException if {@code owningClass} is null, {@code receiverExpr} is null or
	 *             blank, or {@code argumentExprs} is null or contains a null element
	 */
	public String renderVirtualCall(CppClass owningClass, int slotIndex, String receiverExpr,
			boolean receiverIsPointer, List<String> argumentExprs) {
		if (owningClass == null) {
			throw new IllegalArgumentException("owning class must not be null");
		}
		if (receiverExpr == null || receiverExpr.isBlank()) {
			throw new IllegalArgumentException("receiver expression must not be null or blank");
		}
		if (argumentExprs == null) {
			throw new IllegalArgumentException("argument expression list must not be null");
		}
		String access = receiverIsPointer ? "->" : ".";
		String args = renderArguments(argumentExprs);
		String slotName = resolveSlotName(owningClass, slotIndex);
		if (slotName == null) {
			return receiverExpr + access + "vtable[" + slotIndex + "](" + args + ")";
		}
		return receiverExpr + access + slotName + "(" + args + ")";
	}

	/**
	 * Renders an <em>upcast</em> — a pointer adjustment from a derived object to one of its base
	 * subobjects — as {@code static_cast<Base*>(src)}, where {@code Base} is the class on the
	 * inheritance edge whose offset equals {@code baseOffset}.
	 *
	 * <p>{@code static_cast}, never {@code dynamic_cast}: the recovered constant offset is the
	 * compiler's structural base-subobject adjustment, which is exactly what {@code static_cast}
	 * denotes. If no non-virtual base edge sits at {@code baseOffset} — there is no such edge, or the
	 * only edge there is a {@code virtual} base whose offset is dynamic rather than the compile-time
	 * constant a {@code static_cast} represents — the renderer declines the cast and falls back to the
	 * neutral pointer-adjustment form {@code src + baseOffset} rather than fabricating a cast.
	 *
	 * @param derivedClass the class the source pointer starts at; must not be null
	 * @param baseOffset the recovered byte offset of the base subobject within the derived layout
	 * @param sourceExpr the already-rendered source pointer expression; must not be null or blank
	 * @return the rendered C++ cast, or the neutral adjustment when no eligible base edge matches
	 * @throws IllegalArgumentException if {@code derivedClass} is null or {@code sourceExpr} is null or
	 *             blank
	 */
	public String renderUpcast(CppClass derivedClass, int baseOffset, String sourceExpr) {
		return renderCast(derivedClass, baseOffset, sourceExpr, true);
	}

	/**
	 * Renders a <em>downcast</em> — a pointer adjustment from a base subobject back to the enclosing
	 * derived object — as {@code static_cast<Derived*>(src)}, where {@code Derived} is
	 * {@code derivedClass} itself, when a non-virtual base edge sits at {@code baseOffset}.
	 *
	 * <p>{@code static_cast}, never {@code dynamic_cast}: the recovered constant offset is the
	 * compiler's structural base-subobject adjustment, not an RTTI-checked conversion. As with
	 * {@link #renderUpcast}, an offset matching no non-virtual base edge declines the cast and falls
	 * back to the neutral pointer-adjustment form — {@code src - baseOffset} for a downcast.
	 *
	 * @param derivedClass the derived class the cast targets; must not be null
	 * @param baseOffset the recovered byte offset of the base subobject within the derived layout
	 * @param sourceExpr the already-rendered source pointer expression; must not be null or blank
	 * @return the rendered C++ cast, or the neutral adjustment when no eligible base edge matches
	 * @throws IllegalArgumentException if {@code derivedClass} is null or {@code sourceExpr} is null or
	 *             blank
	 */
	public String renderDowncast(CppClass derivedClass, int baseOffset, String sourceExpr) {
		return renderCast(derivedClass, baseOffset, sourceExpr, false);
	}

	private String renderCast(CppClass derivedClass, int baseOffset, String sourceExpr,
			boolean upcast) {
		if (derivedClass == null) {
			throw new IllegalArgumentException("derived class must not be null");
		}
		if (sourceExpr == null || sourceExpr.isBlank()) {
			throw new IllegalArgumentException("source expression must not be null or blank");
		}
		CppBaseClass edge = matchNonVirtualBaseEdge(derivedClass, baseOffset);
		if (edge == null) {
			return sourceExpr + (upcast ? " + " : " - ") + baseOffset;
		}
		String targetType = upcast ? edge.getBaseClass().getName() : derivedClass.getName();
		return "static_cast<" + targetType + "*>(" + sourceExpr + ")";
	}

	/**
	 * {@return the non-virtual base edge of {@code derivedClass} sitting at {@code baseOffset}, or null
	 * if none — a {@code virtual} base at that offset is treated as no match, since its dynamic offset
	 * cannot be a static cast}
	 */
	private static CppBaseClass matchNonVirtualBaseEdge(CppClass derivedClass, int baseOffset) {
		for (CppBaseClass edge : derivedClass.getBaseClasses()) {
			if (!edge.isVirtual() && edge.getOffset() == baseOffset) {
				return edge;
			}
		}
		return null;
	}

	/**
	 * {@return the name of the method occupying the given slot, or null if the slot cannot be named}
	 */
	private static String resolveSlotName(CppClass owningClass, int slotIndex) {
		CppVTable vtable = owningClass.getVtable();
		if (vtable == null || slotIndex < 0 || slotIndex >= vtable.getSlotCount()) {
			return null;
		}
		String name = vtable.getSlot(slotIndex).getName();
		if (name == null || name.isBlank()) {
			return null;
		}
		return name;
	}

	private static String renderArguments(List<String> argumentExprs) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < argumentExprs.size(); i++) {
			String arg = argumentExprs.get(i);
			if (arg == null) {
				throw new IllegalArgumentException("argument expression must not be null");
			}
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(arg);
		}
		return sb.toString();
	}
}
