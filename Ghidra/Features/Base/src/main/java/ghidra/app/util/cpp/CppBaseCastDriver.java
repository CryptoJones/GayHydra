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
import java.util.Iterator;
import java.util.List;

import ghidra.app.util.cpp.CppBaseCastRecognizer.BaseCast;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;

/**
 * The driver half of Rec 37 {@code #37-8b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppBaseCastRecognizer} matcher (DD-0035) to find candidate base-subobject cast {@code CAST}s,
 * reads the source and target classes off the recovered varnodes' pointer types, classifies the cast
 * direction from the recovered offset's sign, resolves the derived class in a supplied
 * {@link CppTypeSystem}, and dispatches to the stateless
 * {@link CppDecompilerHints#renderUpcast}/{@link CppDecompilerHints#renderDowncast} renderers
 * (DD-0016) to produce the C++ hint string. This is the {@code #37-8b-2} slice that closes the loop
 * the recognizer opened.
 *
 * <p><b>Direction from the offset sign; the derived class is the one carrying the base edge.</b> The
 * matcher recovers a single signed byte offset: positive means the pointer was adjusted <em>into</em>
 * a base subobject (an upcast, source = derived), negative means it was adjusted back <em>out</em> to
 * the enclosing object (a downcast, target = derived). The driver reads the pointed-to class name off
 * each end's pointer type, picks the derived class accordingly, and renders the source pointer
 * expression from its {@link HighVariable} name &mdash; the same operand-rendering the delete and
 * destructor drivers use.
 *
 * <p><b>Why the driver re-checks the base edge.</b> The renderers are defensively stateless: asked to
 * render a cast at an offset where the derived class has no non-virtual base edge, they fall back to a
 * neutral {@code src + offset} / {@code src - offset} adjustment rather than fabricating a
 * {@code static_cast}. That fallback is faithful but adds no value over what the decompiler already
 * prints, so it would be noise as a <em>hint</em>. The driver therefore makes the emit decision the
 * renderer cannot: it dispatches only when the resolved derived class genuinely has a non-virtual base
 * edge at the recovered offset, declining otherwise. (The renderer keeps its own check; the two
 * concerns differ &mdash; the renderer answers &ldquo;given I am asked, what string?&rdquo;, the
 * driver answers &ldquo;should a hint be emitted at all?&rdquo;.)
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderers it sits between, the driver is
 * additive and total-failure-safe: a cast whose source or result is not a pointer to a modelled
 * class, whose derived class is unmodelled, whose offset matches no non-virtual base edge, or whose
 * source pointer has no printable name is silently skipped (it contributes no hint), never
 * mis-rendered or raised as an error. A function with no recognised base cast yields an empty list.
 *
 * <p><b>Scope: non-virtual single base offsets.</b> This slice renders the static up/down-cast a
 * non-zero constant base-subobject offset denotes. A {@code virtual} base's offset is dynamic (not the
 * compile-time constant a {@code static_cast} represents), so a virtual-base edge at the offset is not
 * a match &mdash; mirrored in the renderer's own edge check.
 */
public final class CppBaseCastDriver {

	/**
	 * A rendered base-cast hint: the {@code site} address of the {@code CAST} it was recovered from, and
	 * the {@code rendering} string the {@link CppDecompilerHints} renderer produced.
	 *
	 * @param site the address of the dispatching cast op
	 * @param rendering the rendered C++ {@code static_cast} expression
	 */
	public record RenderedCast(Address site, String rendering) {}

	private final CppDecompilerHints renderer;
	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a driver over the given renderer and type-system model.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @param typeSystem the model resolving classes to {@link CppClass}es; must not be null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public CppBaseCastDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
		if (renderer == null) {
			throw new IllegalArgumentException("renderer must not be null");
		}
		if (typeSystem == null) {
			throw new IllegalArgumentException("type system must not be null");
		}
		this.renderer = renderer;
		this.typeSystem = typeSystem;
	}

	/**
	 * Recognises every base-subobject cast in the function and renders a hint for each one whose source
	 * and result point at modelled classes and whose derived class has a non-virtual base edge at the
	 * recovered offset.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedCast> recognizeAndRender(HighFunction function) {
		if (function == null) {
			throw new IllegalArgumentException("high function must not be null");
		}
		List<RenderedCast> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CAST) {
				continue;
			}
			BaseCast cast = CppBaseCastRecognizer.recognize(op);
			if (cast == null) {
				continue;
			}
			RenderedCast hint = render(op, cast);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedCast render(PcodeOp castSite, BaseCast cast) {
		DataType sourceType = pointedType(cast.sourcePointer());
		DataType targetType = pointedType(cast.castResult());
		if (sourceType == null || targetType == null) {
			return null;
		}
		String sourceExpr = sourceName(cast.sourcePointer());
		if (sourceExpr == null) {
			return null;
		}
		boolean upcast = cast.byteOffset() > 0;
		int magnitude = (int) Math.abs(cast.byteOffset());
		// The derived class is the source for an upcast (adjust into the base subobject) and the target
		// for a downcast (adjust back out to the enclosing object).
		CppClass derived =
			typeSystem.getCppClass((upcast ? sourceType : targetType).getName());
		if (derived == null || !hasNonVirtualBaseEdgeAt(derived, magnitude)) {
			return null;
		}
		String rendering = upcast
				? renderer.renderUpcast(derived, magnitude, sourceExpr)
				: renderer.renderDowncast(derived, magnitude, sourceExpr);
		return new RenderedCast(castSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * {@return the class type the pointer varnode points at &mdash; one pointer level stripped off its
	 * {@link HighVariable}'s pointer data type &mdash; or null if it carries no {@link HighVariable} or
	 * a non-pointer type}
	 */
	private static DataType pointedType(Varnode pointerVarnode) {
		HighVariable high = pointerVarnode.getHigh();
		if (high == null) {
			return null;
		}
		DataType dataType = high.getDataType();
		if (!(dataType instanceof Pointer)) {
			return null;
		}
		return ((Pointer) dataType).getDataType();
	}

	/**
	 * {@return the printable name of the source pointer's {@link HighVariable} (e.g. {@code param_1}),
	 * or null if it has none or it is blank}
	 */
	private static String sourceName(Varnode pointerVarnode) {
		HighVariable high = pointerVarnode.getHigh();
		if (high == null) {
			return null;
		}
		String name = high.getName();
		return (name == null || name.isBlank()) ? null : name;
	}

	/**
	 * {@return whether {@code derived} has a non-virtual base-class edge at {@code offset} &mdash; the
	 * driver's emit gate, matching the renderer's own static-cast eligibility (a {@code virtual} base's
	 * dynamic offset is not a constant cast)}
	 */
	private static boolean hasNonVirtualBaseEdgeAt(CppClass derived, int offset) {
		for (CppBaseClass edge : derived.getBaseClasses()) {
			if (!edge.isVirtual() && edge.getOffset() == offset) {
				return true;
			}
		}
		return false;
	}
}
