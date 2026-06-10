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

/**
 * One relation between two value-identities in the Rec 38 scope graph (RFC-0002 {@code #38-2}).
 * Value-semantic, like the nodes: two edges with the same endpoints, kind, confidence, and source
 * are the same edge, and {@link ScopeGraph} deduplicates them.
 *
 * @param source the originating node; must not be null
 * @param destination the related node; must not be null and must differ from {@code source}
 * @param kind the relation kind; must not be null
 * @param confidence how sure the producer is, in {@code [0, 1]} (1 for static facts and user
 *            assertions; below 1 for dataflow heuristics)
 * @param origin which producer asserted the relation; must not be null
 */
public record ScopeEdge(ScopeNode source, ScopeNode destination, Kind kind, float confidence,
		Origin origin) {

	/**
	 * The relation an edge asserts between its two nodes (RFC-0002's {@code EdgeKind}).
	 */
	public enum Kind {
		/** The two nodes hold the same value; rename propagation walks these. */
		SAME_VALUE,
		/** One node's value can be reached from the other by aliasing. */
		ALIAS_OF,
		/** One node's value is computed from the other (offset, cast, …). */
		DERIVED_FROM
	}

	/**
	 * Which producer asserted the edge (RFC-0002's {@code source} column). A user assertion
	 * overrides automatic edges and is the durable part of the graph; static and dataflow edges are
	 * recomputed by their analyses.
	 */
	public enum Origin {
		STATIC,
		DATAFLOW,
		USER_ASSERTED
	}

	public ScopeEdge {
		if (source == null) {
			throw new IllegalArgumentException("source node must not be null");
		}
		if (destination == null) {
			throw new IllegalArgumentException("destination node must not be null");
		}
		if (source.equals(destination)) {
			throw new IllegalArgumentException("an edge must relate two distinct nodes");
		}
		if (kind == null) {
			throw new IllegalArgumentException("edge kind must not be null");
		}
		if (confidence < 0.0f || confidence > 1.0f || Float.isNaN(confidence)) {
			throw new IllegalArgumentException("confidence must be in [0, 1]");
		}
		if (origin == null) {
			throw new IllegalArgumentException("edge origin must not be null");
		}
	}
}
