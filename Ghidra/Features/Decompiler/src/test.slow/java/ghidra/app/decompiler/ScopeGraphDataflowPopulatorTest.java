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
package ghidra.app.decompiler;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ghidra.app.util.scope.ScopeEdge;
import ghidra.app.util.scope.ScopeEdge.Kind;
import ghidra.app.util.scope.ScopeEdge.Origin;
import ghidra.app.util.scope.ScopeGraph;
import ghidra.app.util.scope.ScopeGraphDataflowPopulator;
import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Integration coverage for the Rec 38 dataflow populator's pass-through-parameter slice (DD-0075),
 * driven through the Rec 30 headless {@link AbstractDecompilerHighFunctionTest} harness. The
 * fixture is a caller that forwards its own parameter to a callee — RFC-0002's motivating case
 * with both endpoints deterministic.
 */
public class ScopeGraphDataflowPopulatorTest extends AbstractDecompilerHighFunctionTest {

	private static final String CALLER = "0x401000";
	private static final String CALLEE = "0x401100";

	private ProgramBuilder builder;
	private Program program;
	private Function caller;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("dataflowPassthrough", ProgramBuilder._X64);
		builder.createMemory("text", CALLER, 0x200);
		// void f(int a) { g(a); }  (win-x64: a arrives in ECX and is forwarded untouched)
		// f: call g ; ret        g: ret
		// The CppDeleteDriverTest two-function pattern: bytes without follow-flow disassembly,
		// callee created first, then explicit per-function disassembly.
		builder.setBytes(CALLER, "e8 fb 00 00 00 c3", false);
		builder.setBytes(CALLEE, "c3", false);
		program = builder.getProgram();
		String convention = program.getCompilerSpec().getDefaultCallingConvention().getName();
		builder.createEmptyFunction("g", null, convention, CALLEE, 1,
			VoidDataType.dataType, IntegerDataType.dataType);
		caller = builder.createEmptyFunction("f", null, convention, CALLER, 6,
			VoidDataType.dataType, IntegerDataType.dataType);
		builder.disassemble(CALLER, 6, false);
		builder.disassemble(CALLEE, 1, false);
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testPassThroughParameterRelatesCallerAndCalleeSlots() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, caller);

		ScopeGraph graph = new ScopeGraph();
		int added = ScopeGraphDataflowPopulator.populate(highFunction, graph);

		ScopeEdge expected = new ScopeEdge(
			new Parameter(caller.getEntryPoint(), 0),
			new Parameter(addr(program, 0x401100L), 0),
			Kind.SAME_VALUE, ScopeGraphDataflowPopulator.PASS_THROUGH_CONFIDENCE,
			Origin.DATAFLOW);
		assertEquals("the forwarded parameter must relate the two slots", 1, added);
		assertTrue(graph.getEdges().contains(expected));

		// The propagation set the UI will offer: renaming f's a reaches g's parameter.
		assertTrue(graph.sameValueComponent(new Parameter(caller.getEntryPoint(), 0))
				.contains(new Parameter(addr(program, 0x401100L), 0)));
	}

	@Test
	public void testRepopulationIsIdempotent() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, caller);
		ScopeGraph graph = new ScopeGraph();

		int first = ScopeGraphDataflowPopulator.populate(highFunction, graph);
		int second = ScopeGraphDataflowPopulator.populate(highFunction, graph);

		assertEquals(1, first);
		assertEquals("a re-run must add nothing", 0, second);
	}

	@Test
	public void testNullContracts() {
		assertThrows(IllegalArgumentException.class,
			() -> ScopeGraphDataflowPopulator.populate(null, new ScopeGraph()));
	}

	private static ghidra.program.model.address.Address addr(Program p, long offset) {
		return p.getAddressFactory().getDefaultAddressSpace().getAddress(offset);
	}
}
