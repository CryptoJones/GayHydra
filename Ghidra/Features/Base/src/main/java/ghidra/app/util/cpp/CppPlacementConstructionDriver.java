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

import ghidra.app.util.cpp.CppPlacementConstructionRecognizer.PlacementConstruction;
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
 * The driver half of Rec 37 {@code #37-9e-b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppPlacementConstructionRecognizer} matcher ({@code #37-9e-b-1}, DD-0037) to find candidate
 * non-elided placement-{@code new} constructions, resolves the recovered constructor and allocation
 * targets to {@link Function}s, confirms the first is a constructor and the second is
 * {@code operator new}, recovers the placement buffer's expression, resolves the constructed
 * {@link CppClass} in a supplied {@link CppTypeSystem}, and dispatches to the stateless
 * {@link CppDecompilerHints#renderPlacementConstruction} renderer (DD-0016) to produce the C++ hint
 * string. This is the {@code #37-9e-b-2} slice that closes the loop the matcher opened &mdash; the
 * <b>seventh and last</b> Rec 37 recognition form to go end-to-end.
 *
 * <p><b>The same two callee classifications as heap {@code new}, plus the buffer.</b> Like the
 * {@code #37-9b} {@link CppConstructorDriver} (DD-0031), the driver holds the {@link Program} and
 * classifies the two recovered callees from their demangled names: the constructor by the
 * {@code name == class} marker (its local name equals its parent class namespace), and the allocation
 * as {@code operator new} (its {@code .} namespace separator normalised to a space). What it adds over
 * the heap driver is the <em>placement target</em>: it renders the recovered buffer varnode's
 * {@link HighVariable} name as the placement expression &mdash; the same operand-rendering the delete,
 * destructor, and cast drivers use &mdash; and dispatches to {@code renderPlacementConstruction}, which
 * emits {@code new (buf) ClassName(args)}. (The two small name classifiers are duplicated from the heap
 * driver rather than extracted: at this second user they are kept as honest per-form twins, per the
 * DD-0026 rule-of-three convention, until a third user earns the extraction.)
 *
 * <p><b>Explicit constructor arguments are threaded</b> ({@code #37-10a}): the constructor {@code CALL}'s
 * inputs after the call target (index 0) and the {@code this} receiver (index 1) are its explicit
 * arguments, each rendered by its {@link HighVariable} name, so {@code new (buf) ClassName(arg)} renders
 * with its argument. A zero-argument constructor still renders {@code new (buf) ClassName()}. An argument
 * with no printable name (an unnamed temporary, or a bare constant, which carries no {@code HighVariable})
 * declines the whole hint rather than rendering a gap; rendering constants and compound argument
 * expressions is later {@code #37-10} work, as is overload resolution against the {@code DataType}
 * signature.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: a construction whose constructor target resolves to no function or
 * is not a {@code name == class} constructor, whose allocation target is not {@code operator new},
 * whose placement buffer has no printable name, or whose class is not modelled in the type system is
 * silently skipped (it contributes no hint), never mis-rendered or raised as an error. A function with
 * no recognised placement construction yields an empty list.
 */
public final class CppPlacementConstructionDriver {

	/**
	 * A rendered placement-construction hint: the {@code site} address of the constructor {@code CALL}
	 * it was recovered from, and the {@code rendering} string the {@link CppDecompilerHints} renderer
	 * produced.
	 *
	 * @param site the address of the dispatching constructor call op
	 * @param rendering the rendered C++ placement-{@code new} expression
	 */
	public record RenderedPlacement(Address site, String rendering) {}

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
	public CppPlacementConstructionDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
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
	 * Recognises every non-elided placement-{@code new} construction in the function and renders a hint
	 * for each one whose constructor resolves to a {@code name == class} constructor of a modelled
	 * class, whose allocation resolves to {@code operator new}, and whose placement buffer has a
	 * printable name.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedPlacement> recognizeAndRender(HighFunction function) {
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
		List<RenderedPlacement> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			PlacementConstruction object = CppPlacementConstructionRecognizer.recognize(op);
			if (object == null) {
				continue;
			}
			RenderedPlacement hint = render(op, object, functionManager);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedPlacement render(PcodeOp callSite, PlacementConstruction object,
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
		String placementExpr = operandName(object.placementBuffer());
		if (placementExpr == null) {
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
		String rendering =
			renderer.renderPlacementConstruction(type, placementExpr, argumentExprs);
		return new RenderedPlacement(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * Recovers the explicit constructor arguments to render between the {@code ClassName(...)}
	 * parentheses. In the constructor {@code CALL}, input 0 is the call target and input 1 is the
	 * {@code this} receiver (the placement buffer); the explicit arguments are every input after that,
	 * in order &mdash; grounded against a decompiled placement {@code new (buf) C(arg)} whose
	 * constructor {@code CALL} carries the argument as its third input.
	 *
	 * <p>Each argument is rendered as its {@link HighVariable} name, the same operand rendering the
	 * buffer/receiver uses. An argument with no printable name (an unnamed temporary, or a bare
	 * constant, which carries no {@code HighVariable}) declines the whole hint ({@code null}) rather
	 * than rendering a constructor call with a gap in its argument list &mdash; the same advisory,
	 * never-wrong contract the receiver rendering holds. Rendering constants and compound expressions
	 * is later {@code #37-10} work.
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
