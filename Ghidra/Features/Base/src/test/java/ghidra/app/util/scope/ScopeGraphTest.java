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

import static org.junit.Assert.*;

import java.util.Set;

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.app.util.scope.ScopeEdge.Kind;
import ghidra.app.util.scope.ScopeEdge.Origin;
import ghidra.app.util.scope.ScopeNode.GlobalAddress;
import ghidra.app.util.scope.ScopeNode.LocalEquiv;
import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.app.util.scope.ScopeNode.StructField;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.address.GenericAddressSpace;

/**
 * Headless coverage for the Rec 38 {@code #38-2a} {@link ScopeGraph} model (RFC-0002, DD-0074):
 * value-semantic nodes and edges, idempotent producers, and the {@code SAME_VALUE} component walk
 * rename propagation is built on.
 */
public class ScopeGraphTest extends AbstractGenericTest {

	private final AddressSpace space =
		new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 0);

	private Address addr(long offset) {
		return space.getAddress(offset);
	}

	@Test
	public void testNodesAreValueSemantic() {
		assertEquals(new GlobalAddress(addr(0x1000)), new GlobalAddress(addr(0x1000)));
		assertEquals(new StructField("Packet", 8), new StructField("Packet", 8));
		assertEquals(new Parameter(addr(0x401000), 1), new Parameter(addr(0x401000), 1));
		assertEquals(new LocalEquiv(addr(0x401000), "Stack[-0x8]:4"), new LocalEquiv(addr(0x401000), "Stack[-0x8]:4"));
		assertNotEquals(new Parameter(addr(0x401000), 1), new Parameter(addr(0x401000), 2));
		assertNotEquals(new StructField("Packet", 8), new StructField("Packet", 12));
	}

	@Test
	public void testRepeatedProducersAreIdempotent() {
		// A producer that runs repeatedly (an analyzer re-trigger) must not duplicate — the Rec 37
		// feeder lesson (DD-0063), baked in from the start here.
		ScopeGraph graph = new ScopeGraph();
		ScopeEdge edge = new ScopeEdge(new Parameter(addr(0x401000), 0),
			new GlobalAddress(addr(0x5000)), Kind.SAME_VALUE, 1.0f, Origin.STATIC);

		assertTrue(graph.addEdge(edge));
		assertFalse("re-adding an equal edge must be a no-op", graph.addEdge(new ScopeEdge(
			new Parameter(addr(0x401000), 0), new GlobalAddress(addr(0x5000)), Kind.SAME_VALUE,
			1.0f, Origin.STATIC)));

		assertEquals(1, graph.getEdges().size());
		assertEquals(2, graph.getNodes().size());
	}

	@Test
	public void testAddEdgeRegistersBothEndpointsAsNodes() {
		ScopeGraph graph = new ScopeGraph();
		Parameter param = new Parameter(addr(0x401000), 0);
		LocalEquiv local = new LocalEquiv(addr(0x402000), "Stack[-0x10]:8");

		graph.addEdge(new ScopeEdge(param, local, Kind.SAME_VALUE, 0.8f, Origin.DATAFLOW));

		assertTrue(graph.getNodes().contains(param));
		assertTrue(graph.getNodes().contains(local));
		assertEquals(1, graph.getEdges(param).size());
		assertEquals(1, graph.getEdges(local).size());
	}

	@Test
	public void testSameValueComponentWalksTransitivelyAndUndirected() {
		// a —SAME— b —SAME— c, with the b—c edge pointing c→b to prove the walk is undirected.
		ScopeGraph graph = new ScopeGraph();
		Parameter a = new Parameter(addr(0x401000), 0);
		LocalEquiv b = new LocalEquiv(addr(0x402000), "EDI:4");
		Parameter c = new Parameter(addr(0x403000), 2);
		graph.addEdge(new ScopeEdge(a, b, Kind.SAME_VALUE, 1.0f, Origin.STATIC));
		graph.addEdge(new ScopeEdge(c, b, Kind.SAME_VALUE, 0.9f, Origin.DATAFLOW));

		Set<ScopeNode> component = graph.sameValueComponent(a);

		assertEquals(Set.of(a, b, c), component);
	}

	@Test
	public void testSameValueComponentExcludesOtherEdgeKinds() {
		// An ALIAS_OF or DERIVED_FROM neighbour holds a related value, not the same one — renaming
		// it would be wrong, so the component must not cross those edges (never-wrong).
		ScopeGraph graph = new ScopeGraph();
		Parameter a = new Parameter(addr(0x401000), 0);
		LocalEquiv same = new LocalEquiv(addr(0x402000), "EDI:4");
		GlobalAddress aliased = new GlobalAddress(addr(0x5000));
		StructField derived = new StructField("Packet", 8);
		graph.addEdge(new ScopeEdge(a, same, Kind.SAME_VALUE, 1.0f, Origin.STATIC));
		graph.addEdge(new ScopeEdge(a, aliased, Kind.ALIAS_OF, 1.0f, Origin.STATIC));
		graph.addEdge(new ScopeEdge(same, derived, Kind.DERIVED_FROM, 1.0f, Origin.STATIC));

		assertEquals(Set.of(a, same), graph.sameValueComponent(a));
	}

	@Test
	public void testSameValueComponentOfIsolatedNodeIsItself() {
		ScopeGraph graph = new ScopeGraph();
		Parameter lone = new Parameter(addr(0x401000), 0);
		graph.addNode(lone);

		assertEquals(Set.of(lone), graph.sameValueComponent(lone));
	}

	@Test
	public void testUserAssertedEdgesAreTheDurableSubset() {
		ScopeGraph graph = new ScopeGraph();
		ScopeEdge analysis = new ScopeEdge(new Parameter(addr(0x401000), 0),
			new LocalEquiv(addr(0x402000), "EDI:4"), Kind.SAME_VALUE, 1.0f, Origin.STATIC);
		ScopeEdge asserted = new ScopeEdge(new Parameter(addr(0x401000), 0),
			new GlobalAddress(addr(0x5000)), Kind.SAME_VALUE, 1.0f, Origin.USER_ASSERTED);
		graph.addEdge(analysis);
		graph.addEdge(asserted);

		assertEquals(Set.of(asserted), graph.userAssertedEdges());
	}

	@Test
	public void testEdgeContractsRejectBadValues() {
		Parameter a = new Parameter(addr(0x401000), 0);
		LocalEquiv b = new LocalEquiv(addr(0x402000), "EDI:4");
		assertThrows(IllegalArgumentException.class,
			() -> new ScopeEdge(a, a, Kind.SAME_VALUE, 1.0f, Origin.STATIC));
		assertThrows(IllegalArgumentException.class,
			() -> new ScopeEdge(a, b, Kind.SAME_VALUE, 1.5f, Origin.STATIC));
		assertThrows(IllegalArgumentException.class,
			() -> new ScopeEdge(a, b, Kind.SAME_VALUE, Float.NaN, Origin.STATIC));
		assertThrows(IllegalArgumentException.class,
			() -> new ScopeEdge(null, b, Kind.SAME_VALUE, 1.0f, Origin.STATIC));
	}

	@Test
	public void testNodeContractsRejectBadValues() {
		assertThrows(IllegalArgumentException.class, () -> new GlobalAddress(null));
		assertThrows(IllegalArgumentException.class, () -> new StructField(" ", 0));
		assertThrows(IllegalArgumentException.class, () -> new StructField("Packet", -1));
		assertThrows(IllegalArgumentException.class, () -> new Parameter(addr(0x401000), -1));
		assertThrows(IllegalArgumentException.class, () -> new LocalEquiv(addr(0x401000), " "));
	}

	@Test
	public void testGraphContractsRejectNulls() {
		ScopeGraph graph = new ScopeGraph();
		assertThrows(IllegalArgumentException.class, () -> graph.addNode(null));
		assertThrows(IllegalArgumentException.class, () -> graph.addEdge(null));
		assertThrows(IllegalArgumentException.class, () -> graph.getEdges(null));
		assertThrows(IllegalArgumentException.class, () -> graph.sameValueComponent(null));
	}
}
