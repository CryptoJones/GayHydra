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

import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;

/**
 * The recognition half of Rec 37 {@code #37-7}: a stateless p-code matcher that decides whether an
 * indirect call ({@link PcodeOp#CALLIND}) in a decompiled {@code HighFunction} is a C++
 * virtual-method dispatch through a vtable slot, and if so recovers the {@code (slotIndex,
 * receiver)} pair that the {@link CppDecompilerHints#renderVirtualCall} renderer (DD-0016) needs.
 *
 * <p>This is the first slice of the {@code #37-7b} Program-coupled wrapper the renderer's javadoc
 * deferred. It is split deliberately: this class recovers only the structural facts that live in
 * the p-code SSA graph &mdash; the slot index and the {@link Varnode} carrying the receiver
 * ({@code this}). Mapping that receiver to a {@link CppClass} and rendering the receiver/argument
 * {@link Varnode}s as C++ expression strings (then calling the renderer) is the separate
 * {@code #37-7b-2} driver slice, which has its own type-resolution and expression-rendering
 * concerns.
 *
 * <p><b>The idiom.</b> The matcher is grounded in the p-code the real decompiler emits for an
 * x86-64 {@code obj->vfn(...)} call (observed via the Rec 30 headless harness, DD-0023):
 *
 * <pre>
 *   CALLIND target                          // the indirect call site
 *     target  = LOAD(space, slotPtr)        // load the function pointer out of the vtable slot
 *       slotPtr = INT_ADD(vtable, k)        // k = slotIndex * pointerSize  (absent for slot 0)
 *         vtable = LOAD(space, receiver)    // load the vtable pointer out of the object
 *           receiver = this                 // the object pointer
 * </pre>
 *
 * Two details a guess would get wrong, and that the matcher therefore encodes: a {@link PcodeOp#LOAD}
 * carries the address-space id in {@code input[0]} and the pointer being dereferenced in
 * {@code input[1]} (so the pointer is {@code getInput(1)}, never {@code getInput(0)}); and the
 * decompiler interposes {@link PcodeOp#CAST}/{@link PcodeOp#COPY} pass-through ops along the chain,
 * which {@link #stripCopyCast} skips. The slot offset {@code k} is a constant addend on an
 * {@link PcodeOp#INT_ADD}; slot 0 has no add at all, so the slot load dereferences the vtable
 * pointer directly.
 *
 * <p><b>Advisory and total-failure-safe.</b> Like the renderer it feeds, recognition is advisory: a
 * shape that does not match the idiom yields {@code null}, never an exception or a fabricated
 * dispatch, so an ordinary indirect call through a function-pointer variable is correctly declined
 * rather than mis-rendered. The matcher reads only the SSA graph; it holds no
 * {@link ghidra.program.model.listing.Program} and mutates nothing.
 */
public final class CppVirtualCallRecognizer {

	/**
	 * The structural facts a virtual-dispatch {@link PcodeOp#CALLIND} denotes: the zero-based vtable
	 * {@code slotIndex} the call targets and the {@link Varnode} carrying the {@code receiver}
	 * (the {@code this} pointer the vtable was loaded from).
	 *
	 * @param slotIndex the zero-based vtable slot index
	 * @param receiver the varnode carrying the receiver / {@code this} pointer; never null
	 */
	public record VirtualDispatch(int slotIndex, Varnode receiver) {}

	private CppVirtualCallRecognizer() {
		// static matcher; not instantiable
	}

	/**
	 * Recognises a virtual-method dispatch at the given call site.
	 *
	 * @param callSite the candidate call op; may be any op (only {@link PcodeOp#CALLIND} can match)
	 *            and may be null
	 * @return the recovered {@link VirtualDispatch}, or null if {@code callSite} is not a vtable
	 *         dispatch in the recognised idiom
	 */
	public static VirtualDispatch recognize(PcodeOp callSite) {
		if (callSite == null || callSite.getOpcode() != PcodeOp.CALLIND) {
			return null;
		}
		Varnode slotPtrLoaded = stripCopyCast(callSite.getInput(0));
		PcodeOp slotLoad = defOf(slotPtrLoaded);
		if (slotLoad == null || slotLoad.getOpcode() != PcodeOp.LOAD) {
			return null;
		}
		Varnode slotPtr = stripCopyCast(slotLoad.getInput(1));
		if (slotPtr == null) {
			return null;
		}

		long byteOffset;
		Varnode vtablePtr;
		PcodeOp slotAdd = slotPtr.getDef();
		if (slotAdd != null && slotAdd.getOpcode() == PcodeOp.INT_ADD &&
			isConstant(slotAdd.getInput(1))) {
			byteOffset = slotAdd.getInput(1).getOffset();
			vtablePtr = stripCopyCast(slotAdd.getInput(0));
		}
		else {
			// slot 0: the slot load dereferences the vtable pointer directly, no addend
			byteOffset = 0;
			vtablePtr = slotPtr;
		}

		PcodeOp vtableLoad = defOf(vtablePtr);
		if (vtableLoad == null || vtableLoad.getOpcode() != PcodeOp.LOAD) {
			return null;
		}
		Varnode receiver = stripCopyCast(vtableLoad.getInput(1));
		if (receiver == null) {
			return null;
		}

		int pointerSize = vtablePtr.getSize();
		if (pointerSize <= 0 || byteOffset < 0 || byteOffset % pointerSize != 0) {
			return null;
		}
		return new VirtualDispatch((int) (byteOffset / pointerSize), receiver);
	}

	private static PcodeOp defOf(Varnode vn) {
		return vn == null ? null : vn.getDef();
	}

	private static boolean isConstant(Varnode vn) {
		return vn != null && vn.isConstant();
	}

	/**
	 * {@return the varnode reached by skipping back over any chain of {@link PcodeOp#CAST} /
	 * {@link PcodeOp#COPY} pass-through ops, so the caller can inspect the {@code getDef()} of the
	 * underlying producer; null in, null out}
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
