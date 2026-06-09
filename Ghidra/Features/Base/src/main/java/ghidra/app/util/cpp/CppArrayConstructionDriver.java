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

import ghidra.app.util.cpp.CppArrayConstructionRecognizer.ArrayAllocation;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
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
 * The driver half of Rec 37 {@code #37-9d-b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppArrayConstructionRecognizer} matcher (DD-0033) to find candidate array-{@code new}
 * allocation {@code CALL}s, resolves each recovered allocation target to a {@link Function},
 * classifies its name as array {@code operator new[]}, reads the element class off the recovered
 * typed result's pointer type, computes the element count, resolves that class in a supplied
 * {@link CppTypeSystem}, and dispatches to the stateless
 * {@link CppDecompilerHints#renderArrayConstruction} renderer (DD-0016) to produce the C++ hint
 * string. This is the {@code #37-9d-b-2} slice that closes the loop the recognizer opened.
 *
 * <p><b>Two facts, two sources — both read from forward of the call.</b> The renderer needs the
 * element class and the element count to emit {@code new ClassName[count]}. Unlike the scalar
 * {@code #37-9b} constructor driver, which reads the class from the constructor <em>callee's name</em>,
 * the trivial-element array {@code new} this form recognises has <em>no</em> constructor call to name
 * the class. Both facts therefore come from what the matcher recovered forward of the allocation:
 * <ul>
 * <li><b>The element class</b> is the type the raw {@code void *} storage is reinterpreted into:
 * {@link ArrayAllocation#typedResult()} carries a pointer {@link HighVariable}; the driver strips one
 * pointer level to the pointed element type and looks its name up as a {@link CppClass}.</li>
 * <li><b>The element count</b> is the allocation's byte size divided by the element size:
 * {@link ArrayAllocation#byteSize()} is the total bytes (e.g. {@code 0x28}); dividing by the pointed
 * element type's {@link DataType#getLength()} (e.g. {@code 8}) gives the count (e.g. {@code 5}). The
 * driver renders a count only when the size is a positive constant that divides evenly &mdash; a
 * non-constant or non-multiple size is declined rather than rendering a fabricated or fractional
 * count.</li>
 * </ul>
 *
 * <p><b>Why the callee name, and why here.</b> The matcher's anchor is name-blind: a sized allocation
 * whose result becomes a typed pointer is structurally identical for {@code operator new[]},
 * {@code operator new}, and {@code malloc}. The one fact distinguishing an array {@code new} &mdash;
 * that the callee is the array {@code operator new[]} &mdash; lives in the callee's
 * {@link ghidra.program.model.listing.Program}-coupled name, so the driver holds the program, resolves
 * the recovered {@link ArrayAllocation#allocationTarget()} to a {@link Function}, and matches its name
 * in the demangled form Ghidra emits (its {@code .} namespace separator), i.e. {@code operator.new[]}.
 * The pass therefore assumes the demangler analyzer has run &mdash; which it has by the time a
 * function decompiles to a {@code HighFunction} in a fully-analyzed program.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: a call whose target resolves to no function, whose name is not
 * {@code operator new[]}, whose typed result is not a pointer to a modelled class, or whose byte size
 * is not a positive exact multiple of the element size is silently skipped (it contributes no hint),
 * never mis-rendered or raised as an error. A function with no recognised array construction yields an
 * empty list.
 *
 * <p><b>Scope: trivial-element arrays.</b> This slice renders the array allocation a
 * <em>trivial</em>-element {@code new C[n]} reduces to (allocation plus typed use). A non-trivial
 * element type additionally emits a per-element default-constructor loop; fusing that loop (to confirm
 * the element constructor and read its class from the ctor name) is a later cross-form refinement,
 * mirrored on the matcher (DD-0033).
 */
public final class CppArrayConstructionDriver {

	/**
	 * A rendered array-construction hint: the {@code site} address of the {@code CALL} it was recovered
	 * from, and the {@code rendering} string the {@link CppDecompilerHints} renderer produced.
	 *
	 * @param site the address of the dispatching allocation call op
	 * @param rendering the rendered C++ array-{@code new} expression
	 */
	public record RenderedArrayConstruction(Address site, String rendering) {}

	private final CppDecompilerHints renderer;
	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a driver over the given renderer and type-system model.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @param typeSystem the model resolving element classes to {@link CppClass}es; must not be null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public CppArrayConstructionDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
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
	 * Recognises every array-{@code new} allocation in the function and renders a hint for each one
	 * whose target resolves to {@code operator new[]}, whose typed result points at a modelled class,
	 * and whose byte size is a positive exact multiple of the element size.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedArrayConstruction> recognizeAndRender(HighFunction function) {
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
		List<RenderedArrayConstruction> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			ArrayAllocation alloc = CppArrayConstructionRecognizer.recognize(op);
			if (alloc == null) {
				continue;
			}
			RenderedArrayConstruction hint = render(op, alloc, functionManager);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedArrayConstruction render(PcodeOp callSite, ArrayAllocation alloc,
			FunctionManager functionManager) {
		Function callee = functionManager.getFunctionAt(alloc.allocationTarget());
		if (callee == null) {
			return null;
		}
		if (!isOperatorNewArray(callee.getName())) {
			return null;
		}
		DataType element = elementType(alloc.typedResult());
		if (element == null) {
			return null;
		}
		CppClass type = typeSystem.getCppClass(element.getName());
		if (type == null) {
			return null;
		}
		String countExpr = elementCount(alloc.byteSize(), element);
		if (countExpr == null) {
			return null;
		}
		String rendering = renderer.renderArrayConstruction(type, countExpr);
		return new RenderedArrayConstruction(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * {@return the element type the recovered typed result points at &mdash; one pointer level stripped
	 * off its {@link HighVariable}'s pointer data type &mdash; or null if it carries no
	 * {@link HighVariable} or a non-pointer type}
	 */
	private static DataType elementType(Varnode typedResult) {
		HighVariable high = typedResult.getHigh();
		if (high == null) {
			return null;
		}
		DataType dataType = high.getDataType();
		if (!(dataType instanceof Pointer)) {
			return null;
		}
		return ((Pointer) dataType).getDataType();
	}

	/**
	 * {@return the element-count expression &mdash; the total byte size divided by the element size
	 * &mdash; or null unless {@code byteSize} is a positive constant that is an exact multiple of the
	 * element size, so a non-constant or non-dividing size yields no fabricated or fractional count}
	 */
	private static String elementCount(Varnode byteSize, DataType element) {
		if (byteSize == null || !byteSize.isConstant()) {
			return null;
		}
		int elementSize = element.getLength();
		if (elementSize <= 0) {
			return null;
		}
		long totalBytes = byteSize.getOffset();
		if (totalBytes <= 0 || totalBytes % elementSize != 0) {
			return null;
		}
		return Long.toString(totalBytes / elementSize);
	}

	/**
	 * {@return whether the callee name denotes the array {@code operator new[]} &mdash; matched in the
	 * demangled form Ghidra emits (its {@code .} namespace separator), so {@code operator.new[]}
	 * normalises to and compares equal to {@code operator new[]}}
	 */
	private static boolean isOperatorNewArray(String calleeName) {
		if (calleeName == null) {
			return false;
		}
		return calleeName.replace('.', ' ').equals("operator new[]");
	}
}
