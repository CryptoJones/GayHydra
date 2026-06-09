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

import ghidra.app.util.cpp.CppVirtualCallRecognizer.VirtualDispatch;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.data.Structure;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;

/**
 * The driver half of Rec 37 {@code #37-7b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppVirtualCallRecognizer} matcher ({@code #37-7b-1}, DD-0024) to find vtable-dispatch
 * {@code CALLIND}s, resolves each recovered receiver to a {@link CppClass} in a supplied
 * {@link CppTypeSystem}, and dispatches to the stateless
 * {@link CppDecompilerHints#renderVirtualCall} renderer (DD-0016) to produce the C++ hint string.
 * This is the {@code #37-7b-2} slice that closes the loop the recognizer opened.
 *
 * <p><b>How a receiver becomes a class.</b> The matcher hands back the {@link Varnode} carrying the
 * receiver ({@code this}). Its {@link HighVariable} datatype is the recovered type of {@code this}
 * &mdash; a {@link Pointer} to the class {@link Structure} for a pointer receiver (the usual case),
 * or the {@link Structure} directly for a value receiver. {@link #resolveReceiverClass} unwraps one
 * pointer level, takes the structure name, and looks the {@link CppClass} up by name in the type
 * system ({@link CppTypeSystem#getCppClass}). The receiver expression handed to the renderer is the
 * {@code HighVariable}'s own name (e.g. {@code param_1}), which is exactly the identifier the
 * decompiler prints for it.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: any call whose receiver has no {@link HighVariable}, whose type
 * is not a known modelled class, or whose receiver has no printable name is silently skipped (it
 * contributes no hint), never mis-rendered or raised as an error. A function with no recognised
 * dispatch yields an empty list.
 *
 * <p><b>Scope: arguments are not yet threaded.</b> This slice renders the receiver, the vtable slot,
 * and the owning class &mdash; {@code receiver->method()} &mdash; with an <em>empty</em> argument
 * list. That is grounded in what the decompiler actually produces: an unresolved indirect call has
 * no recovered prototype, so its {@code CALLIND} carries only the call-target input and no argument
 * varnodes to render. Recovering and rendering virtual-call arguments depends on indirect-call
 * prototype recovery and is deliberately left to a later slice; the renderer already accepts an
 * empty argument list and emits {@code receiver->method()} for it.
 */
public final class CppVirtualCallDriver {

	/**
	 * A rendered virtual-call hint: the {@code site} address of the {@code CALLIND} it was recovered
	 * from, and the {@code rendering} string the {@link CppDecompilerHints} renderer produced.
	 *
	 * @param site the address of the dispatching call op
	 * @param rendering the rendered C++ virtual-call expression
	 */
	public record RenderedVirtualCall(Address site, String rendering) {}

	private final CppDecompilerHints renderer;
	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a driver over the given renderer and type-system model.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @param typeSystem the model resolving receiver structures to {@link CppClass}es; must not be
	 *            null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public CppVirtualCallDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
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
	 * Recognises every vtable-dispatch call in the function and renders a hint for each one whose
	 * receiver resolves to a modelled class.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedVirtualCall> recognizeAndRender(HighFunction function) {
		if (function == null) {
			throw new IllegalArgumentException("high function must not be null");
		}
		List<RenderedVirtualCall> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALLIND) {
				continue;
			}
			VirtualDispatch dispatch = CppVirtualCallRecognizer.recognize(op);
			if (dispatch == null) {
				continue;
			}
			RenderedVirtualCall hint = render(op, dispatch);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedVirtualCall render(PcodeOp callSite, VirtualDispatch dispatch) {
		HighVariable high = dispatch.receiver().getHigh();
		if (high == null) {
			return null;
		}
		ReceiverClass receiverClass = resolveReceiverClass(high.getDataType());
		if (receiverClass == null) {
			return null;
		}
		String receiverExpr = high.getName();
		if (receiverExpr == null || receiverExpr.isBlank()) {
			return null;
		}
		String rendering = renderer.renderVirtualCall(receiverClass.owningClass(),
			dispatch.slotIndex(), receiverExpr, receiverClass.receiverIsPointer(), List.of());
		return new RenderedVirtualCall(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * {@return the modelled class the receiver type denotes (with whether the receiver is a pointer),
	 * or null if the type is not a pointer-to / value of a structure registered in the type system}
	 */
	private ReceiverClass resolveReceiverClass(DataType receiverType) {
		boolean receiverIsPointer = false;
		DataType dt = receiverType;
		if (dt instanceof Pointer pointer) {
			receiverIsPointer = true;
			dt = pointer.getDataType();
		}
		if (!(dt instanceof Structure structure)) {
			return null;
		}
		CppClass owningClass = typeSystem.getCppClass(structure.getName());
		if (owningClass == null) {
			return null;
		}
		return new ReceiverClass(owningClass, receiverIsPointer);
	}

	private record ReceiverClass(CppClass owningClass, boolean receiverIsPointer) {}
}
