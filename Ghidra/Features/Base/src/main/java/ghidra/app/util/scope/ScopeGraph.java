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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import ghidra.app.util.scope.ScopeEdge.Kind;
import ghidra.app.util.scope.ScopeEdge.Origin;

/**
 * The Rec 38 scope graph (RFC-0002 {@code #38-2a}): value-identities ({@link ScopeNode}) related by
 * {@link ScopeEdge}s, queried by rename propagation for "every place this value also appears".
 *
 * <p><b>Model-first, like Rec 37's {@code CppTypeSystem}.</b> RFC-0002 sketches a relational
 * {@code ScopeNode}/{@code ScopeEdge} table schema, but grounding against the program APIs shows no
 * public extension point for fork-owned tables ({@code ProgramUserData} exposes address-keyed
 * property maps, not tables). The graph therefore lives as this headless in-memory model: the
 * static and dataflow producers <em>recompute</em> their edges (the RFC already re-runs static
 * analysis on every change), and only {@link Origin#USER_ASSERTED} edges need durability — a
 * later persistence slice encodes exactly those through {@code ProgramUserData}
 * (see {@link #userAssertedEdges()}, its feed point).
 *
 * <p><b>Idempotent by construction.</b> Nodes and edges are value-semantic and deduplicated, so a
 * producer that runs repeatedly (an analyzer re-trigger) re-adds without duplicating — the lesson
 * the Rec 37 feeder re-learned (DD-0063) is baked in here from the start.
 *
 * <p>Not thread-safe; producers and consumers coordinate externally (the same posture as
 * {@code CppTypeSystem}).
 */
public final class ScopeGraph {

	private final Set<ScopeNode> nodes = new LinkedHashSet<>();
	private final Set<ScopeEdge> edges = new LinkedHashSet<>();
	private final Map<ScopeNode, Set<ScopeEdge>> edgesByNode = new HashMap<>();

	/**
	 * Adds a node with no relations yet (a value-identity known to exist). Adding an already-known
	 * node is a no-op.
	 *
	 * @param node the node; must not be null
	 * @return true if the node was new
	 */
	public boolean addNode(ScopeNode node) {
		if (node == null) {
			throw new IllegalArgumentException("node must not be null");
		}
		return nodes.add(node);
	}

	/**
	 * Adds an edge, registering both endpoints as nodes. Adding an edge equal to an existing one is
	 * a no-op (idempotent producers).
	 *
	 * @param edge the edge; must not be null
	 * @return true if the edge was new
	 */
	public boolean addEdge(ScopeEdge edge) {
		if (edge == null) {
			throw new IllegalArgumentException("edge must not be null");
		}
		if (!edges.add(edge)) {
			return false;
		}
		nodes.add(edge.source());
		nodes.add(edge.destination());
		edgesByNode.computeIfAbsent(edge.source(), n -> new LinkedHashSet<>()).add(edge);
		edgesByNode.computeIfAbsent(edge.destination(), n -> new LinkedHashSet<>()).add(edge);
		return true;
	}

	/**
	 * {@return an unmodifiable view of every known node}
	 */
	public Set<ScopeNode> getNodes() {
		return Collections.unmodifiableSet(nodes);
	}

	/**
	 * {@return an unmodifiable view of every known edge}
	 */
	public Set<ScopeEdge> getEdges() {
		return Collections.unmodifiableSet(edges);
	}

	/**
	 * {@return the edges touching the given node, in either direction (empty for an unknown node,
	 * never null)}
	 *
	 * @param node the node; must not be null
	 */
	public Set<ScopeEdge> getEdges(ScopeNode node) {
		if (node == null) {
			throw new IllegalArgumentException("node must not be null");
		}
		Set<ScopeEdge> touching = edgesByNode.get(node);
		return touching == null ? Set.of() : Collections.unmodifiableSet(touching);
	}

	/**
	 * {@return every node reachable from {@code start} over {@link Kind#SAME_VALUE} edges, walked
	 * undirected, including {@code start} itself — the set rename propagation offers the user}
	 *
	 * <p>Only {@code SAME_VALUE} edges connect the component: an {@code ALIAS_OF} or
	 * {@code DERIVED_FROM} neighbour holds a <em>related</em> value, not the same one, and renaming
	 * it from here would be wrong (never-wrong over complete, RFC-0002's propagation rule).
	 *
	 * @param start the node to start from; must not be null
	 */
	public Set<ScopeNode> sameValueComponent(ScopeNode start) {
		if (start == null) {
			throw new IllegalArgumentException("start node must not be null");
		}
		Set<ScopeNode> component = new LinkedHashSet<>();
		Deque<ScopeNode> pending = new ArrayDeque<>();
		component.add(start);
		pending.push(start);
		while (!pending.isEmpty()) {
			ScopeNode current = pending.pop();
			for (ScopeEdge edge : getEdges(current)) {
				if (edge.kind() != Kind.SAME_VALUE) {
					continue;
				}
				ScopeNode other =
					edge.source().equals(current) ? edge.destination() : edge.source();
				if (component.add(other)) {
					pending.push(other);
				}
			}
		}
		return component;
	}

	/**
	 * {@return the user-asserted edges, the durable subset a persistence pass encodes (analysis
	 * edges are recomputed by their producers)}
	 */
	public Set<ScopeEdge> userAssertedEdges() {
		Set<ScopeEdge> asserted = new LinkedHashSet<>();
		for (ScopeEdge edge : edges) {
			if (edge.origin() == Origin.USER_ASSERTED) {
				asserted.add(edge);
			}
		}
		return asserted;
	}
}
