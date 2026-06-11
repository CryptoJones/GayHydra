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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

/**
 * Rec 38 #38-4 (RFC-0002 "Rename propagation", minimal slice): resolves the
 * {@code SAME_VALUE} component of a function parameter into the live
 * parameters it names, and applies a name across them.
 *
 * <p>The scope graph's first production consumer. Deliberately headless and
 * graph-in: the caller populates the graph (static + dataflow populators over
 * whatever decompile scope it can afford — the GUI action uses the renamed
 * function plus its direct callers) and owns the transaction around
 * {@link #applyName}. Minimal-slice boundaries: only {@code Parameter} peers
 * are offered (a {@code LocalEquiv} peer has no rename surface without its
 * decompile context — a later slice); a peer whose rename collides is
 * skipped, not failed — never-wrong.
 */
public final class ScopeGraphRenamePropagator {

	/**
	 * One renameable peer: the live function, the parameter ordinal, and the
	 * name it currently carries (for the confirmation dialog).
	 */
	public record Peer(Function function, int parameterIndex, String currentName) {}

	private ScopeGraphRenamePropagator() {
		// static utility
	}

	/**
	 * Walks the {@code SAME_VALUE} component of the given parameter slot and
	 * resolves every <i>other</i> {@code Parameter} node to its live function
	 * parameter. Nodes naming a function or ordinal that no longer exists
	 * resolve to nothing (the graph is advisory; the program is the truth).
	 *
	 * @param graph the populated scope graph
	 * @param function the function whose parameter is being propagated from
	 * @param parameterIndex the parameter ordinal in that function
	 * @return the peers, ordered by function entry then ordinal (possibly
	 *         empty, never null)
	 */
	public static List<Peer> findParameterPeers(ScopeGraph graph, Function function,
			int parameterIndex) {
		if (graph == null || function == null) {
			throw new IllegalArgumentException("graph and function must not be null");
		}
		Parameter start = new Parameter(function.getEntryPoint(), parameterIndex);
		Set<ScopeNode> component = graph.sameValueComponent(start);
		List<Peer> peers = new ArrayList<>();
		for (ScopeNode node : component) {
			if (!(node instanceof Parameter p) || p.equals(start)) {
				continue;
			}
			Function peerFunction = function.getProgram()
					.getFunctionManager()
					.getFunctionAt(p.functionEntry());
			if (peerFunction == null) {
				continue;
			}
			ghidra.program.model.listing.Parameter live =
				peerFunction.getParameter(p.parameterIndex());
			if (live == null) {
				continue;
			}
			peers.add(new Peer(peerFunction, p.parameterIndex(), live.getName()));
		}
		peers.sort(Comparator
				.comparing((Peer peer) -> peer.function().getEntryPoint())
				.thenComparingInt(Peer::parameterIndex));
		return peers;
	}

	/**
	 * Applies the name to each peer as {@link SourceType#USER_DEFINED}. A peer
	 * whose rename collides ({@link DuplicateNameException}) or is rejected
	 * ({@link InvalidInputException}) is skipped — the rest still apply. The
	 * caller owns the surrounding program transaction.
	 *
	 * @param peers the peers to rename
	 * @param name the name to apply
	 * @return how many peers were actually renamed
	 */
	public static int applyName(List<Peer> peers, String name) {
		if (peers == null || name == null || name.isBlank()) {
			throw new IllegalArgumentException("peers and a non-blank name are required");
		}
		int renamed = 0;
		for (Peer peer : peers) {
			ghidra.program.model.listing.Parameter live =
				peer.function().getParameter(peer.parameterIndex());
			if (live == null) {
				continue;
			}
			try {
				live.setName(name, SourceType.USER_DEFINED);
				renamed++;
			}
			catch (DuplicateNameException | InvalidInputException e) {
				// skip this peer; never-wrong over all-or-nothing
			}
		}
		return renamed;
	}
}
