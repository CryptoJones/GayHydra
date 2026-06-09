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

import ghidra.app.util.cpp.CppDestructorRecognizer.DestructorCall;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;

/**
 * The driver half of Rec 37 {@code #37-9c-b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppDestructorRecognizer} matcher ({@code #37-9c-b-1}, DD-0028) to find candidate direct
 * destructor {@code CALL}s, resolves each recovered call target to a {@link Function}, classifies
 * its name as a destructor ({@code ~ClassName}), resolves that class in a supplied
 * {@link CppTypeSystem}, and dispatches to the stateless
 * {@link CppDecompilerHints#renderDestructorCall} renderer (DD-0016) to produce the C++ hint string.
 * This is the {@code #37-9c-b-2} slice that closes the loop the recognizer opened.
 *
 * <p><b>Two model facts, two sources.</b> The renderer needs three things to emit
 * {@code receiver->~ClassName()}: the receiver expression, whether the receiver is a pointer (so
 * {@code ->} vs {@code .}), and the class whose destructor runs. This driver reads each from where it
 * actually lives:
 * <ul>
 * <li><b>Which class</b> comes from the <em>callee's name</em>, not the receiver's type. A destructor
 * function's Ghidra local name is {@code ~ClassName} (e.g. {@code _ZN6Magick5ImageD1Ev} demangles to
 * {@code Magick::Image::~Image()}, local name {@code ~Image}); the driver resolves the recovered
 * {@link DestructorCall#callTarget()} to a {@link Function}, strips the leading {@code ~}, and looks
 * the {@link CppClass} up by the remaining name in the type system. The callee names the destructor
 * being invoked &mdash; the authoritative class for the rendered {@code ~ClassName} &mdash; which is
 * why the class is read here and not from the (possibly base-adjusted) receiver type. This is the one
 * fact the matcher could not read (it holds no {@link Program}).</li>
 * <li><b>Receiver expression and pointer-ness</b> come from the receiver {@link Varnode}'s
 * {@link HighVariable}: its name (e.g. {@code param_1}) is the identifier the decompiler prints, and
 * its {@link Pointer}-ness selects {@code ->} over {@code .}. The usual {@code this} is a {@code C *},
 * so the pointer form is the common case.</li>
 * </ul>
 *
 * <p>The pass therefore assumes the demangler analyzer has run &mdash; which it has by the time a
 * function decompiles to a {@code HighFunction} in a fully-analyzed program. A not-yet-demangled
 * mangled destructor symbol would be declined.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: a call whose target resolves to no function, whose name is not a
 * {@code ~ClassName} destructor, whose class is not modelled in the type system, or whose receiver
 * has no printable name is silently skipped (it contributes no hint), never mis-rendered or raised as
 * an error. A function with no recognised destructor yields an empty list.
 *
 * <p><b>Scope: the dtor-then-delete pairing is not yet fused.</b> A {@code delete p} of a non-trivial
 * type emits this explicit destructor call <em>before</em> the {@code operator delete} free; the
 * {@code #37-9f-b} delete driver (DD-0027) renders that {@code operator delete} as {@code delete p}.
 * Pairing the two so a {@code delete p} is rendered once rather than as a destructor call plus a
 * {@code delete} is a later cross-form refinement; this slice renders the explicit destructor call on
 * its own terms.
 */
public final class CppDestructorDriver {

	/**
	 * A rendered destructor-call hint: the {@code site} address of the {@code CALL} it was recovered
	 * from, and the {@code rendering} string the {@link CppDecompilerHints} renderer produced.
	 *
	 * @param site the address of the dispatching call op
	 * @param rendering the rendered C++ destructor-call expression
	 */
	public record RenderedDestructorCall(Address site, String rendering) {}

	private final CppDecompilerHints renderer;
	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a driver over the given renderer and type-system model.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @param typeSystem the model resolving destructor classes to {@link CppClass}es; must not be null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public CppDestructorDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
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
	 * Recognises every explicit destructor call in the function and renders a hint for each one whose
	 * target resolves to a {@code ~ClassName} destructor of a modelled class and whose receiver has a
	 * printable name.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedDestructorCall> recognizeAndRender(HighFunction function) {
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
		List<RenderedDestructorCall> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			DestructorCall dtor = CppDestructorRecognizer.recognize(op);
			if (dtor == null) {
				continue;
			}
			RenderedDestructorCall hint = render(op, dtor, functionManager);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedDestructorCall render(PcodeOp callSite, DestructorCall dtor,
			FunctionManager functionManager) {
		Function callee = functionManager.getFunctionAt(dtor.callTarget());
		if (callee == null) {
			return null;
		}
		String className = destructorClassName(callee.getName());
		if (className == null) {
			return null;
		}
		CppClass type = typeSystem.getCppClass(className);
		if (type == null) {
			return null;
		}
		HighVariable high = dtor.receiver().getHigh();
		if (high == null) {
			return null;
		}
		String receiverExpr = high.getName();
		if (receiverExpr == null || receiverExpr.isBlank()) {
			return null;
		}
		boolean receiverIsPointer = high.getDataType() instanceof Pointer;
		String rendering = renderer.renderDestructorCall(type, receiverExpr, receiverIsPointer);
		return new RenderedDestructorCall(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * {@return the class name a destructor callee names &mdash; the text after the leading {@code ~}
	 * of a {@code ~ClassName} local name &mdash; or null if the name is not a destructor (no leading
	 * {@code ~}, or nothing after it)}
	 */
	private static String destructorClassName(String calleeName) {
		if (calleeName == null || calleeName.length() < 2 || calleeName.charAt(0) != '~') {
			return null;
		}
		return calleeName.substring(1);
	}
}
