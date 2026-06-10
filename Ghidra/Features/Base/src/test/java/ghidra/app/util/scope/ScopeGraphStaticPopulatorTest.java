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
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.ByteDataType;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.VoidDataType;
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
