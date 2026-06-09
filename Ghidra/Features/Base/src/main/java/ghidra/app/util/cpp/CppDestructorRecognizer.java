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
 * The recognition half of Rec 37 {@code #37-9c-b}: a stateless p-code matcher that recovers the
 * structural facts an <em>explicit</em> (non-virtual) C++ destructor call carries &mdash; the direct
 * call's <em>target address</em> and the <em>receiver</em> {@link Varnode} (the {@code this} pointer
 * the destructor is invoked on) &mdash; from a direct call ({@link PcodeOp#CALL}) in a decompiled
 * {@code HighFunction}. The facts the {@link CppDecompilerHints#renderDestructorCall} renderer
 * (DD-0016) needs come from here; deciding whether the call <em>is</em> a destructor (and of which
 * class) is the driver's job.
 *
 * <p>This is the matcher slice of the {@code #37-9c-b} Program-coupled wrapper, split on the same
 * line as the {@code #37-7b} virtual-call pair (DD-0024 / DD-0025) and the {@code #37-9f-b} delete
 * pair (DD-0026 / DD-0027): this class reads only the SSA graph and recovers structural facts; the
 * {@code #37-9c-b-2} {@link CppDestructorDriver} slice holds the
 * {@link ghidra.program.model.listing.Program}, resolves the recovered
 * {@link DestructorCall#callTarget()} to a function, classifies its name as {@code ~ClassName},
 * resolves that class in a {@link CppTypeSystem}, and dispatches to the renderer. Which class is being
 * destructed is <em>not</em> in the p-code &mdash; it lives in the callee's name &mdash; so the
 * matcher cannot and does not decide it.
 *
 * <p><b>The idiom.</b> An explicit destructor call {@code p->~C()} compiles to a direct call passing
 * {@code this} as the first (and, for a destructor, only explicit) argument:
 *
 * <pre>
 *   CALL dtor, this                  // the direct call to the destructor
 *     dtor = (ram) call-target addr  // input[0]: the destructor entry address
 *     this = receiver                // input[1]: the object pointer the dtor runs on
 * </pre>
 *
 * <p><b>Shared extraction, kept per-form for now.</b> The structural recovery here &mdash; a direct
 * {@code CALL}'s {@code input[0]} target address plus its {@link #stripCopyCast cast-stripped}
 * {@code input[1]} receiver &mdash; is the same direct-call shape {@link CppDeleteRecognizer}
 * established (DD-0026). This is the <em>second</em> form to use it; per DD-0026's standing note the
 * shared extractor is deliberately <em>not</em> unified yet (the rule-of-three threshold is met at the
 * third form, the constructor {@code #37-9b}), so the two matchers are kept as honest per-form twins
 * until then rather than abstracted prematurely. The {@link #stripCopyCast} pass-through skip is
 * retained even though a {@code this} pointer is usually passed as its own {@code C *} type without
 * the {@code void *} {@code CAST} a {@code delete} interposes: a receiver can still reach the call
 * through a {@code COPY}/{@code CAST} (e.g. a base-subobject adjustment), and skipping it is harmless
 * when absent.
 *
 * <p><b>Advisory and total-failure-safe.</b> Like the renderer it feeds, recognition is advisory: a
 * shape that does not match &mdash; not a {@code CALL}, an argument-less call, a call with no
 * resolvable target address or no receiver varnode &mdash; yields {@code null}, never an exception
 * or a fabricated site. The matcher reads only the SSA graph; it holds no
 * {@link ghidra.program.model.listing.Program} and mutates nothing.
 */
public final class CppDestructorRecognizer {

	/**
	 * The structural facts a candidate explicit-destructor {@link PcodeOp#CALL} denotes: the
	 * {@code callTarget} entry address of the called destructor and the {@link Varnode} carrying the
	 * {@code receiver} {@code this} pointer handed to it.
	 *
	 * @param callTarget the entry address of the called destructor; never null
	 * @param receiver the varnode carrying the receiver {@code this} pointer argument; never null
	 */
	public record DestructorCall(Address callTarget, Varnode receiver) {}

	private CppDestructorRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recovers the structural facts of a candidate explicit-destructor call at the given call site.
	 *
	 * @param callSite the candidate call op; may be any op (only a direct {@link PcodeOp#CALL} with at
	 *            least one argument can match) and may be null
	 * @return the recovered {@link DestructorCall}, or null if {@code callSite} is not a direct call
	 *         carrying a resolvable target address and a receiver argument
	 */
	public static DestructorCall recognize(PcodeOp callSite) {
		if (callSite == null || callSite.getOpcode() != PcodeOp.CALL) {
			return null;
		}
		// a destructor runs on a this pointer, so the call must carry at least one argument
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
		return new DestructorCall(callTarget, receiver);
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
