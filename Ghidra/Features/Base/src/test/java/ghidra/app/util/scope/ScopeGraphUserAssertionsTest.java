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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import db.Transaction;
import generic.test.AbstractGenericTest;
import ghidra.app.util.scope.ScopeEdge.Kind;
import ghidra.app.util.scope.ScopeEdge.Origin;
import ghidra.app.util.scope.ScopeNode.GlobalAddress;
import ghidra.app.util.scope.ScopeNode.LocalEquiv;
import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.app.util.scope.ScopeNode.StructField;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;

/**
 * Headless coverage for the Rec 38 {@code #38-2b} {@link ScopeGraphUserAssertions} codec
 * (DD-0074): user-asserted edges round-trip through one versioned {@code ProgramUserData} string
 * property; everything else about the load path is never-wrong (fresh program, unknown version,
 * corrupt lines).
 */
public class ScopeGraphUserAssertionsTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private ProgramDB program;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("scopeAssertions", ProgramBuilder._X64);
		builder.createMemory("text", "0x401000", 0x1000);
		program = builder.getProgram();
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	private Address addr(long offset) {
		return program.getAddressFactory().getDefaultAddressSpace().getAddress(offset);
	}

	@Test
	public void testUserAssertionsRoundTripAcrossAllNodeKinds() {
		ScopeGraph graph = new ScopeGraph();
		ScopeEdge acrossFunctions = new ScopeEdge(new Parameter(addr(0x401000), 1),
			new LocalEquiv(addr(0x402000), "Stack[-0x8]:4"), Kind.SAME_VALUE, 1.0f, Origin.USER_ASSERTED);
		ScopeEdge globalToField = new ScopeEdge(new GlobalAddress(addr(0x5000)),
			new StructField("MyVec<int>", 8), Kind.ALIAS_OF, 0.75f, Origin.USER_ASSERTED);
		// An analysis edge must NOT persist -- it is recomputed by its producer.
		ScopeEdge analysisOnly = new ScopeEdge(new Parameter(addr(0x401000), 0),
			new GlobalAddress(addr(0x6000)), Kind.SAME_VALUE, 1.0f, Origin.STATIC);
		graph.addEdge(acrossFunctions);
		graph.addEdge(globalToField);
		graph.addEdge(analysisOnly);

		ScopeGraphUserAssertions.save(graph, program);
		ScopeGraph reloaded = new ScopeGraph();
		int loaded = ScopeGraphUserAssertions.load(program, reloaded);

		assertEquals("both user assertions must load", 2, loaded);
		assertTrue(reloaded.getEdges().contains(acrossFunctions));
		assertTrue("a structure name with template brackets must round-trip",
			reloaded.getEdges().contains(globalToField));
		assertEquals("the analysis edge must not have persisted", 2, reloaded.getEdges().size());
	}

	@Test
	public void testFreshProgramLoadsNothing() {
		// The RFC's forward-only migration: a program never saved to yields an empty set.
		ScopeGraph graph = new ScopeGraph();
		assertEquals(0, ScopeGraphUserAssertions.load(program, graph));
		assertTrue(graph.getEdges().isEmpty());
	}

	@Test
	public void testSaveReplacesThePreviousSet() {
		ScopeGraph first = new ScopeGraph();
		first.addEdge(new ScopeEdge(new Parameter(addr(0x401000), 0),
			new GlobalAddress(addr(0x5000)), Kind.SAME_VALUE, 1.0f, Origin.USER_ASSERTED));
		ScopeGraphUserAssertions.save(first, program);

		ScopeGraph second = new ScopeGraph();
		second.addEdge(new ScopeEdge(new Parameter(addr(0x401000), 1),
			new GlobalAddress(addr(0x6000)), Kind.SAME_VALUE, 1.0f, Origin.USER_ASSERTED));
		ScopeGraphUserAssertions.save(second, program);

		ScopeGraph reloaded = new ScopeGraph();
		assertEquals("save must replace, not append", 1,
			ScopeGraphUserAssertions.load(program, reloaded));
		assertEquals(second.getEdges(), reloaded.getEdges());
	}

	@Test
	public void testSavingNoAssertionsClearsTheProperty() {
		ScopeGraph withOne = new ScopeGraph();
		withOne.addEdge(new ScopeEdge(new Parameter(addr(0x401000), 0),
			new GlobalAddress(addr(0x5000)), Kind.SAME_VALUE, 1.0f, Origin.USER_ASSERTED));
		ScopeGraphUserAssertions.save(withOne, program);

		ScopeGraphUserAssertions.save(new ScopeGraph(), program);

		assertEquals(0, ScopeGraphUserAssertions.load(program, new ScopeGraph()));
		assertNull("the property must be removed outright", program.getProgramUserData()
				.getStringProperty(ScopeGraphUserAssertions.PROPERTY_NAME, null));
	}

	@Test
	public void testUnknownFormatVersionLoadsNothing() {
		setRawProperty("99\nSAME_VALUE|1.0|G:ram:5000|F:Packet:8");
		assertEquals("an unknown version must load nothing rather than guess", 0,
			ScopeGraphUserAssertions.load(program, new ScopeGraph()));
	}

	@Test
	public void testCorruptLinesAreSkippedNotFatal() {
		setRawProperty("1\n" +
			"SAME_VALUE|1.0|P:ram:401000:0|G:ram:5000\n" +   // good
			"SAME_VALUE|not-a-float|P:ram:401000:1|G:ram:5000\n" +  // bad confidence
			"NO_SUCH_KIND|1.0|P:ram:401000:2|G:ram:5000\n" +  // bad kind
			"SAME_VALUE|1.0|P:nosuchspace:401000:3|G:ram:5000\n" +  // unknown space
			"garbage");
		ScopeGraph graph = new ScopeGraph();

		assertEquals("only the well-formed line must load", 1,
			ScopeGraphUserAssertions.load(program, graph));
		assertEquals(1, graph.getEdges().size());
	}

	@Test
	public void testNullContracts() {
		ScopeGraph graph = new ScopeGraph();
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphUserAssertions.save(null, program));
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphUserAssertions.save(graph, null));
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphUserAssertions.load(null, graph));
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphUserAssertions.load(program, null));
	}

	private void setRawProperty(String value) {
		try (Transaction tx = program.getProgramUserData().openTransaction()) {
			program.getProgramUserData()
					.setStringProperty(ScopeGraphUserAssertions.PROPERTY_NAME, value);
		}
	}
}
