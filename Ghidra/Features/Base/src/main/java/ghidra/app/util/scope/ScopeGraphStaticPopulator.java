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

import ghidra.app.util.scope.ScopeNode.GlobalAddress;
import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.app.util.scope.ScopeNode.StructField;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * The deterministic population source of the Rec 38 scope graph (RFC-0002 {@code #38-3}, first
 * slice): mints value-identity {@link ScopeNode}s from program facts that need no heuristics —
 * "same global address &rarr; same node; same parameter slot of the same function &rarr; same
 * node". The static source establishes <em>identity</em>; the cross-function {@code SAME_VALUE}
 * edges between identities are the dataflow source's and the user's to assert (RFC-0002's
 * population split), so this populator adds nodes only.
 *
 * <p><b>Scope.</b> Globals come from defined data units (one {@link GlobalAddress} per unit);
 * parameters come from function signatures (one {@link Parameter} per slot); struct fields come
 * from <em>references into</em> defined structure data ({@code #38-3b}) — a memory reference whose
 * destination falls inside a defined {@code Structure} unit resolves to the component containing
 * it, minting one {@link StructField} per {@code (structure, field offset)} actually evidenced by
 * the program, shared across every instance of the structure (field identity is type-level, the
 * RFC's "same struct field across loads"). Fields are not minted speculatively from type
 * definitions — only referenced ones exist. A reference to the structure's base address conflates
 * with field 0; that is acceptable for identity minting (no rename is wrongly propagated by an
 * identity's mere existence).
 *
 * <p><b>Recomputable and idempotent.</b> The RFC re-runs static analysis on every change; the
 * graph's deduplicating adds make repeated population a no-op, so this populator can run as often
 * as its caller likes (the same posture as the Rec 37 analyzers).
 */
public final class ScopeGraphStaticPopulator {

	private ScopeGraphStaticPopulator() {
		// static populator utility
	}

	/**
	 * Populates the graph from the program's deterministic facts, without cancellation support.
	 *
	 * @param program the program to walk; must not be null
	 * @param graph the graph to populate; must not be null
	 * @return the number of nodes newly added (0 when everything was already known)
	 */
	public static int populate(Program program, ScopeGraph graph) {
		try {
			return populate(program, graph, TaskMonitor.DUMMY);
		}
		catch (CancelledException e) {
			throw new AssertionError("the DUMMY monitor cannot be cancelled", e);
		}
	}

	/**
	 * Populates the graph from the program's deterministic facts, checking the monitor per
	 * function and per data unit.
	 *
	 * @param program the program to walk; must not be null
	 * @param graph the graph to populate; must not be null
	 * @param monitor the task monitor to poll for cancellation; must not be null
	 * @return the number of nodes newly added (0 when everything was already known)
	 * @throws CancelledException if the monitor is cancelled mid-walk
	 */
	public static int populate(Program program, ScopeGraph graph, TaskMonitor monitor)
			throws CancelledException {
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		if (graph == null) {
			throw new IllegalArgumentException("graph must not be null");
		}
		if (monitor == null) {
			throw new IllegalArgumentException("monitor must not be null");
		}
		int added = 0;
		FunctionIterator functions = program.getFunctionManager().getFunctions(true);
		while (functions.hasNext()) {
			monitor.checkCancelled();
			Function function = functions.next();
			int parameterCount = function.getParameterCount();
			for (int i = 0; i < parameterCount; i++) {
				if (graph.addNode(new Parameter(function.getEntryPoint(), i))) {
					added++;
				}
			}
		}
		DataIterator definedData = program.getListing().getDefinedData(true);
		while (definedData.hasNext()) {
			monitor.checkCancelled();
			Data data = definedData.next();
			if (graph.addNode(new GlobalAddress(data.getAddress()))) {
				added++;
			}
			if (data.getDataType() instanceof Structure structure) {
				added += mintReferencedFields(program, data, structure, graph, monitor);
			}
		}
		return added;
	}

	// Mints one StructField identity per (structure, field offset) the program references inside
	// this defined structure unit. The component CONTAINING the referenced address decides the
	// field offset, so a reference into a field's interior still resolves to the field.
	private static int mintReferencedFields(Program program, Data data, Structure structure,
			ScopeGraph graph, TaskMonitor monitor) throws CancelledException {
		int added = 0;
		AddressSet unitRange = new AddressSet(data.getMinAddress(), data.getMaxAddress());
		AddressIterator destinations =
			program.getReferenceManager().getReferenceDestinationIterator(unitRange, true);
		while (destinations.hasNext()) {
			monitor.checkCancelled();
			Address destination = destinations.next();
			int offsetInUnit = (int) destination.subtract(data.getMinAddress());
			Data component = data.getComponentContaining(offsetInUnit);
			if (component == null) {
				continue;
			}
			if (graph.addNode(new StructField(structure.getName(), component.getParentOffset()))) {
				added++;
			}
		}
		return added;
	}
}
