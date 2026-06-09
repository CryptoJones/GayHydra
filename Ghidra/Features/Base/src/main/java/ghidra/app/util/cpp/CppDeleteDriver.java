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

import ghidra.app.util.cpp.CppDeleteRecognizer.DeleteCall;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;

/**
 * The driver half of Rec 37 {@code #37-9f-b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppDeleteRecognizer} matcher ({@code #37-9f-b-1}, DD-0026) to find candidate direct
 * deallocation {@code CALL}s, resolves each recovered call target to a {@link Function},
 * classifies its name as scalar {@code operator delete} or array {@code operator delete[]}, and
 * dispatches to the stateless {@link CppDecompilerHints#renderDelete} renderer (DD-0022) to produce
 * the C++ hint string. This is the {@code #37-9f-b-2} slice that closes the loop the recognizer
 * opened.
 *
 * <p><b>Why the callee name, and why here.</b> Unlike the {@code #37-7b} virtual-call driver, this
 * one resolves no {@link CppClass}: {@code delete e} / {@code delete[] e} names no type, so
 * {@code renderDelete} takes no class and the driver needs no {@link CppTypeSystem}. What it
 * <em>does</em> need is the one fact the matcher could not read from the p-code &mdash; whether the
 * called function is a deallocation function at all, and if so whether it is the scalar or array
 * operator. That fact lives in the callee's <em>name</em>, which is {@link ghidra.program.model.listing.Program}-coupled,
 * so the driver holds the program (via {@code function.getFunction().getProgram()}), resolves the
 * recovered {@link DeleteCall#callTarget()} address to a {@link Function}, and classifies its name.
 * Names are matched in the form a demangler produces (Ghidra's {@code .} namespace separator),
 * i.e. {@code operator.delete} and {@code operator.delete[]}; the array form is checked first
 * because it contains the scalar substring. The pass therefore assumes the demangler analyzer has
 * run &mdash; which it has by the time a function decompiles to a {@code HighFunction} in a
 * fully-analyzed program.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: a call whose target resolves to no function, whose function is
 * not an {@code operator delete}, or whose receiver has no printable name is silently skipped (it
 * contributes no hint), never mis-rendered or raised as an error. A function with no recognised
 * deallocation yields an empty list.
 *
 * <p><b>Scope: the dtor-then-delete pairing is not yet fused.</b> A {@code delete p} of a
 * non-trivial type emits a destructor call <em>before</em> the {@code operator delete} free. This
 * slice renders the {@code operator delete} call itself as {@code delete p}; pairing (and consuming)
 * the preceding destructor call so the future explicit-destructor driver ({@code #37-9c-b}) does not
 * also render it is a later cross-form refinement, noted on {@code renderDelete}'s own javadoc.
 */
public final class CppDeleteDriver {

	/**
	 * A rendered deallocation hint: the {@code site} address of the {@code CALL} it was recovered
	 * from, and the {@code rendering} string the {@link CppDecompilerHints} renderer produced.
	 *
	 * @param site the address of the dispatching call op
	 * @param rendering the rendered C++ {@code delete} expression
	 */
	public record RenderedDelete(Address site, String rendering) {}

	private enum DeleteKind {
		SCALAR, ARRAY, NONE
	}

	private final CppDecompilerHints renderer;

	/**
	 * Constructs a driver over the given renderer.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @throws IllegalArgumentException if {@code renderer} is null
	 */
	public CppDeleteDriver(CppDecompilerHints renderer) {
		if (renderer == null) {
			throw new IllegalArgumentException("renderer must not be null");
		}
		this.renderer = renderer;
	}

	/**
	 * Recognises every deallocation call in the function and renders a hint for each one whose target
	 * resolves to a scalar or array {@code operator delete} and whose receiver has a printable name.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedDelete> recognizeAndRender(HighFunction function) {
		if (function == null) {
			throw new IllegalArgumentException("high function must not be null");
		}
		Function host = function.getFunction();
		if (host == null) {
			return List.of();
		}
		Program program = host.getProgram();
		if (program == null) {
			return List.of();
		}
		FunctionManager functionManager = program.getFunctionManager();
		List<RenderedDelete> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			DeleteCall delete = CppDeleteRecognizer.recognize(op);
			if (delete == null) {
				continue;
			}
			RenderedDelete hint = render(op, delete, functionManager);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedDelete render(PcodeOp callSite, DeleteCall delete,
			FunctionManager functionManager) {
		Function callee = functionManager.getFunctionAt(delete.callTarget());
		if (callee == null) {
			return null;
		}
		DeleteKind kind = classifyDelete(callee.getName());
		if (kind == DeleteKind.NONE) {
			return null;
		}
		HighVariable high = delete.receiver().getHigh();
		if (high == null) {
			return null;
		}
		String receiverExpr = high.getName();
		if (receiverExpr == null || receiverExpr.isBlank()) {
			return null;
		}
		String rendering = renderer.renderDelete(receiverExpr, kind == DeleteKind.ARRAY);
		return new RenderedDelete(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * {@return whether the callee name denotes the scalar {@code operator delete}, the array
	 * {@code operator delete[]}, or neither &mdash; matched in the demangled form Ghidra emits
	 * (its {@code .} namespace separator), with the array form checked first because it contains the
	 * scalar substring}
	 */
	private static DeleteKind classifyDelete(String calleeName) {
		if (calleeName == null) {
			return DeleteKind.NONE;
		}
		// normalise Ghidra's '.' namespace separator to a space so the demangled "operator.delete"
		// and the plain "operator delete" form compare equal.
		String normalized = calleeName.replace('.', ' ');
		if (normalized.equals("operator delete[]")) {
			return DeleteKind.ARRAY;
		}
		if (normalized.equals("operator delete")) {
			return DeleteKind.SCALAR;
		}
		return DeleteKind.NONE;
	}
}
