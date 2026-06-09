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

import ghidra.app.util.cpp.CppDirectCallRecognizer.DirectCall;
import ghidra.program.model.address.Address;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;

/**
 * The recognition half of Rec 37 {@code #37-9e-b}: a stateless p-code matcher that recovers the
 * structural facts a <em>placement</em> {@code new (buf) C(...)} construction carries &mdash; the
 * constructor call's <em>target address</em>, the placement allocation call's <em>target address</em>,
 * and the <em>buffer varnode</em> the allocation was handed &mdash; from the fused p-code shape a
 * non-elided placement {@code new} compiles to. The fact the
 * {@link CppDecompilerHints#renderPlacementConstruction} renderer (DD-0016) needs (the constructed
 * class) follows from the constructor target; deciding whether the two calls really <em>are</em> a
 * constructor and a placement {@code operator new}, and rendering the buffer expression, is the
 * {@code #37-9e-b-2} {@link CppPlacementConstructionDriver}'s job.
 *
 * <p><b>Why placement is the non-elided two-call form.</b> The <em>standard</em> placement
 * {@code new (buf) C()} elides {@code operator new(size_t, void*)} entirely &mdash; that overload just
 * returns its buffer argument, so the compiler drops the call and emits a bare constructor on
 * caller-owned storage. That bare form is structurally indistinguishable from an ordinary in-place /
 * stack construction, which the {@code #37-9b} heap matcher deliberately declines (its receiver is not
 * a call result). The recoverable placement shape is therefore the <em>non-elided</em> two-call form:
 * a real placement {@code operator new} taking {@code (size, buffer)} whose result feeds the
 * constructor receiver. Grounded in the p-code the real decompiler emits for an x86-64
 * {@code C* makeAt(void* buf) { return new (buf) C(); }} (observed via the Rec 30 headless harness,
 * DD-0023):
 *
 * <pre>
 *   uniq = CALL operatorNew, size, buffer  // placement alloc: operator new(size, buf) -&gt; buf
 *   rax  = CAST uniq                        // CAST raw void* -&gt; C*
 *   CALL ctor, rax                          // the constructor: C::C(this = the placement storage)
 * </pre>
 *
 * <p><b>What distinguishes placement from heap {@code new}.</b> Both are the same fusion shape &mdash;
 * a constructor whose receiver is the result of an allocation {@code CALL}. The one structural
 * difference is the allocation's <em>operand count</em>: a heap {@code operator new(size_t)} is called
 * with the size alone ({@code CALL} target + size = two inputs), whereas a placement
 * {@code operator new(size_t, void*)} is additionally handed the buffer ({@code CALL} target + size +
 * buffer = three inputs). This matcher requires that buffer operand and recovers it as the allocation
 * {@code CALL}'s {@code input[2]}; the {@code #37-9b} heap matcher (DD-0030) was tightened in lock-step
 * to <em>decline</em> an allocation carrying a buffer, so the two forms partition the fusion shape and
 * never both match a site. (The two callees share the demangled name {@code operator new}; the operand
 * count, not the name, is what separates the forms.)
 *
 * <p><b>The direct-call recovery is shared.</b> Recovering the constructor call's {@code (target,
 * receiver)} via {@link CppDirectCallRecognizer} and walking back from the receiver to the allocation
 * {@code CALL} is the same fusion logic the {@code #37-9b} heap matcher uses; this matcher contributes
 * only the placement-specific buffer recovery and the buffer-operand gate.
 *
 * <p><b>Advisory and total-failure-safe.</b> Like the renderer it feeds, recognition is advisory: a
 * shape that does not match &mdash; not a {@code CALL}, an argument-less call, a receiver that is not
 * itself a call result, an allocation with no buffer operand or no resolvable target &mdash; yields
 * {@code null}, never an exception or a fabricated site. The matcher reads only the SSA graph; it holds
 * no {@link ghidra.program.model.listing.Program} and mutates nothing.
 */
public final class CppPlacementConstructionRecognizer {

	/**
	 * The structural facts a candidate placement-{@code new} construction denotes: the
	 * {@code constructorTarget} entry address of the called constructor, the {@code allocationTarget}
	 * entry address of the placement allocation call ({@code operator new(size, buffer)}) whose result
	 * the constructor runs on, and the {@code placementBuffer} varnode that allocation was handed.
	 *
	 * @param constructorTarget the entry address of the called constructor; never null
	 * @param allocationTarget the entry address of the placement allocation call; never null
	 * @param placementBuffer the varnode carrying the buffer the allocation was handed; never null
	 */
	public record PlacementConstruction(Address constructorTarget, Address allocationTarget,
			Varnode placementBuffer) {}

	private CppPlacementConstructionRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recovers the structural facts of a candidate placement-{@code new} construction at the given
	 * constructor call site.
	 *
	 * @param callSite the candidate constructor call op; may be any op (only a direct
	 *            {@link PcodeOp#CALL} whose receiver is the result of an allocation call carrying a
	 *            buffer operand can match) and may be null
	 * @return the recovered {@link PlacementConstruction}, or null if {@code callSite} is not a
	 *         constructor call whose cast-stripped receiver is the result of a resolvable placement
	 *         allocation call
	 */
	public static PlacementConstruction recognize(PcodeOp callSite) {
		DirectCall constructor = CppDirectCallRecognizer.recognize(callSite);
		if (constructor == null) {
			return null;
		}
		// the fusion link: the constructor runs on the result of the allocation call.
		PcodeOp allocation = constructor.receiver().getDef();
		if (allocation == null || allocation.getOpcode() != PcodeOp.CALL) {
			return null;
		}
		// placement, not heap: the allocation is handed a buffer beyond the size operand.
		if (allocation.getNumInputs() < 3) {
			return null;
		}
		Varnode placementBuffer = allocation.getInput(2);
		if (placementBuffer == null) {
			return null;
		}
		Address allocationTarget = CppDirectCallRecognizer.callTargetAddress(allocation);
		if (allocationTarget == null) {
			return null;
		}
		return new PlacementConstruction(constructor.callTarget(), allocationTarget, placementBuffer);
	}
}
