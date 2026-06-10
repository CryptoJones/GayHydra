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

import generic.test.AbstractGenericTest;
import ghidra.app.util.scope.ScopeNode.GlobalAddress;
import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.app.util.scope.ScopeNode.StructField;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.ByteDataType;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Headless coverage for the Rec 38 {@code #38-3} {@link ScopeGraphStaticPopulator} (first slice):
 * deterministic identity nodes from function parameters and defined data, idempotent re-runs,
 * cancellation, and the contracts.
 */
public class ScopeGraphStaticPopulatorTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private ProgramDB program;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("staticPopulator", ProgramBuilder._X64);
		builder.createMemory("text", "0x401000", 0x1000);
		builder.createMemory("data", "0x500000", 0x1000);
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
	public void testMintsParameterAndGlobalIdentities() throws Exception {
		String convention =
			builder.getProgram().getCompilerSpec().getDefaultCallingConvention().getName();
		builder.createEmptyFunction("two", null, convention, "0x401000", 8,
			VoidDataType.dataType, IntegerDataType.dataType, IntegerDataType.dataType);
		builder.createEmptyFunction("none", null, convention, "0x401100", 8,
			VoidDataType.dataType);
		builder.applyDataType("0x500010", new IntegerDataType());
		program = builder.getProgram();
		ScopeGraph graph = new ScopeGraph();

		int added = ScopeGraphStaticPopulator.populate(program, graph);

		assertTrue("the two parameter slots must mint identities",
			graph.getNodes().contains(new Parameter(addr(0x401000), 0)));
		assertTrue(graph.getNodes().contains(new Parameter(addr(0x401000), 1)));
		assertFalse("a zero-parameter function mints no parameter identity",
			graph.getNodes().contains(new Parameter(addr(0x401100), 0)));
		assertTrue("the defined data unit must mint a global identity",
			graph.getNodes().contains(new GlobalAddress(addr(0x500010))));
		assertEquals("added must count exactly the minted nodes", graph.getNodes().size(), added);
		assertTrue("the static source mints identity only — no edges",
			graph.getEdges().isEmpty());
	}

	@Test
	public void testReferencedStructFieldsMintTypeLevelIdentities() throws Exception {
		// struct Packet { int a; int b; } at 0x500000, with references into both fields (one to
		// b's interior) and a second Packet instance referencing b — field identity is type-level,
		// so both instances share one StructField("Packet", 4).
		StructureDataType packet = new StructureDataType("Packet", 0);
		packet.add(IntegerDataType.dataType, 4, "a", null);
		packet.add(IntegerDataType.dataType, 4, "b", null);
		builder.applyDataType("0x500000", packet);
		builder.applyDataType("0x500100", packet);
		builder.createMemoryReference("0x401000", "0x500000", RefType.READ,
			SourceType.USER_DEFINED);
		builder.createMemoryReference("0x401004", "0x500006", RefType.READ,
			SourceType.USER_DEFINED);  // interior of b -> resolves to the containing field
		builder.createMemoryReference("0x401008", "0x500104", RefType.WRITE,
			SourceType.USER_DEFINED);  // second instance's b -> same type-level identity
		program = builder.getProgram();
		ScopeGraph graph = new ScopeGraph();

		ScopeGraphStaticPopulator.populate(program, graph);

		assertTrue("a base reference mints field 0",
			graph.getNodes().contains(new StructField("Packet", 0)));
		assertTrue("an interior reference resolves to its containing field",
			graph.getNodes().contains(new StructField("Packet", 4)));
		long packetFieldNodes = graph.getNodes().stream()
				.filter(n -> n instanceof StructField f && f.structureName().equals("Packet"))
				.count();
		assertEquals("both instances' b references share ONE type-level identity", 2,
			packetFieldNodes);
	}

	@Test
	public void testUnreferencedStructMintsNoFieldIdentities() throws Exception {
		// Fields are evidenced by references, not minted speculatively from the type definition.
		StructureDataType packet = new StructureDataType("Packet", 0);
		packet.add(IntegerDataType.dataType, 4, "a", null);
		builder.applyDataType("0x500000", packet);
		program = builder.getProgram();
		ScopeGraph graph = new ScopeGraph();

		ScopeGraphStaticPopulator.populate(program, graph);

		assertTrue("no references, no field identities", graph.getNodes().stream()
				.noneMatch(n -> n instanceof StructField));
		assertTrue("the unit itself still mints its global identity",
			graph.getNodes().contains(new GlobalAddress(addr(0x500000))));
	}

	@Test
	public void testRepopulationIsIdempotent() throws Exception {
		String convention =
			builder.getProgram().getCompilerSpec().getDefaultCallingConvention().getName();
		builder.createEmptyFunction("f", null, convention, "0x401000", 8,
			VoidDataType.dataType, IntegerDataType.dataType);
		builder.applyDataType("0x500000", new ByteDataType());
		program = builder.getProgram();
		ScopeGraph graph = new ScopeGraph();

		int first = ScopeGraphStaticPopulator.populate(program, graph);
		int second = ScopeGraphStaticPopulator.populate(program, graph);

		assertTrue(first > 0);
		assertEquals("a re-run must add nothing", 0, second);
	}

	@Test
	public void testEmptyProgramPopulatesNothing() throws Exception {
		program = builder.getProgram();
		ScopeGraph graph = new ScopeGraph();

		assertEquals(0, ScopeGraphStaticPopulator.populate(program, graph));
		assertTrue(graph.getNodes().isEmpty());
	}

	@Test
	public void testCancelledMonitorAbortsTheWalk() throws Exception {
		String convention =
			builder.getProgram().getCompilerSpec().getDefaultCallingConvention().getName();
		builder.createEmptyFunction("f", null, convention, "0x401000", 8,
			VoidDataType.dataType, IntegerDataType.dataType);
		program = builder.getProgram();
		TaskMonitorAdapter monitor = new TaskMonitorAdapter(true);
		monitor.cancel();

		ScopeGraph graph = new ScopeGraph();
		assertThrows(CancelledException.class,
			() -> ScopeGraphStaticPopulator.populate(program, graph, monitor));
		assertTrue("a cancelled walk must not have populated", graph.getNodes().isEmpty());
	}

	@Test
	public void testNullContracts() throws Exception {
		program = builder.getProgram();
		ScopeGraph graph = new ScopeGraph();
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphStaticPopulator.populate(null, graph));
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphStaticPopulator.populate(program, null));
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphStaticPopulator.populate(program, graph, (TaskMonitor) null));
	}
}
