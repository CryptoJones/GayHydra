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

import ghidra.program.model.data.Pointer;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;

/**
 * The recognition half of Rec 37 {@code #37-8b}: a stateless p-code matcher that decides whether a
 * {@link PcodeOp#CAST} in a decompiled {@code HighFunction} is a C++ <em>base-subobject pointer
 * adjustment</em> &mdash; the structural up/down-cast between a derived class and one of its base
 * classes &mdash; and if so recovers the {@code (sourcePointer, byteOffset, castResult)} facts the
 * {@link CppDecompilerHints#renderUpcast}/{@link CppDecompilerHints#renderDowncast} renderers
 * (DD-0016) need.
 *
 * <p>This is the matcher slice of the {@code #37-8b} Program-coupled wrapper the cast renderers'
 * javadoc deferred. Like the array-{@code new} matcher (DD-0033) it reads only the SSA graph: it
 * recovers the source pointer {@link Varnode}, the signed byte offset of the adjustment, and the
 * {@code CAST} result {@link Varnode} (which carries the target pointer type). Resolving either end
 * to a {@link CppClass}, classifying the cast direction against the modelled base-class edges, and
 * rendering the source expression is the separate {@code #37-8b-2} driver slice.
 *
 * <p><b>The idiom &mdash; two shapes, grounded.</b> A static up/down-cast at a non-zero base offset
 * is a constant pointer adjustment whose result is reinterpreted to a different class pointer. The
 * real x86-64 decompiler p-code (observed via the Rec 30 headless harness, DD-0023) takes one of two
 * forms depending on the sign of the adjustment:
 *
 * <pre>
 *   // upcast  Derived* -> Base*  ( + offset, into the derived layout ):
 *   //   return (Base *)&amp;d-&gt;field_0x10;
 *   CAST   out = Base*           in[0] = PTRSUB out
 *     PTRSUB out = undefined1*   in[0] = d (Derived*)   in[1] = const 0x10
 *
 *   // downcast  Base* -> Derived*  ( - offset, before the base subobject ):
 *   //   return (Derived *)(b + -2L);
 *   CAST   out = Derived*        in[0] = PTRADD out
 *     PTRADD out = Base*         in[0] = b (Base*)   in[1] = const -2   in[2] = const 8 (scale)
 * </pre>
 *
 * The compiler-positive in-layout offset is a {@link PcodeOp#PTRSUB} (address of a subcomponent),
 * whose {@code in[1]} <em>is</em> the byte offset; the negative before-the-object offset is a
 * {@link PcodeOp#PTRADD} (scaled pointer arithmetic), whose byte offset is {@code in[1] * in[2]}
 * (index times element size, and signed &mdash; the index is {@code -2} here). The matcher normalises
 * both to a single signed byte offset: <b>its sign is the cast direction</b> (positive = upcast into
 * a base subobject, negative = downcast back to the enclosing derived object) and its magnitude is
 * the base-subobject offset the renderers match against a base-class edge.
 *
 * <p><b>Advisory and total-failure-safe.</b> Like the renderers it feeds, recognition is advisory: a
 * {@code CAST} that is not over a constant {@code PTRSUB}/{@code PTRADD} adjustment, whose source or
 * result is not pointer-typed, or whose offset is zero (a first-base reinterpretation leaves no
 * recoverable adjustment) yields {@code null}, never an exception or a fabricated cast. The matcher
 * reads only the SSA graph; it holds no {@link ghidra.program.model.listing.Program} and mutates
 * nothing.
 */
public final class CppBaseCastRecognizer {

	/**
	 * The structural facts a base-subobject cast {@link PcodeOp#CAST} denotes: the {@code sourcePointer}
	 * varnode the adjustment starts from, the signed {@code byteOffset} of the adjustment (positive for
	 * an upcast into a base subobject, negative for a downcast back to the derived object), and the
	 * {@code castResult} varnode carrying the target pointer type.
	 *
	 * @param sourcePointer the varnode carrying the source class pointer; never null
	 * @param byteOffset the signed byte offset of the adjustment; never zero
	 * @param castResult the {@code CAST} output varnode carrying the target class pointer; never null
	 */
	public record BaseCast(Varnode sourcePointer, long byteOffset, Varnode castResult) {}

	private CppBaseCastRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recognises a base-subobject pointer-adjustment cast at the given op.
	 *
	 * @param castOp the candidate op; may be any op (only a {@link PcodeOp#CAST} over a constant
	 *            {@link PcodeOp#PTRSUB}/{@link PcodeOp#PTRADD} can match) and may be null
	 * @return the recovered {@link BaseCast}, or null if {@code castOp} is not a base-cast in the
	 *         recognised idiom
	 */
	public static BaseCast recognize(PcodeOp castOp) {
		if (castOp == null || castOp.getOpcode() != PcodeOp.CAST) {
			return null;
		}
		Varnode castResult = castOp.getOutput();
		if (castResult == null || !isPointerTyped(castResult)) {
			return null;
		}
		PcodeOp adjust = defThroughCopy(castOp.getInput(0));
		if (adjust == null) {
			return null;
		}
		long byteOffset;
		Varnode sourcePointer;
		if (adjust.getOpcode() == PcodeOp.PTRSUB) {
			Varnode offset = adjust.getInput(1);
			if (offset == null || !offset.isConstant()) {
				return null;
			}
			byteOffset = offset.getOffset();
			sourcePointer = adjust.getInput(0);
		}
		else if (adjust.getOpcode() == PcodeOp.PTRADD) {
			Varnode index = adjust.getInput(1);
			Varnode scale = adjust.getInput(2);
			if (index == null || !index.isConstant() || scale == null || !scale.isConstant()) {
				return null;
			}
			byteOffset = index.getOffset() * scale.getOffset();
			sourcePointer = adjust.getInput(0);
		}
		else {
			return null;
		}
		if (byteOffset == 0 || sourcePointer == null || !isPointerTyped(sourcePointer)) {
			return null;
		}
		return new BaseCast(sourcePointer, byteOffset, castResult);
	}

	/**
	 * {@return the defining op of the varnode reached by skipping back over any chain of
	 * {@link PcodeOp#COPY} pass-through ops &mdash; not {@code CAST}, since the {@code CAST} is the
	 * anchor we came from &mdash; or null if there is none}
	 */
	private static PcodeOp defThroughCopy(Varnode vn) {
		while (vn != null) {
			PcodeOp def = vn.getDef();
			if (def == null) {
				return null;
			}
			if (def.getOpcode() != PcodeOp.COPY) {
				return def;
			}
			vn = def.getInput(0);
		}
		return null;
	}

	/**
	 * {@return whether the varnode carries a {@link Pointer}-typed {@link HighVariable} &mdash; the
	 * class-pointer requirement that separates a base-subobject cast from scalar pointer arithmetic}
	 */
	private static boolean isPointerTyped(Varnode vn) {
		HighVariable high = vn.getHigh();
		return high != null && high.getDataType() instanceof Pointer;
	}
}
