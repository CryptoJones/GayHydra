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

import java.util.Iterator;

import ghidra.program.model.address.Address;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;

/**
 * The recognition half of Rec 37 {@code #37-9d-b}: a stateless p-code matcher that recovers the
 * structural facts a heap array-{@code new} ({@code new C[n]}) carries &mdash; the
 * <em>allocation</em> call's target address, the allocation <em>byte-size</em> argument, and the
 * <em>typed result</em> varnode the raw storage flows into &mdash; from a decompiled
 * {@link ghidra.program.model.pcode.HighFunction}. The facts the
 * {@link CppDecompilerHints#renderArrayConstruction} renderer (DD-0016) needs (the element class and
 * the element count) follow from those: the class from the typed result's pointer type, the count
 * from the byte size divided by the element size. Deciding whether the called function actually
 * <em>is</em> {@code operator new[]} is the driver's job ({@code #37-9d-b-2}).
 *
 * <p><b>Why this is a <em>forward</em> matcher, and how it differs from the direct-call forms.</b>
 * The delete, destructor, and constructor forms recover their facts by walking <em>backward</em> from
 * a call's receiver argument (the shared {@link CppDirectCallRecognizer}). Array-{@code new} is the
 * first form whose defining facts live <em>forward</em> of the call. Grounded in the p-code the real
 * decompiler emits for an x86-64 {@code C* makeArray() { return new C[5]; }} (observed via the Rec 30
 * headless harness, DD-0023): the decompiled C is
 * {@code pCVar1 = (C *)operator_new__(0x28); return pCVar1;}, whose p-code is
 *
 * <pre>
 *   uniq = CALL operator.new[], #0x28     // allocation: raw void* result; 0x28 = 5 * sizeof(C)
 *   rax  = CAST uniq                       // CAST void* -&gt; C*  &mdash; this output carries the C* type
 *   RETURN rax                             // (high = pCVar1 : C *)
 * </pre>
 *
 * The raw allocation result ({@code uniq}) is an untyped {@code void *}; the element type {@code C}
 * appears only <em>downstream</em>, on the {@code CAST} output the storage is reinterpreted into. So
 * the matcher anchors on the allocation {@code CALL}, then walks <em>forward</em> over any
 * {@link PcodeOp#CAST}/{@link PcodeOp#COPY} pass-through chain off the call's result to reach the
 * varnode that carries a pointer-typed {@link HighVariable} &mdash; the {@code typedResult} from which
 * the driver reads the element type and (with the byte-size argument) the element count.
 *
 * <p><b>Why no ctor fusion (the trivial-element shape).</b> For a non-trivial element type
 * {@code new C[n]} additionally emits a per-element default-constructor loop over the allocated
 * storage; this matcher recognises the allocation-and-type shape that a <em>trivial</em>-element
 * array {@code new} reduces to (allocation plus typed use, no ctor loop), the same way the
 * {@link CppDeleteDriver delete} form renders the {@code operator delete} call on its own terms.
 * Fusing the per-element constructor loop (to confirm the element ctor and recover its class from the
 * callee name rather than the result type) is a later cross-form refinement, noted on
 * {@link CppDecompilerHints#renderArrayConstruction}'s own javadoc.
 *
 * <p><b>The structural anchor is name-blind; the driver disambiguates.</b> Like the delete form, a
 * raw {@code operator new[]} call is structurally indistinguishable from {@code operator new} or a
 * bare {@code malloc} &mdash; all are a sized allocation whose result becomes a typed pointer. What
 * makes it an array {@code new} is the callee's <em>name</em> ({@code operator new[]}), which is
 * {@link ghidra.program.model.listing.Program}-coupled and therefore the driver's call. This matcher
 * contributes only the SSA-graph facts: a sized {@code CALL} with a resolvable target whose result
 * flows forward to a pointer-typed varnode.
 *
 * <p><b>Advisory and total-failure-safe.</b> A shape that does not match &mdash; not a {@code CALL},
 * a call with no size argument, no resolvable target, no result, or a result that never reaches a
 * pointer-typed varnode &mdash; yields {@code null}, never an exception. The matcher reads only the
 * SSA graph; it holds no {@link ghidra.program.model.listing.Program} and mutates nothing.
 */
public final class CppArrayConstructionRecognizer {

	/**
	 * The structural facts a candidate array-{@code new} allocation denotes: the
	 * {@code allocationTarget} entry address of the allocation call, the {@code byteSize} varnode
	 * carrying its size argument (the total allocation size in bytes), and the {@code typedResult}
	 * varnode &mdash; the forward-resolved result carrying the element pointer type &mdash; from which
	 * the driver reads the element class and (dividing {@code byteSize} by the element size) the count.
	 *
	 * @param allocationTarget the entry address of the allocation call; never null
	 * @param byteSize the varnode carrying the allocation's byte-size argument; never null
	 * @param typedResult the forward-resolved varnode carrying the element pointer type; never null
	 */
	public record ArrayAllocation(Address allocationTarget, Varnode byteSize, Varnode typedResult) {}

	private CppArrayConstructionRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recovers the structural facts of a candidate array-{@code new} allocation at the given call site.
	 *
	 * @param callSite the candidate allocation call op; may be any op (only a direct
	 *            {@link PcodeOp#CALL} with a size argument whose result flows to a pointer-typed
	 *            varnode can match) and may be null
	 * @return the recovered {@link ArrayAllocation}, or null if {@code callSite} is not a sized
	 *         allocation call whose result reaches a pointer-typed varnode
	 */
	public static ArrayAllocation recognize(PcodeOp callSite) {
		if (callSite == null || callSite.getOpcode() != PcodeOp.CALL) {
			return null;
		}
		// a sized allocation passes at least one explicit argument (the byte size)
		if (callSite.getNumInputs() < 2) {
			return null;
		}
		Address allocationTarget = CppDirectCallRecognizer.callTargetAddress(callSite);
		if (allocationTarget == null) {
			return null;
		}
		Varnode byteSize = callSite.getInput(1);
		if (byteSize == null) {
			return null;
		}
		Varnode result = callSite.getOutput();
		if (result == null) {
			return null;
		}
		// the element type lives forward of the call, on the varnode the raw void* is reinterpreted
		// into; walk forward over the CAST/COPY pass-through chain to reach it.
		Varnode typedResult = forwardThroughCastCopy(result);
		if (typedResult == null) {
			return null;
		}
		HighVariable high = typedResult.getHigh();
		if (high == null || !(high.getDataType() instanceof Pointer)) {
			return null;
		}
		return new ArrayAllocation(allocationTarget, byteSize, typedResult);
	}

	/**
	 * {@return the varnode reached by following the result <em>forward</em> over a chain of single
	 * {@link PcodeOp#CAST} / {@link PcodeOp#COPY} pass-through consumers, so the caller inspects the
	 * downstream typed varnode the raw allocation result is reinterpreted into; the input varnode
	 * itself if it has no such pass-through consumer; null in, null out}
	 */
	private static Varnode forwardThroughCastCopy(Varnode vn) {
		while (vn != null) {
			Varnode next = solePassThroughConsumerOutput(vn);
			if (next == null) {
				break;
			}
			vn = next;
		}
		return vn;
	}

	/**
	 * {@return the output of {@code vn}'s sole consumer when that consumer is a single
	 * {@link PcodeOp#CAST}/{@link PcodeOp#COPY} pass-through, else null &mdash; so a result used in
	 * exactly one pass-through step is followed, but a fork (multiple consumers) or a real use stops
	 * the walk}
	 */
	private static Varnode solePassThroughConsumerOutput(Varnode vn) {
		Iterator<PcodeOp> consumers = vn.getDescendants();
		if (consumers == null || !consumers.hasNext()) {
			return null;
		}
		PcodeOp consumer = consumers.next();
		if (consumers.hasNext()) {
			// forked use: not a single pass-through chain
			return null;
		}
		int opcode = consumer.getOpcode();
		if (opcode != PcodeOp.CAST && opcode != PcodeOp.COPY) {
			return null;
		}
		return consumer.getOutput();
	}
}
