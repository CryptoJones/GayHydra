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
import java.util.Set;

/**
 * The C++ decompiler-hint renderer (RFC-0001 §5): a stateless producer of C++-style rendering
 * strings from <em>already-resolved</em> {@link CppTypeSystem} model facts plus the operand
 * expressions supplied by its caller.
 *
 * <p>Rec 37 {@code #37-7} (virtual-method-call form, DD-0016), {@code #37-8} (up/down-cast form,
 * DD-0017), {@code #37-9} (heap-construction form, DD-0018), {@code #37-9c} (explicit
 * destructor-call form, DD-0019), {@code #37-9d} (array-construction form, DD-0020),
 * {@code #37-9e} (placement-construction form, DD-0021), and {@code #37-9f} (deallocation form,
 * DD-0022) — the seventh and final headless renderer form, after which the family is exhausted.
 * This is the headless half of RFC §5. It holds no
 * {@link ghidra.program.model.listing.Program}, no
 * {@link ghidra.program.model.data.DataTypeManager}, and no decompiler / {@code HighFunction}
 * reference; it never scans, demangles, parses, or mutates the model. Its inputs are model objects
 * (a {@link CppClass}, a vtable slot index, an inheritance offset) plus operand expressions — the
 * receiver, arguments, or source pointer — handed in as opaque strings by whatever drives it. Its
 * output is the rendered string. The decompiler-side pattern-recognition pass that walks a
 * {@code HighFunction}, recognises the raw C-style idiom, recovers the {@code (class, slot)} or
 * {@code (derived, offset, direction)} it denotes, and calls this renderer is the deferred
 * {@code #37-7b}/{@code #37-8b}/{@code #37-9b} Program-coupled wrapper, not part of this slice.
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
		String infix = infixOperatorRendering(slotName, receiverExpr, receiverIsPointer,
			argumentExprs);
		if (infix != null) {
			return infix;
		}
		return receiverExpr + access + slotName + "(" + args + ")";
	}

	/**
	 * The overloaded member operators whose call renders source-faithfully as a binary infix
	 * expression when (and only when) the call carries exactly one explicit argument &mdash; the
	 * arity that makes a member {@code operator-} subtraction rather than negation and a member
	 * {@code operator*} multiplication rather than dereference. {@code ++}/{@code --} (whose postfix
	 * forms carry a dummy {@code int} argument), {@code []}, {@code ()}, {@code ->}, and the
	 * assignment family are deliberately absent: their faithful forms are not plain binary infix, so
	 * they keep the explicit {@code receiver->operatorX(args)} rendering, which is itself valid C++.
	 */
	private static final Set<String> INFIX_OPERATOR_NAMES = Set.of("operator+", "operator-",
		"operator*", "operator/", "operator%", "operator==", "operator!=", "operator<",
		"operator<=", "operator>", "operator>=", "operator&", "operator|", "operator^",
		"operator<<", "operator>>");

	/**
	 * {@return the infix rendering of an overloaded-operator call (e.g. {@code (*p) + x},
	 * {@code s == x}), or null when the slot is not an infix-renderable operator with exactly one
	 * explicit argument &mdash; the caller falls back to the explicit call form}
	 *
	 * <p>A pointer receiver is dereferenced and parenthesised unconditionally ({@code (*p) + x})
	 * &mdash; exact by construction, no precedence table to get wrong (the {@code #37-10r}
	 * philosophy); a value receiver renders bare ({@code s + x}).
	 */
	private static String infixOperatorRendering(String slotName, String receiverExpr,
			boolean receiverIsPointer, List<String> argumentExprs) {
		if (argumentExprs.size() != 1 || !INFIX_OPERATOR_NAMES.contains(slotName)) {
			return null;
		}
		String glyph = slotName.substring("operator".length());
		String receiver = receiverIsPointer ? "(*" + receiverExpr + ")" : receiverExpr;
		return receiver + " " + glyph + " " + argumentExprs.get(0);
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

	/**
	 * Renders a heap construction — a {@code malloc(...) + Ctor(...)} idiom fused into the C++
	 * {@code new} expression — as {@code new ClassName(args)}, where {@code ClassName} is
	 * {@code type.getName()} and {@code args} are the constructor argument expressions joined in call
	 * order. A zero-argument construction renders {@code new ClassName()}; the parentheses are always
	 * emitted so the rendering is unambiguously a construction.
	 *
	 * <p>Unlike {@link #renderVirtualCall} and the cast renderers, this form has <em>no neutral
	 * fallback</em>: the only model fact it needs is the class name, which is total ({@code getName()}
	 * never fails for a defined class), so there is nothing to fall back from. It does no
	 * constructor-overload resolution — selecting among overloaded constructors is a
	 * signature/{@code DataType} concern (the DTM-coupled {@code #37-10+} work), not a rendering one —
	 * and simply formats the argument expressions the recognition pass supplies.
	 *
	 * @param type the class being constructed; must not be null
	 * @param argumentExprs the already-rendered constructor argument expressions in call order; must
	 *            not be null and must contain no null element (may be empty for a default construction)
	 * @return the rendered C++ {@code new} expression
	 * @throws IllegalArgumentException if {@code type} is null, or {@code argumentExprs} is null or
	 *             contains a null element
	 */
	public String renderConstruction(CppClass type, List<String> argumentExprs) {
		if (type == null) {
			throw new IllegalArgumentException("constructed type must not be null");
		}
		if (argumentExprs == null) {
			throw new IllegalArgumentException("argument expression list must not be null");
		}
		return "new " + type.getName() + "(" + renderArguments(argumentExprs) + ")";
	}

	/**
	 * Renders an <em>explicit</em> (non-virtual) destructor call — the in-place-destruction idiom a
	 * {@code Foo::~Foo(ptr)} direct call denotes (placement-new teardown, a member or stack object's
	 * destructor) — as {@code receiver->~ClassName()} for a pointer receiver or
	 * {@code receiver.~ClassName()} for a value receiver, where {@code ClassName} is
	 * {@code type.getName()}. The parentheses are always empty: a C++ destructor takes no explicit
	 * arguments, so this form has no argument-list parameter.
	 *
	 * <p>The destructor name comes from the <em>class</em> ({@code ~} prepended to {@code getName()}),
	 * not a vtable slot: a destructor that dispatches through the vtable (a virtual destructor) is
	 * already the {@link #renderVirtualCall} form, driven by a name-resolved {@code ~ClassName} slot.
	 * This form does not consult the vtable.
	 *
	 * <p>Like {@link #renderConstruction}, it has <em>no neutral fallback</em>: the only model fact it
	 * needs is the class name, which is total ({@code getName()} never fails for a defined class), so
	 * there is nothing to fall back from. It validates only its boundary inputs.
	 *
	 * @param type the class being destructed; must not be null
	 * @param receiverExpr the already-rendered receiver expression; must not be null or blank
	 * @param receiverIsPointer true to render pointer access ({@code ->}), false for value access
	 *            ({@code .})
	 * @return the rendered C++ destructor-call expression
	 * @throws IllegalArgumentException if {@code type} is null or {@code receiverExpr} is null or blank
	 */
	public String renderDestructorCall(CppClass type, String receiverExpr,
			boolean receiverIsPointer) {
		if (type == null) {
			throw new IllegalArgumentException("destructed type must not be null");
		}
		if (receiverExpr == null || receiverExpr.isBlank()) {
			throw new IllegalArgumentException("receiver expression must not be null or blank");
		}
		String access = receiverIsPointer ? "->" : ".";
		return receiverExpr + access + "~" + type.getName() + "()";
	}

	/**
	 * Renders an array heap construction — an {@code operator new[]} allocation followed by a
	 * per-element default-constructor loop, fused into the C++ array-{@code new} expression — as
	 * {@code new ClassName[count]}, where {@code ClassName} is {@code type.getName()} and
	 * {@code count} is the element-count expression supplied verbatim inside the brackets.
	 *
	 * <p>Unlike {@link #renderConstruction}, this form takes <em>no constructor argument list</em>:
	 * array {@code new T[n]} value/default-initializes each element, and C++ has no syntax to thread
	 * per-element constructor arguments through it — so there is no argument list to format (the same
	 * reason {@link #renderDestructorCall} takes none).
	 *
	 * <p>Like {@link #renderConstruction} and {@link #renderDestructorCall}, it has <em>no neutral
	 * fallback</em>: the only model fact it needs is the class name, which is total ({@code getName()}
	 * never fails for a defined class), so there is nothing to fall back from. It does no
	 * constructor-overload resolution (the element constructor is the implicit default) and no vtable
	 * lookup; it validates only its boundary inputs.
	 *
	 * @param type the class whose array is being constructed; must not be null
	 * @param countExpr the already-rendered element-count expression; must not be null or blank
	 * @return the rendered C++ array-{@code new} expression
	 * @throws IllegalArgumentException if {@code type} is null or {@code countExpr} is null or blank
	 */
	public String renderArrayConstruction(CppClass type, String countExpr) {
		if (type == null) {
			throw new IllegalArgumentException("constructed type must not be null");
		}
		if (countExpr == null || countExpr.isBlank()) {
			throw new IllegalArgumentException("count expression must not be null or blank");
		}
		return "new " + type.getName() + "[" + countExpr + "]";
	}

	/**
	 * Renders a placement construction — a constructor call into an already-owned buffer, fused into
	 * the C++ placement-{@code new} expression — as {@code new (ptr) ClassName(args)}, where
	 * {@code ptr} is the placement-target expression, {@code ClassName} is {@code type.getName()}, and
	 * {@code args} are the constructor argument expressions joined in call order. A zero-argument
	 * placement construction renders {@code new (ptr) ClassName()}; the constructor parentheses are
	 * always emitted, as in {@link #renderConstruction}.
	 *
	 * <p>The bracketed placement target before the class name is exactly what distinguishes
	 * placement-{@code new} from the ordinary {@link #renderConstruction} {@code new}: it is a
	 * distinct expression shape, not a scalar {@code new} with a folded-in argument.
	 *
	 * <p>Like {@link #renderConstruction} and {@link #renderArrayConstruction}, this form has <em>no
	 * neutral fallback</em>: the only model fact it needs is the class name, which is total
	 * ({@code getName()} never fails for a defined class), so there is nothing to fall back from. It
	 * does no constructor-overload resolution (a signature/{@code DataType} concern, the
	 * {@code #37-10+} work) and no vtable lookup; it validates only its boundary inputs and formats
	 * the placement and argument expressions the recognition pass supplies.
	 *
	 * @param type the class being constructed; must not be null
	 * @param placementExpr the already-rendered placement-target expression; must not be null or blank
	 * @param argumentExprs the already-rendered constructor argument expressions in call order; must
	 *            not be null and must contain no null element (may be empty for a default construction)
	 * @return the rendered C++ placement-{@code new} expression
	 * @throws IllegalArgumentException if {@code type} is null, {@code placementExpr} is null or blank,
	 *             or {@code argumentExprs} is null or contains a null element
	 */
	public String renderPlacementConstruction(CppClass type, String placementExpr,
			List<String> argumentExprs) {
		if (type == null) {
			throw new IllegalArgumentException("constructed type must not be null");
		}
		if (placementExpr == null || placementExpr.isBlank()) {
			throw new IllegalArgumentException("placement expression must not be null or blank");
		}
		if (argumentExprs == null) {
			throw new IllegalArgumentException("argument expression list must not be null");
		}
		return "new (" + placementExpr + ") " + type.getName() + "("
			+ renderArguments(argumentExprs) + ")";
	}

	/**
	 * Renders a heap deallocation — the destructor-then-{@code operator delete} idiom fused into the
	 * C++ {@code delete} expression — as {@code delete receiver} for a scalar pointer or
	 * {@code delete[] receiver} for an array pointer, where {@code receiver} is the operand expression
	 * supplied verbatim. {@code delete} is a unary operator on the pointer: there is no class name, no
	 * parentheses, and no argument list, and the {@code [}{@code ]} is the only thing {@code isArray}
	 * adds.
	 *
	 * <p>This is the seventh and final headless form, and the only one that reads <em>no</em>
	 * {@link CppTypeSystem} model fact at all: in C++ {@code delete e} / {@code delete[] e} names no
	 * type — the operand is a pointer and the type to destroy is inferred from it — so the renderer
	 * takes <em>no {@link CppClass}</em> and consults no vtable. The destructor {@code delete} invokes
	 * before freeing is a recognition concern (the pass pairs the dtor call with the
	 * {@code operator delete}); the explicit-destructor <em>rendering</em> is the separate
	 * {@link #renderDestructorCall} form.
	 *
	 * <p>Having no model fact to read, it has even less to fall back from than the class-name-only
	 * forms — so, like {@link #renderConstruction} and its siblings, it has <em>no neutral
	 * fallback</em>: it validates only its single boundary input, the receiver expression, and rejects
	 * a null or blank one.
	 *
	 * @param receiverExpr the already-rendered pointer expression being deleted; must not be null or
	 *            blank
	 * @param isArray true to render the array form {@code delete[] receiver}, false for the scalar
	 *            form {@code delete receiver}
	 * @return the rendered C++ {@code delete} expression
	 * @throws IllegalArgumentException if {@code receiverExpr} is null or blank
	 */
	public String renderDelete(String receiverExpr, boolean isArray) {
		if (receiverExpr == null || receiverExpr.isBlank()) {
			throw new IllegalArgumentException("receiver expression must not be null or blank");
		}
		return (isArray ? "delete[] " : "delete ") + receiverExpr;
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
