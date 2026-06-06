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

import java.util.List;

/**
 * The C++ decompiler-hint renderer (RFC-0001 §5): a stateless producer of C++-style rendering
 * strings from <em>already-resolved</em> {@link CppTypeSystem} model facts plus the operand
 * expressions supplied by its caller.
 *
 * <p>Rec 37 {@code #37-7}, grounded by DD-0016. This is the headless half of RFC §5. It holds no
 * {@link ghidra.program.model.listing.Program}, no
 * {@link ghidra.program.model.data.DataTypeManager}, and no decompiler / {@code HighFunction}
 * reference; it never scans, demangles, parses, or mutates the model. Its inputs are model objects
 * (a {@link CppClass}, a vtable slot index) plus operand expressions — the receiver and arguments —
 * handed in as opaque strings by whatever drives it. Its output is the rendered string. The
 * decompiler-side pattern-recognition pass that walks a {@code HighFunction}, recognises the raw
 * C-style idiom, recovers the {@code (class, slot)} it denotes, and calls this renderer is the
 * deferred {@code #37-7b} Program-coupled wrapper, not part of this slice.
 *
 * <p>Hints are advisory and additive: this renderer only produces a string; it never rewrites
 * p-code or replaces an analysis pass.
 */
public final class CppDecompilerHints {

	/**
	 * Renders a virtual method call through a vtable slot as C++ source — {@code receiver->name(args)}
	 * for a pointer receiver, {@code receiver.name(args)} for a value receiver — using the
	 * name-resolved slot method the {@code CppVTableFeeder} ({@code #37-6}) and
	 * {@code CppVtableReconciler} ({@code #37-6c}) produced.
	 *
	 * <p>When the slot cannot be named — the class has no vtable, the index is out of range, or the
	 * slot method's name is blank/unresolved — the renderer falls back to a neutral indirect-call
	 * form {@code receiver->vtable[i](args)} rather than throwing or fabricating a name, so a caller
	 * driving this from a partially-recovered model still gets a well-formed rendering.
	 *
	 * @param owningClass the class whose vtable the call dispatches through; must not be null
	 * @param slotIndex the zero-based vtable slot index the call targets
	 * @param receiverExpr the already-rendered receiver expression; must not be null or blank
	 * @param receiverIsPointer true to render pointer access ({@code ->}), false for value access
	 *            ({@code .})
	 * @param argumentExprs the already-rendered argument expressions in call order; must not be null
	 *            and must contain no null element (may be empty for a no-argument call)
	 * @return the rendered C++ call expression
	 * @throws IllegalArgumentException if {@code owningClass} is null, {@code receiverExpr} is null or
	 *             blank, or {@code argumentExprs} is null or contains a null element
	 */
	public String renderVirtualCall(CppClass owningClass, int slotIndex, String receiverExpr,
			boolean receiverIsPointer, List<String> argumentExprs) {
		if (owningClass == null) {
			throw new IllegalArgumentException("owning class must not be null");
		}
		if (receiverExpr == null || receiverExpr.isBlank()) {
			throw new IllegalArgumentException("receiver expression must not be null or blank");
		}
		if (argumentExprs == null) {
			throw new IllegalArgumentException("argument expression list must not be null");
		}
		String access = receiverIsPointer ? "->" : ".";
		String args = renderArguments(argumentExprs);
		String slotName = resolveSlotName(owningClass, slotIndex);
		if (slotName == null) {
			return receiverExpr + access + "vtable[" + slotIndex + "](" + args + ")";
		}
		return receiverExpr + access + slotName + "(" + args + ")";
	}

	/**
	 * {@return the name of the method occupying the given slot, or null if the slot cannot be named}
	 */
	private static String resolveSlotName(CppClass owningClass, int slotIndex) {
		CppVTable vtable = owningClass.getVtable();
		if (vtable == null || slotIndex < 0 || slotIndex >= vtable.getSlotCount()) {
			return null;
		}
		String name = vtable.getSlot(slotIndex).getName();
		if (name == null || name.isBlank()) {
			return null;
		}
		return name;
	}

	private static String renderArguments(List<String> argumentExprs) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < argumentExprs.size(); i++) {
			String arg = argumentExprs.get(i);
			if (arg == null) {
				throw new IllegalArgumentException("argument expression must not be null");
			}
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(arg);
		}
		return sb.toString();
	}
}
