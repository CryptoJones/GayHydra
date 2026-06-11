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
package ghidra.app.plugin.core.decompile.actions;

import java.util.ArrayList;
import java.util.List;

import docking.action.MenuData;
import docking.widgets.OptionDialog;
import ghidra.app.decompiler.ClangFieldToken;
import ghidra.app.decompiler.ClangToken;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.plugin.core.decompile.DecompilerActionContext;
import ghidra.app.util.HelpTopics;
import ghidra.app.util.scope.ScopeGraph;
import ghidra.app.util.scope.ScopeGraphDataflowPopulator;
import ghidra.app.util.scope.ScopeGraphRenamePropagator;
import ghidra.app.util.scope.ScopeGraphRenamePropagator.Peer;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighSymbol;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.HelpLocation;
import ghidra.util.Msg;
import ghidra.util.UndefinedFunction;
import ghidra.util.task.TaskLauncher;

/**
 * Rec 38 #38-4 (RFC-0002, minimal slice): "Propagate Name to Same-Value
 * Peers" — the scope graph's first production consumer.
 *
 * <p>Deliberately an <i>explicit</i> action on a parameter token, not an
 * interception of the rename flow: the user already named this parameter
 * (with the ordinary rename action); this propagates that name to the
 * {@code SAME_VALUE} parameter peers the dataflow populator can prove from
 * the function and its direct callers, behind the RFC's confirmation dialog.
 * The automatic-on-rename hook layers on top later; an explicit verb cannot
 * surprise anyone — never-wrong UX.
 */
public class PropagateNameAction extends AbstractDecompilerAction {

	public PropagateNameAction() {
		super("Propagate Name to Same-Value Peers");
		setHelpLocation(new HelpLocation(HelpTopics.DECOMPILER, "ActionRenameVariable"));
		setPopupMenuData(
			new MenuData(new String[] { "Propagate Name to Same-Value Peers" }, "Decompile"));
	}

	@Override
	protected boolean isEnabledForDecompilerContext(DecompilerActionContext context) {
		return getParameterSymbol(context) != null;
	}

	@Override
	protected void decompilerActionPerformed(DecompilerActionContext context) {
		HighSymbol highSymbol = getParameterSymbol(context);
		if (highSymbol == null) {
			return;
		}
		Function function = context.getFunction();
		int parameterIndex = highSymbol.getCategoryIndex();
		String name = highSymbol.getName();

		TaskLauncher.launchModal("Finding same-value peers", monitor -> {
			ScopeGraph graph = new ScopeGraph();
			DecompInterface decompiler = new DecompInterface();
			try {
				Program program = context.getProgram();
				if (!decompiler.openProgram(program)) {
					Msg.showError(this, null, "Propagate Name",
						"Decompiler failed to open: " + decompiler.getLastMessage());
					return;
				}
				// The pass-through edges are minted while walking each CALLER,
				// so populate from this function (its outgoing args) and from
				// every direct caller (their args into this function).
				monitor.setMessage("Decompiling " + function.getName());
				populateFrom(decompiler, function, graph);
				for (Function caller : directCallers(function)) {
					monitor.checkCancelled();
					monitor.setMessage("Decompiling caller " + caller.getName());
					populateFrom(decompiler, caller, graph);
				}
			}
			catch (Exception e) {
				Msg.showError(this, null, "Propagate Name", "Peer search failed: " + e, e);
				return;
			}
			finally {
				decompiler.dispose();
			}

			List<Peer> peers =
				ScopeGraphRenamePropagator.findParameterPeers(graph, function, parameterIndex);
			if (peers.isEmpty()) {
				Msg.showInfo(this, null, "Propagate Name",
					"No same-value parameter peers found for '" + name + "'.");
				return;
			}
			offerAndApply(context, peers, name);
		});
	}

	private static void populateFrom(DecompInterface decompiler, Function function,
			ScopeGraph graph) {
		DecompileResults results = decompiler.decompileFunction(function, 30, null);
		HighFunction high = results.getHighFunction();
		if (high != null) {
			ScopeGraphDataflowPopulator.populate(high, graph);
		}
	}

	private static List<Function> directCallers(Function function) {
		Program program = function.getProgram();
		ReferenceManager refs = program.getReferenceManager();
		List<Function> callers = new ArrayList<>();
		for (Reference ref : refs.getReferencesTo(function.getEntryPoint())) {
			if (!ref.getReferenceType().isCall()) {
				continue;
			}
			Address from = ref.getFromAddress();
			Function caller = program.getFunctionManager().getFunctionContaining(from);
			if (caller != null && !callers.contains(caller) && !caller.equals(function)) {
				callers.add(caller);
			}
		}
		return callers;
	}

	private void offerAndApply(DecompilerActionContext context, List<Peer> peers, String name) {
		StringBuilder body = new StringBuilder();
		body.append("Apply the name '").append(name).append("' to ").append(peers.size())
				.append(" same-value parameter peer(s)?\n\n");
		for (Peer peer : peers) {
			body.append(peer.function().getName()).append(" — parameter #")
					.append(peer.parameterIndex() + 1).append(" (currently '")
					.append(peer.currentName()).append("')\n");
		}
		int choice = OptionDialog.showYesNoDialog(
			context.getComponentProvider().getComponent(), "Propagate Name", body.toString());
		if (choice != OptionDialog.YES_OPTION) {
			return;
		}
		Program program = context.getProgram();
		int tx = program.startTransaction("Propagate Name to Same-Value Peers");
		int renamed;
		try {
			renamed = ScopeGraphRenamePropagator.applyName(peers, name);
		}
		finally {
			program.endTransaction(tx, true);
		}
		context.getTool().setStatusInfo(
			"Propagated '" + name + "' to " + renamed + " of " + peers.size() + " peer(s)");
	}

	/**
	 * {@return the parameter HighSymbol under the cursor, or null} — mirrors
	 * the rename actions' token resolution, narrowed to parameters (the
	 * minimal #38-4 surface; locals need a {@code LocalEquiv} rename path).
	 */
	private static HighSymbol getParameterSymbol(DecompilerActionContext context) {
		Function function = context.getFunction();
		if (function == null || function instanceof UndefinedFunction) {
			return null;
		}
		ClangToken tokenAtCursor = context.getTokenAtCursor();
		if (tokenAtCursor == null || tokenAtCursor instanceof ClangFieldToken) {
			return null;
		}
		if (context.getHighFunction() == null) {
			return null;
		}
		HighSymbol highSymbol = tokenAtCursor.getHighSymbol(context.getHighFunction());
		if (highSymbol == null || !highSymbol.isParameter()) {
			return null;
		}
		return highSymbol;
	}
}
