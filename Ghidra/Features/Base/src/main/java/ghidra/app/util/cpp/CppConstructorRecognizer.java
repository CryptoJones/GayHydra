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
import ghidra.app.util.cpp.CppDirectCallRecognizer.DirectCall;
import ghidra.program.model.pcode.PcodeOp;

/**
 * The recognition half of Rec 37 {@code #37-9b}: a stateless p-code matcher that recovers the
 * structural facts a heap {@code new C(...)} construction carries &mdash; the constructor call's
 * <em>target address</em> and the <em>allocation</em> call's target address &mdash; from the
 * <em>fused</em> p-code shape a {@code new} expression compiles to. The fact the
 * {@link CppDecompilerHints#renderConstruction} renderer (DD-0016) needs (the constructed class)
 * follows from the constructor target; deciding whether the two calls really <em>are</em> a
 * constructor and {@code operator new} is the driver's job.
 *
 * <p>This is the matcher slice of the {@code #37-9b} Program-coupled wrapper, split on the same line
 * as the {@code #37-7b} virtual-call pair (DD-0024 / DD-0025), the {@code #37-9f-b} delete pair
 * (DD-0026 / DD-0027), and the {@code #37-9c-b} destructor pair (DD-0028 / DD-0029): this class reads
 * only the SSA graph and recovers structural facts; the {@code #37-9b-2}
 * {@link CppConstructorDriver} slice holds the {@link ghidra.program.model.listing.Program}, resolves
 * the recovered {@link ConstructedObject#constructorTarget()} to a function, classifies its name as a
 * constructor (local name equal to its class namespace), resolves that class in a
 * {@link CppTypeSystem}, classifies the {@link ConstructedObject#allocationTarget()} as
 * {@code operator new}, and dispatches to the renderer.
 *
 * <p><b>The idiom, and why this is a <em>fusion</em> matcher.</b> Unlike the delete and destructor
 * forms &mdash; a single recognised {@code CALL} &mdash; a heap {@code new C()} is <em>two</em>
 * linked calls: the allocation produces raw storage, and the constructor runs on it. Grounded in the
 * p-code the real decompiler emits for an x86-64 {@code C* make() { return new C(); }} (observed via
 * the Rec 30 headless harness, DD-0023):
 *
 * <pre>
 *   uniq = CALL operatorNew, size        // the allocation: operator new(size) -&gt; raw void*
 *   rax  = CAST uniq                      // CAST raw void* -&gt; C*
 *   CALL ctor, rax                        // the constructor: C::C(this = the allocated storage)
 * </pre>
 *
 * The detail that distinguishes a heap {@code new} from an in-place construction (a stack
 * {@code C c;} or a member, whose {@code this} is a stack/field address, <em>not</em> a call result)
 * is precisely the <em>fusion link</em>: the constructor's receiver is itself the <em>result of
 * another {@code CALL}</em> (the allocation). The matcher recovers the constructor call's
 * {@code (target, receiver)} via the shared {@link CppDirectCallRecognizer}, then requires that
 * receiver's defining op to be a {@code CALL} &mdash; whose {@code input[0]} is the allocation target.
 * A constructor call whose receiver is not a call result (stack/member construction, or a placement
 * {@code new} whose storage came from elsewhere) is declined here; those are separate forms.
 *
 * <p><b>The direct-call recovery is shared.</b> Recovering the constructor call's {@code input[0]}
 * target plus its cast-stripped {@code input[1]} receiver is the same direct-call shape the delete
 * (DD-0026) and destructor (DD-0028) forms use. This matcher was the third user, the rule-of-three
 * point at which that recovery was extracted into {@link CppDirectCallRecognizer} (DD-0032); this
 * matcher now delegates the recovery and contributes only the form-specific <em>fusion logic</em>
 * (the walk back from the receiver to the allocation call), using
 * {@link CppDirectCallRecognizer#recognize} for the constructor call and
 * {@link CppDirectCallRecognizer#callTargetAddress} for the allocation call.
 *
 * <p><b>Advisory and total-failure-safe.</b> Like the renderer it feeds, recognition is advisory: a
 * shape that does not match &mdash; not a {@code CALL}, an argument-less call, a call with no
 * resolvable target address, a receiver that is not itself a call result, or that result-call having
 * no resolvable target &mdash; yields {@code null}, never an exception or a fabricated site. The
 * matcher reads only the SSA graph; it holds no {@link ghidra.program.model.listing.Program} and
 * mutates nothing.
 */
public final class CppConstructorRecognizer {

	/**
	 * The structural facts a candidate heap-{@code new} construction denotes: the
	 * {@code constructorTarget} entry address of the called constructor and the
	 * {@code allocationTarget} entry address of the allocation call ({@code operator new}) whose
	 * result the constructor runs on.
	 *
	 * @param constructorTarget the entry address of the called constructor; never null
	 * @param allocationTarget the entry address of the allocation call feeding the constructor's
	 *            receiver; never null
	 */
	public record ConstructedObject(Address constructorTarget, Address allocationTarget) {}

	private CppConstructorRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recovers the structural facts of a candidate heap-{@code new} construction at the given
	 * constructor call site.
	 *
	 * @param callSite the candidate constructor call op; may be any op (only a direct
	 *            {@link PcodeOp#CALL} whose receiver is itself a call result can match) and may be null
	 * @return the recovered {@link ConstructedObject}, or null if {@code callSite} is not a constructor
	 *         call whose cast-stripped receiver is the result of a resolvable allocation call
	 */
	public static ConstructedObject recognize(PcodeOp callSite) {
		DirectCall constructor = CppDirectCallRecognizer.recognize(callSite);
		if (constructor == null) {
			return null;
		}
		// the fusion link: a heap new's this pointer is the result of the allocation call.
		PcodeOp allocation = constructor.receiver().getDef();
		if (allocation == null || allocation.getOpcode() != PcodeOp.CALL) {
			return null;
		}
		Address allocationTarget = CppDirectCallRecognizer.callTargetAddress(allocation);
		if (allocationTarget == null) {
			return null;
		}
		return new ConstructedObject(constructor.callTarget(), allocationTarget);
	}
}
