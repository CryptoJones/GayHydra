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

import ghidra.program.model.address.Address;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;

/**
 * The recognition half of Rec 37 {@code #37-9f-b}: a stateless p-code matcher that recovers the
 * structural facts a candidate C++ {@code delete} site carries &mdash; the direct call's
 * <em>target address</em> and the <em>receiver</em> {@link Varnode} (the pointer handed to the
 * deallocation function) &mdash; from a direct call ({@link PcodeOp#CALL}) in a decompiled
 * {@code HighFunction}. The facts the {@link CppDecompilerHints#renderDelete} renderer (DD-0022)
 * needs come from here; deciding whether the call <em>is</em> a {@code delete} is the driver's job.
 *
 * <p>This is the first slice of the {@code #37-9f-b} Program-coupled wrapper the renderer's javadoc
 * deferred, split on the same line as the {@code #37-7b} virtual-call pair (DD-0024 / DD-0025): this
 * class reads only the SSA graph and recovers structural facts; the {@code #37-9f-b-2}
 * {@link CppDeleteDriver} slice holds the {@link ghidra.program.model.listing.Program}, resolves the
 * recovered {@link DeleteCall#callTarget()} to a function, classifies its name as scalar
 * {@code operator delete} or array {@code operator delete[]} (or neither), and dispatches to the
 * renderer. The scalar-vs-array distinction is <em>not</em> in the p-code &mdash; it lives entirely
 * in the callee's name &mdash; so the matcher cannot and does not decide it.
 *
 * <p><b>The idiom.</b> The matcher is grounded in the p-code the real decompiler emits for an
 * x86-64 {@code delete p} (observed via the Rec 30 headless harness, DD-0023):
 *
 * <pre>
 *   CALL operatorDelete, arg0                 // the direct call to the deallocation function
 *     operatorDelete = (ram) call-target addr // input[0]: the callee entry address
 *     arg0           = CAST(receiver)         // input[1]: the deleted pointer, often a void* CAST
 *       receiver     = p                      // the object pointer being freed
 * </pre>
 *
 * The detail a guess would get wrong, and that the matcher therefore encodes: the pointer argument
 * the renderer wants to print reaches the call through an interposed {@link PcodeOp#CAST} to
 * {@code void *} (the deallocation function's parameter type), so {@link #stripCopyCast} skips the
 * {@code CAST}/{@code COPY} pass-through to reach the underlying receiver varnode whose
 * {@code HighVariable} carries the printable name. The call target is the {@code input[0]} address
 * varnode; a direct {@code CALL} encodes the callee entry there.
 *
 * <p><b>Advisory and total-failure-safe.</b> Like the renderer it feeds, recognition is advisory: a
 * shape that does not match &mdash; not a {@code CALL}, an argument-less call, a call with no
 * resolvable target address or no receiver varnode &mdash; yields {@code null}, never an exception
 * or a fabricated site. The matcher reads only the SSA graph; it holds no
 * {@link ghidra.program.model.listing.Program} and mutates nothing.
 */
public final class CppDeleteRecognizer {

	/**
	 * The structural facts a candidate deallocation {@link PcodeOp#CALL} denotes: the
	 * {@code callTarget} entry address of the called function and the {@link Varnode} carrying the
	 * {@code receiver} pointer handed to it (the candidate being-deleted object).
	 *
	 * @param callTarget the entry address of the called function; never null
	 * @param receiver the varnode carrying the receiver pointer argument; never null
	 */
	public record DeleteCall(Address callTarget, Varnode receiver) {}

	private CppDeleteRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recovers the structural facts of a candidate deallocation call at the given call site.
	 *
	 * @param callSite the candidate call op; may be any op (only a direct {@link PcodeOp#CALL} with at
	 *            least one argument can match) and may be null
	 * @return the recovered {@link DeleteCall}, or null if {@code callSite} is not a direct call
	 *         carrying a resolvable target address and a receiver argument
	 */
	public static DeleteCall recognize(PcodeOp callSite) {
		if (callSite == null || callSite.getOpcode() != PcodeOp.CALL) {
			return null;
		}
		// a delete passes the pointer to free, so the call must carry at least one argument
		if (callSite.getNumInputs() < 2) {
			return null;
		}
		Varnode target = callSite.getInput(0);
		if (target == null) {
			return null;
		}
		Address callTarget = target.getAddress();
		if (callTarget == null || !callTarget.isMemoryAddress()) {
			return null;
		}
		Varnode receiver = stripCopyCast(callSite.getInput(1));
		if (receiver == null) {
			return null;
		}
		return new DeleteCall(callTarget, receiver);
	}

	/**
	 * {@return the varnode reached by skipping back over any chain of {@link PcodeOp#CAST} /
	 * {@link PcodeOp#COPY} pass-through ops, so the caller inspects the underlying receiver; null in,
	 * null out}
	 */
	private static Varnode stripCopyCast(Varnode vn) {
		while (vn != null) {
			PcodeOp def = vn.getDef();
			if (def == null) {
				break;
			}
			int opcode = def.getOpcode();
			if (opcode != PcodeOp.CAST && opcode != PcodeOp.COPY) {
				break;
			}
			vn = def.getInput(0);
		}
		return vn;
	}
}
