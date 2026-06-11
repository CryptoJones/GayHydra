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

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ghidra.app.util.scope.*;
import ghidra.app.util.scope.ScopeGraphRenamePropagator.Peer;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Rec 38 #38-4 (RFC-0002 minimal slice): the rename propagator over the
 * dataflow-populated scope graph — the same pass-through fixture
 * {@link ScopeGraphDataflowPopulatorTest} grounds, taken the rest of the way
 * to a live rename: f forwards its parameter to g, so propagating f's
 * parameter name must offer and rename g's parameter slot.
 */
public class ScopeGraphRenamePropagatorTest extends AbstractDecompilerHighFunctionTest {

	private static final String CALLER = "0x401000";
	private static final String CALLEE = "0x401100";

	private ProgramBuilder builder;
	private Program program;
	private Function caller;
	private Function callee;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("renamePropagation", ProgramBuilder._X64);
		builder.createMemory("text", CALLER, 0x200);
		// void f(int a) { g(a); }   f: call g ; ret      g: ret
		builder.setBytes(CALLER, "e8 fb 00 00 00 c3", false);
		builder.setBytes(CALLEE, "c3", false);
		program = builder.getProgram();
		String convention = program.getCompilerSpec().getDefaultCallingConvention().getName();
		callee = builder.createEmptyFunction("g", null, convention, CALLEE, 1,
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

	private ScopeGraph populatedGraph() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, caller);
		ScopeGraph graph = new ScopeGraph();
		ScopeGraphDataflowPopulator.populate(highFunction, graph);
		return graph;
	}

	@Test
	public void testForwardedParameterIsOfferedAsPeer() throws Exception {
		ScopeGraph graph = populatedGraph();

		List<Peer> peers =
			ScopeGraphRenamePropagator.findParameterPeers(graph, caller, 0);

		assertEquals("the forwarded parameter must surface exactly one peer", 1, peers.size());
		Peer peer = peers.get(0);
		assertEquals(callee, peer.function());
		assertEquals(0, peer.parameterIndex());
		assertEquals(callee.getParameter(0).getName(), peer.currentName());
	}

	@Test
	public void testApplyNameRenamesThePeerSlot() throws Exception {
		ScopeGraph graph = populatedGraph();
		List<Peer> peers =
			ScopeGraphRenamePropagator.findParameterPeers(graph, caller, 0);
		assertEquals(1, peers.size());

		int tx = program.startTransaction("propagate");
		int renamed;
		try {
			renamed = ScopeGraphRenamePropagator.applyName(peers, "inputBuf");
		}
		finally {
			program.endTransaction(tx, true);
		}

		assertEquals("the one peer must rename", 1, renamed);
		assertEquals("inputBuf", callee.getParameter(0).getName());
		// The origin slot is untouched — propagation goes outward only.
		assertNotEquals("inputBuf", caller.getParameter(0).getName());
	}

	@Test
	public void testNoPeersOnAnUnconnectedSlot() throws Exception {
		ScopeGraph graph = populatedGraph();

		// g's parameter has an edge back to f's, so walk from g: the component
		// is the same — but a fresh, unpopulated graph yields nothing.
		assertTrue(ScopeGraphRenamePropagator
				.findParameterPeers(new ScopeGraph(), caller, 0)
				.isEmpty());
		// And an out-of-range slot on the populated graph yields nothing.
		assertTrue(ScopeGraphRenamePropagator.findParameterPeers(graph, caller, 7).isEmpty());
	}
}
