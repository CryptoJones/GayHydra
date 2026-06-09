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

import ghidra.app.util.cpp.CppConstructorRecognizer.ConstructedObject;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.symbol.Namespace;

/**
 * The driver half of Rec 37 {@code #37-9b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppConstructorRecognizer} fusion matcher ({@code #37-9b-1}, DD-0030) to find candidate
 * heap-{@code new} constructions, resolves the recovered constructor and allocation targets to
 * {@link Function}s, confirms the first is a constructor and the second is {@code operator new},
 * resolves the constructed {@link CppClass} in a supplied {@link CppTypeSystem}, and dispatches to
 * the stateless {@link CppDecompilerHints#renderConstruction} renderer (DD-0016) to produce the C++
 * hint string. This is the {@code #37-9b-2} slice that closes the loop the recognizer opened.
 *
 * <p><b>Two callee classifications, both from names.</b> The matcher recovered two addresses but, as
 * a p-code-only pass, could not decide what the callees <em>are</em>. The driver holds the
 * {@link Program} (via {@code function.getFunction().getProgram()}) and classifies each:
 * <ul>
 * <li><b>The constructor, and its class.</b> A constructor's demangled Ghidra local name <em>equals
 * its class</em> — the function {@code getName()} equals its parent (class) namespace name
 * (e.g. {@code _ZN3Bar4FredC1Ei} demangles to {@code Bar::Fred::Fred(int)}, local name {@code Fred}
 * in namespace {@code Fred}). The driver resolves {@link ConstructedObject#constructorTarget()} to a
 * {@link Function}, requires its name to equal its parent namespace name, and looks that class up in
 * the type system. This {@code name == class} marker is the constructor's counterpart to the
 * destructor's {@code ~} prefix; a member method ({@code Fred::foo}) has a name unequal to its class
 * and is declined.</li>
 * <li><b>The allocation as {@code operator new}.</b> The driver resolves
 * {@link ConstructedObject#allocationTarget()} and requires its name (in the demangled form Ghidra
 * emits, its {@code .} namespace separator) to be {@code operator new} — normalised the same way the
 * {@code #37-9f-b} delete driver (DD-0027) matches {@code operator delete}. A construction on storage
 * from any other call (a placement {@code new}, a custom allocator) is declined here; the placement
 * form is the separate {@code #37-9e-b} slice.</li>
 * </ul>
 *
 * <p>The renderer emits {@code new ClassName(args)} and takes <em>no receiver</em>: a heap {@code new}
 * is the allocation-plus-construction, with no printed {@code this}. <b>Explicit constructor arguments
 * are threaded</b> ({@code #37-10b}): the constructor {@code CALL}'s inputs after the call target
 * (index 0) and the {@code this} receiver (index 1) are its explicit arguments, each rendered by its
 * {@link HighVariable} name, so {@code new ClassName(arg)} renders with its argument and a zero-argument
 * constructor still renders {@code new ClassName()}. An argument with no printable name (an unnamed
 * temporary, or a bare constant, which carries no {@code HighVariable}) declines the whole hint rather
 * than rendering a gap &mdash; the same advisory, never-wrong contract the {@code #37-10a}
 * {@link CppPlacementConstructionDriver} holds. (The two small argument helpers are duplicated from the
 * placement driver rather than extracted: at this second user they are kept as honest per-form twins,
 * per the DD-0026 rule-of-three convention, until a third user earns the extraction.) Rendering
 * constants and compound argument expressions is later {@code #37-10} work, as is overload resolution
 * against the {@code DataType} signature.
 *
 * <p>The pass therefore assumes the demangler analyzer has run — which it has by the time a function
 * decompiles to a {@code HighFunction} in a fully-analyzed program. A not-yet-demangled mangled
 * constructor or {@code operator new} symbol would be declined.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: a construction whose constructor target resolves to no function or
 * is not a {@code name == class} constructor, whose allocation target is not {@code operator new}, or
 * whose class is not modelled in the type system is silently skipped (it contributes no hint), never
 * mis-rendered or raised as an error. A function with no recognised construction yields an empty list.
 */
public final class CppConstructorDriver {

	/**
	 * A rendered heap-construction hint: the {@code site} address of the constructor {@code CALL} it
	 * was recovered from, and the {@code rendering} string the {@link CppDecompilerHints} renderer
	 * produced.
	 *
	 * @param site the address of the dispatching constructor call op
	 * @param rendering the rendered C++ {@code new} expression
	 */
	public record RenderedConstruction(Address site, String rendering) {}

	/** A constructor {@code CALL}'s input 0 is the call target; input 1 is the {@code this} receiver. */
	private static final int THIS_INPUT_INDEX = 1;

	private final CppDecompilerHints renderer;
	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a driver over the given renderer and type-system model.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @param typeSystem the model resolving constructed classes to {@link CppClass}es; must not be null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public CppConstructorDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
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
	 * Recognises every heap-{@code new} construction in the function and renders a hint for each one
	 * whose constructor resolves to a {@code name == class} constructor of a modelled class and whose
	 * allocation resolves to {@code operator new}.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedConstruction> recognizeAndRender(HighFunction function) {
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
		List<RenderedConstruction> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			ConstructedObject object = CppConstructorRecognizer.recognize(op);
			if (object == null) {
				continue;
			}
			RenderedConstruction hint = render(op, object, functionManager);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedConstruction render(PcodeOp callSite, ConstructedObject object,
			FunctionManager functionManager) {
		Function constructor = functionManager.getFunctionAt(object.constructorTarget());
		if (constructor == null) {
			return null;
		}
		String className = constructorClassName(constructor);
		if (className == null) {
			return null;
		}
		Function allocation = functionManager.getFunctionAt(object.allocationTarget());
		if (allocation == null || !isOperatorNew(allocation.getName())) {
			return null;
		}
		CppClass type = typeSystem.getCppClass(className);
		if (type == null) {
			return null;
		}
		List<String> argumentExprs = constructorArguments(callSite);
		if (argumentExprs == null) {
			return null;
		}
		String rendering = renderer.renderConstruction(type, argumentExprs);
		return new RenderedConstruction(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * Recovers the explicit constructor arguments to render between the {@code ClassName(...)}
	 * parentheses. In the constructor {@code CALL}, input 0 is the call target and input 1 is the
	 * {@code this} receiver (the allocated storage); the explicit arguments are every input after that,
	 * in order &mdash; the same layout the {@code #37-10a} placement driver threads.
	 *
	 * <p>Each argument is rendered as its {@link HighVariable} name, the same operand rendering the
	 * receiver forms use. An argument with no printable name (an unnamed temporary, or a bare constant,
	 * which carries no {@code HighVariable}) declines the whole hint ({@code null}) rather than rendering
	 * a constructor call with a gap in its argument list. Rendering constants and compound expressions is
	 * later {@code #37-10} work.
	 *
	 * @return the rendered argument expressions in call order (empty for a no-argument constructor), or
	 *         null if any argument has no printable operand name
	 */
	private static List<String> constructorArguments(PcodeOp constructorCall) {
		List<String> arguments = new ArrayList<>();
		for (int i = THIS_INPUT_INDEX + 1; i < constructorCall.getNumInputs(); i++) {
			String argument = operandName(constructorCall.getInput(i));
			if (argument == null) {
				return null;
			}
			arguments.add(argument);
		}
		return arguments;
	}

	/**
	 * {@return the printable name of a varnode's {@link HighVariable} (e.g. {@code param_1}), or null if
	 * it has none or it is blank}
	 */
	private static String operandName(Varnode varnode) {
		HighVariable high = varnode.getHigh();
		if (high == null) {
			return null;
		}
		String name = high.getName();
		return (name == null || name.isBlank()) ? null : name;
	}

	/**
	 * {@return the class name a constructor callee names — its local name, which a demangled
	 * constructor shares with its parent (class) namespace — or null if the function is not a
	 * {@code name == class} constructor (no parent namespace, or a name unequal to it)}
	 */
	private static String constructorClassName(Function constructor) {
		String name = constructor.getName();
		Namespace parent = constructor.getParentNamespace();
		if (name == null || parent == null) {
			return null;
		}
		String parentName = parent.getName();
		if (parentName == null || !name.equals(parentName)) {
			return null;
		}
		return name;
	}

	/**
	 * {@return whether the callee name denotes {@code operator new} — matched in the demangled form
	 * Ghidra emits (its {@code .} namespace separator normalised to a space), so {@code operator.new}
	 * and the plain {@code operator new} form compare equal}
	 */
	private static boolean isOperatorNew(String calleeName) {
		if (calleeName == null) {
			return false;
		}
		return calleeName.replace('.', ' ').equals("operator new");
	}
}
