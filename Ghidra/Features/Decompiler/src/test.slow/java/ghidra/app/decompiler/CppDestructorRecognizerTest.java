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

import ghidra.app.util.cpp.CppDestructorRecognizer;
import ghidra.app.util.cpp.CppDestructorRecognizer.DestructorCall;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;

/**
 * Integration coverage for the Rec 37 {@code #37-9c-b-1}
 * {@link CppDestructorRecognizer} p-code matcher, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0028).
 *
 * <p>The fixture is a hand-assembled x86-64 (Windows-ABI) method {@code f(C* p)} whose body is a
 * single direct {@code call ~C} that forwards its pointer parameter (in {@code RCX}, the x64
 * first-argument register) to a {@code void ~C(C*)} destructor callee. Because the destructor takes
 * its {@code this} as a typed {@code C *} (no {@code void *} cast, unlike a {@code delete}), the
 * decompiler recovers the receiver as parameter {@code param_1} directly; the matcher recovers the
 * call-target address (resolving to the {@code ~C} function) and that receiver. Whether the callee is
 * actually a destructor (and of which class) is the {@code #37-9c-b-2} driver's concern, not the
 * matcher's.
 */
public class CppDestructorRecognizerTest extends AbstractDecompilerHighFunctionTest {

	private static final String CALLER = "0x401000";
	private static final String DTOR = "0x401010";

	private ProgramBuilder builder;
	private Program program;
	private Function caller;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("dtor", ProgramBuilder._X64);
		builder.createMemory("text", CALLER, 0x100);
		// f(C* p): call ~C ; ret   (p forwarded in RCX) — E8 rel32 -> 0x401010 ; C3
		builder.setBytes(CALLER, "e8 0b 00 00 00 c3", false);
		builder.setBytes(DTOR, "c3", false);

		StructureDataType classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create both functions before disassembly so auto-analysis finds them and does not
		// auto-create an overlapping function at the call target.
		builder.createEmptyFunction("~C", null, conv, DTOR, 1, VoidDataType.dataType, classCPtr);
		caller = builder.createEmptyFunction("f", null, conv, CALLER, 6, VoidDataType.dataType,
			classCPtr);
		builder.disassemble(CALLER, 6, false);
		builder.disassemble(DTOR, 1, false);
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRecognizesDestructorCallTargetAndReceiver() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, caller);

		DestructorCall dtor = recognizeSoleCall(highFunction);
		assertNotNull("matcher declined a genuine direct call with a receiver", dtor);
		assertNotNull("no call-target address recovered", dtor.callTarget());
		Function target = program.getFunctionManager().getFunctionAt(dtor.callTarget());
		assertNotNull("recovered call target resolved to no function", target);
		assertEquals("recovered call target is the wrong function", "~C", target.getName());
		assertNotNull("no receiver varnode recovered", dtor.receiver());
		assertNotNull("receiver varnode carried no HighVariable", dtor.receiver().getHigh());
		assertEquals("receiver should be the this pointer param_1", "param_1",
			dtor.receiver().getHigh().getName());
	}

	@Test
	public void testDeclinesNonCallOps() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, caller);

		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		int nonCall = 0;
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL) {
				continue;
			}
			nonCall++;
			assertNull("matcher matched a non-CALL op: " + op.getMnemonic(),
				CppDestructorRecognizer.recognize(op));
		}
		assertTrue("fixture produced no non-CALL ops to decline", nonCall > 0);
	}

	@Test
	public void testRecognizeNullIsSafe() {
		assertNull("null call site must be declined, not throw",
			CppDestructorRecognizer.recognize(null));
	}

	private static DestructorCall recognizeSoleCall(HighFunction highFunction) {
		DestructorCall found = null;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL) {
				assertNull("fixture unexpectedly had more than one CALL", found);
				found = CppDestructorRecognizer.recognize(op);
			}
		}
		return found;
	}
}
