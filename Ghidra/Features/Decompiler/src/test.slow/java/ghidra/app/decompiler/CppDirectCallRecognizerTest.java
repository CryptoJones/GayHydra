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
import org.junit.Test;

import ghidra.app.util.cpp.CppDirectCallRecognizer;
import ghidra.app.util.cpp.CppDirectCallRecognizer.DirectCall;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.Undefined1DataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;

/**
 * Integration coverage for the shared Rec 37 {@link CppDirectCallRecognizer} direct-call recovery,
 * driven through the Rec 30 headless {@link AbstractDecompilerHighFunctionTest} harness (DD-0023,
 * DD-0032). This consolidates the recovery coverage previously split across the delete (DD-0026) and
 * destructor (DD-0028) per-form recognizer tests, which the rule-of-three extraction (DD-0032)
 * folded into this one matcher; the per-form <em>semantics</em> stay covered by the form drivers'
 * own tests ({@code CppDeleteDriverTest}, {@code CppDestructorDriverTest}, {@code CppConstructorDriverTest}).
 *
 * <p>Each fixture is a hand-assembled x86-64 (Windows-ABI) method {@code f(C* p)} whose body is a
 * single direct {@code call} forwarding its pointer parameter (in {@code RCX}, the x64
 * first-argument register) to a callee whose parameter type is varied. With a typed {@code C *}
 * callee the receiver reaches the call directly; with a {@code void *} callee (the {@code delete}
 * shape) a {@code CAST} is interposed, which {@link CppDirectCallRecognizer#recognize} strips. Either
 * way the matcher recovers the call-target address (resolving to the callee) and the receiver
 * {@code param_1}.
 */
public class CppDirectCallRecognizerTest extends AbstractDecompilerHighFunctionTest {

	private static final String CALLER = "0x401000";
	private static final String CALLEE = "0x401010";

	private ProgramBuilder builder;
	private Program program;
	private Function caller;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRecoversTargetAndTypedReceiver() throws Exception {
		HighFunction highFunction = decompileCaller(false);

		DirectCall call = recognizeSoleCall(highFunction);
		assertNotNull("matcher declined a genuine direct call with a receiver", call);
		assertNotNull("no call-target address recovered", call.callTarget());
		Function target = program.getFunctionManager().getFunctionAt(call.callTarget());
		assertNotNull("recovered call target resolved to no function", target);
		assertEquals("recovered call target is the wrong function", "callee", target.getName());
		assertNotNull("no receiver varnode recovered", call.receiver());
		assertNotNull("receiver varnode carried no HighVariable", call.receiver().getHigh());
		assertEquals("receiver should be the this pointer param_1", "param_1",
			call.receiver().getHigh().getName());
	}

	@Test
	public void testStripsInterposedVoidCast() throws Exception {
		// A void* callee (the delete shape) interposes a CAST between param_1 and the call; the
		// matcher must strip it to reach the underlying receiver.
		HighFunction highFunction = decompileCaller(true);

		DirectCall call = recognizeSoleCall(highFunction);
		assertNotNull("matcher declined a genuine direct call with a receiver", call);
		assertEquals("receiver should strip the void* CAST back to param_1", "param_1",
			call.receiver().getHigh().getName());
	}

	@Test
	public void testCallTargetAddressReadsCallee() throws Exception {
		HighFunction highFunction = decompileCaller(false);

		PcodeOp call = soleCallOp(highFunction);
		Address target = CppDirectCallRecognizer.callTargetAddress(call);
		assertNotNull("callTargetAddress recovered no address", target);
		assertEquals("callTargetAddress read the wrong target", "callee",
			program.getFunctionManager().getFunctionAt(target).getName());
		assertNull("null op must yield null, not throw",
			CppDirectCallRecognizer.callTargetAddress(null));
	}

	@Test
	public void testDeclinesNonCallOps() throws Exception {
		HighFunction highFunction = decompileCaller(false);

		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		int nonCall = 0;
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL) {
				continue;
			}
			nonCall++;
			assertNull("matcher matched a non-CALL op: " + op.getMnemonic(),
				CppDirectCallRecognizer.recognize(op));
		}
		assertTrue("fixture produced no non-CALL ops to decline", nonCall > 0);
	}

	@Test
	public void testRecognizeNullIsSafe() {
		assertNull("null call site must be declined, not throw",
			CppDirectCallRecognizer.recognize(null));
	}

	/**
	 * Builds a fresh {@code f(C* p)} that forwards {@code p} to a {@code callee} whose sole parameter
	 * is {@code void *} (when {@code voidPtrParam}) or {@code C *} (otherwise), and returns the
	 * caller's decompiled {@link HighFunction}.
	 */
	private HighFunction decompileCaller(boolean voidPtrParam) throws Exception {
		builder = new ProgramBuilder("directcall", ProgramBuilder._X64);
		builder.createMemory("text", CALLER, 0x100);
		// f(C* p): call callee ; ret   (p forwarded in RCX) — E8 rel32 -> 0x401010 ; C3
		builder.setBytes(CALLER, "e8 0b 00 00 00 c3", false);
		builder.setBytes(CALLEE, "c3", false);

		StructureDataType classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType calleeParam =
			voidPtrParam ? new PointerDataType(new Undefined1DataType()) : classCPtr;
		program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create both functions before disassembly so auto-analysis finds them and does not
		// auto-create an overlapping function at the call target.
		builder.createEmptyFunction("callee", null, conv, CALLEE, 1, VoidDataType.dataType,
			calleeParam);
		caller = builder.createEmptyFunction("f", null, conv, CALLER, 6, VoidDataType.dataType,
			classCPtr);
		builder.disassemble(CALLER, 6, false);
		builder.disassemble(CALLEE, 1, false);

		return decompileToHighFunction(program, caller);
	}

	private static DirectCall recognizeSoleCall(HighFunction highFunction) {
		DirectCall found = null;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL) {
				assertNull("fixture unexpectedly had more than one CALL", found);
				found = CppDirectCallRecognizer.recognize(op);
			}
		}
		return found;
	}

	private static PcodeOp soleCallOp(HighFunction highFunction) {
		PcodeOp found = null;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL) {
				assertNull("fixture unexpectedly had more than one CALL", found);
				found = op;
			}
		}
		assertNotNull("fixture produced no CALL op", found);
		return found;
	}
}
