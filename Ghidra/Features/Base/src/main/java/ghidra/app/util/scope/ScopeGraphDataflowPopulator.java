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
package ghidra.app.util.scope;

import java.util.Iterator;

import ghidra.app.util.scope.ScopeEdge.Kind;
import ghidra.app.util.scope.ScopeEdge.Origin;
import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighSymbol;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;

/**
 * The dataflow population source of the Rec 38 scope graph (RFC-0002 {@code #38-3}, dataflow
 * slice 1): relates value-identities across functions by following call-site argument flow in a
 * decompiled {@link HighFunction} — RFC-0002's "local in function A passed as parameter to
 * function B &rarr; the local in A and B's parameter share a node".
 *
 * <p><b>This slice covers the deterministic end of the heuristic band: pass-through
 * parameters.</b> When a direct {@code CALL}'s argument is itself a <em>parameter of the
 * caller</em>, both endpoints are {@link Parameter} identities — caller slot and callee slot — and
 * the positional mapping comes from the decompiler's recovered call (inputs after the target map
 * to callee slots in order, the same recovery the Rec 37 argument threading uses). A local-variable
 * argument needs a stable local equivalence id ({@code LocalEquiv}) that no pass mints yet, so
 * locals are deferred rather than keyed off per-decompile {@code HighVariable} identity that would
 * not survive recomputation.
 *
 * <p><b>Confidence.</b> Edges carry {@link #PASS_THROUGH_CONFIDENCE} with origin
 * {@link Origin#DATAFLOW} — high (the prototype mapping is the decompiler's own) but deliberately
 * below the {@code 1.0} of static facts and user assertions, per the RFC's
 * behind-a-confidence-threshold posture for everything dataflow. Consumers choose their threshold.
 *
 * <p>Idempotent like every producer here: re-populating the same function re-adds nothing.
 */
public final class ScopeGraphDataflowPopulator {

	/**
	 * The confidence carried by a pass-through-parameter edge: the decompiler's own prototype
	 * mapping, but still a dataflow inference, not a stated fact.
	 */
	public static final float PASS_THROUGH_CONFIDENCE = 0.9f;

	private ScopeGraphDataflowPopulator() {
		// static populator utility
	}

	/**
	 * Adds a {@code SAME_VALUE} dataflow edge for every caller-parameter passed directly as an
	 * argument in a resolved direct call.
	 *
	 * @param function the decompiled function whose call sites to walk; must not be null
	 * @param graph the graph to populate; must not be null
	 * @return the number of edges newly added (0 when everything was already known or nothing
	 *         qualified)
	 */
	public static int populate(HighFunction function, ScopeGraph graph) {
		if (function == null) {
			throw new IllegalArgumentException("function must not be null");
		}
		if (graph == null) {
			throw new IllegalArgumentException("graph must not be null");
		}
		Address callerEntry = function.getFunction().getEntryPoint();
		int added = 0;
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			Function callee = resolveCallee(function, op);
			if (callee == null) {
				continue;
			}
			for (int i = 1; i < op.getNumInputs(); i++) {
				int callerSlot = parameterSlot(op.getInput(i));
				if (callerSlot < 0) {
					continue;
				}
				ScopeEdge edge = new ScopeEdge(new Parameter(callerEntry, callerSlot),
					new Parameter(callee.getEntryPoint(), i - 1), Kind.SAME_VALUE,
					PASS_THROUGH_CONFIDENCE, Origin.DATAFLOW);
				if (graph.addEdge(edge)) {
					added++;
				}
			}
		}
		return added;
	}

	// The called Function for a direct CALL, or null when the target address holds none (an
	// unresolved or external target contributes nothing -- never-wrong).
	private static Function resolveCallee(HighFunction function, PcodeOp call) {
		Varnode target = call.getInput(0);
		if (target == null || !target.isAddress()) {
			return null;
		}
		return function.getFunction()
				.getProgram()
				.getFunctionManager()
				.getFunctionAt(target.getAddress());
	}

	// The caller parameter slot the argument varnode carries, or -1 when the argument is not a
	// caller parameter (a local, a constant, a computed value -- all out of this slice's scope).
	private static int parameterSlot(Varnode argument) {
		if (argument == null) {
			return -1;
		}
		HighVariable high = argument.getHigh();
		if (high == null) {
			return -1;
		}
		HighSymbol symbol = high.getSymbol();
		if (symbol == null || !symbol.isParameter()) {
			return -1;
		}
		return symbol.getCategoryIndex();
	}
}
