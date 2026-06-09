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
 * The shared <em>direct-call recovery</em> for Rec 37 recognition: a stateless p-code matcher that
 * recovers the two structural facts a direct call ({@link PcodeOp#CALL}) carries &mdash; the call's
 * <em>target address</em> (a direct {@code CALL} encodes its callee entry in {@code input[0]}) and
 * its first-argument <em>receiver</em> {@link Varnode}, reached after skipping any interposed
 * {@link PcodeOp#CAST}/{@link PcodeOp#COPY} pass-through &mdash; from a decompiled {@code HighFunction}.
 * It reads only the SSA graph; it holds no {@link ghidra.program.model.listing.Program} and decides
 * nothing about what the callee <em>is</em> (that is each form's driver's job).
 *
 * <p><b>The rule-of-three extraction.</b> This recovery was first established for the
 * {@code #37-9f-b} delete form (DD-0026), reused verbatim for the {@code #37-9c-b} destructor form
 * (DD-0028), and used a third time inside the {@code #37-9b} constructor fusion matcher (DD-0030).
 * Per DD-0026's standing note, the shared extractor was deliberately kept inlined as honest per-form
 * twins until that third user proved the abstraction's shape; this class is that extraction (DD-0032),
 * done as a dedicated refactor at the rule-of-three threshold rather than guessed at the first
 * instance. Its three users are:
 * <ul>
 * <li>the {@code #37-9f-b} {@link CppDeleteDriver}: a {@code delete e} is a direct call to
 * {@code operator delete} passing the freed pointer (often through a {@code void *} {@code CAST},
 * which {@link #recognize} strips);</li>
 * <li>the {@code #37-9c-b} {@link CppDestructorDriver}: an explicit {@code p->~C()} is a direct call
 * to the destructor passing {@code this} as its sole argument;</li>
 * <li>the {@code #37-9b} {@link CppConstructorRecognizer}: a heap {@code new C()} is a direct call to
 * the constructor whose receiver is itself the result of the allocation call &mdash; the fusion
 * matcher uses {@link #recognize} for the constructor call and {@link #callTargetAddress} for the
 * allocation call.</li>
 * </ul>
 *
 * <p><b>The idiom.</b> A direct call passing one explicit argument:
 *
 * <pre>
 *   CALL target, arg0                // a direct call
 *     target = (ram) call-target addr // input[0]: the callee entry address
 *     arg0   = receiver               // input[1]: the first argument, possibly CAST/COPY-wrapped
 * </pre>
 *
 * <p><b>Advisory and total-failure-safe.</b> A shape that does not match &mdash; not a {@code CALL},
 * an argument-less call, a call with no resolvable memory target, or no receiver varnode &mdash;
 * yields {@code null}, never an exception. The matcher mutates nothing.
 */
public final class CppDirectCallRecognizer {

	/**
	 * The structural facts a direct {@link PcodeOp#CALL} denotes: the {@code callTarget} entry address
	 * of the called function and the {@link Varnode} carrying the {@code receiver} (first explicit
	 * argument) handed to it.
	 *
	 * @param callTarget the entry address of the called function; never null
	 * @param receiver the varnode carrying the first-argument receiver; never null
	 */
	public record DirectCall(Address callTarget, Varnode receiver) {}

	private CppDirectCallRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recovers the target address and first-argument receiver of a direct call at the given call site.
	 *
	 * @param callSite the candidate call op; may be any op (only a direct {@link PcodeOp#CALL} with at
	 *            least one argument can match) and may be null
	 * @return the recovered {@link DirectCall}, or null if {@code callSite} is not a direct call
	 *         carrying a resolvable target address and a receiver argument
	 */
	public static DirectCall recognize(PcodeOp callSite) {
		if (callSite == null || callSite.getOpcode() != PcodeOp.CALL) {
			return null;
		}
		// a recognised direct call passes at least one explicit argument (the receiver)
		if (callSite.getNumInputs() < 2) {
			return null;
		}
		Address callTarget = callTargetAddress(callSite);
		if (callTarget == null) {
			return null;
		}
		Varnode receiver = stripCopyCast(callSite.getInput(1));
		if (receiver == null) {
			return null;
		}
		return new DirectCall(callTarget, receiver);
	}

	/**
	 * {@return the memory entry address a direct {@link PcodeOp#CALL} encodes in its {@code input[0]}
	 * target varnode, or null if {@code call} is not a call op with a resolvable memory target}
	 *
	 * @param call the call op to read the target from; may be null
	 */
	public static Address callTargetAddress(PcodeOp call) {
		if (call == null || call.getNumInputs() < 1) {
			return null;
		}
		Varnode target = call.getInput(0);
		if (target == null) {
			return null;
		}
		Address address = target.getAddress();
		if (address == null || !address.isMemoryAddress()) {
			return null;
		}
		return address;
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
