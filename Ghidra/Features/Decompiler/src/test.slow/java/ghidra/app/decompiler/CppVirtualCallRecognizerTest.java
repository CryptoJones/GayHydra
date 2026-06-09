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

import java.util.Iterator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ghidra.app.util.cpp.CppVirtualCallRecognizer;
import ghidra.app.util.cpp.CppVirtualCallRecognizer.VirtualDispatch;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;

/**
 * Integration coverage for the Rec 37 {@code #37-7b-1}
 * {@link CppVirtualCallRecognizer} p-code matcher, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0024).
 *
 * <p>The fixture is a hand-assembled x86-64 function carrying the canonical vtable-dispatch idiom
 * &mdash; {@code mov rax,[rdi]; call qword [rax+8]; ret}, i.e. {@code this->vtable[1](...)} &mdash;
 * so the recogniser is asserted against the p-code the <em>real</em> decompiler emits, not a
 * hand-built syntax tree. The single virtual call decompiles to slot index 1 (offset {@code 0x8}
 * over an 8-byte pointer) with the receiver in {@code RDI}.
 */
public class CppVirtualCallRecognizerTest extends AbstractDecompilerHighFunctionTest {

	private static final String ENTRY = "0x401000";

	private ProgramBuilder builder;
	private Program program;
	private Function function;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("vcall", ProgramBuilder._X64);
		builder.createMemory("text", ENTRY, 0x100);
		// void f(C* this):  mov rax,[rdi] ; call qword [rax+8] ; ret
		builder.setBytes(ENTRY, "48 8b 07 ff 50 08 c3", true);
		function = builder.createFunction(ENTRY);
		program = builder.getProgram();
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRecognizesVirtualDispatchSlotAndReceiver() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, function);

		VirtualDispatch dispatch = recognizeSoleCallInd(highFunction);
		assertNotNull("recogniser declined a genuine vtable dispatch", dispatch);
		assertEquals("wrong vtable slot index recovered", 1, dispatch.slotIndex());
		assertNotNull("no receiver varnode recovered", dispatch.receiver());
		assertTrue("receiver should be the RDI register holding this",
			dispatch.receiver().isRegister());
	}

	@Test
	public void testDeclinesNonCallIndOps() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, function);

		// Every op that is not the CALLIND must be declined (null), never mis-recognised.
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		int nonCallInd = 0;
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALLIND) {
				continue;
			}
			nonCallInd++;
			assertNull("recogniser matched a non-CALLIND op: " + op.getMnemonic(),
				CppVirtualCallRecognizer.recognize(op));
		}
		assertTrue("fixture produced no non-CALLIND ops to decline", nonCallInd > 0);
	}

	@Test
	public void testRecognizeNullIsSafe() {
		assertNull("null call site must be declined, not throw",
			CppVirtualCallRecognizer.recognize(null));
	}

	private static VirtualDispatch recognizeSoleCallInd(HighFunction highFunction) {
		VirtualDispatch found = null;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALLIND) {
				assertNull("fixture unexpectedly had more than one CALLIND", found);
				found = CppVirtualCallRecognizer.recognize(op);
			}
		}
		return found;
	}
}
