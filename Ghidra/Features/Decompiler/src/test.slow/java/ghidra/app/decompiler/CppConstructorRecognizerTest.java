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

import ghidra.app.util.cpp.CppConstructorRecognizer;
import ghidra.app.util.cpp.CppConstructorRecognizer.ConstructedObject;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.LongLongDataType;
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
 * Integration coverage for the Rec 37 {@code #37-9b-1}
 * {@link CppConstructorRecognizer} fusion matcher, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0030).
 *
 * <p>The fixture is a hand-assembled x86-64 (Windows-ABI) factory {@code C* make()} whose body is the
 * heap-{@code new} idiom {@code p = operator.new(8); C::C(p); return p;} &mdash; an allocation call
 * feeding a constructor call. The matcher walks the function's p-code, declines the
 * {@code operator.new} {@code CALL} (its argument is the size constant, not a call result), and
 * matches the {@code C::C} {@code CALL} whose cast-stripped receiver <em>is</em> the allocation's
 * result, recovering both the constructor target ({@code C::C}) and the allocation target
 * ({@code operator.new}). Whether those targets really are a constructor and {@code operator new} is
 * the {@code #37-9b-2} driver's concern, not the matcher's.
 */
public class CppConstructorRecognizerTest extends AbstractDecompilerHighFunctionTest {

	private static final String MAKE = "0x401000";
	private static final String OP_NEW = "0x401100";
	private static final String CTOR = "0x401200";

	private ProgramBuilder builder;
	private Program program;
	private Function make;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("ctor", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// make(): push rsi; sub rsp,0x20; mov ecx,8; call operator.new; mov rsi,rax; mov rcx,rax;
		//         call C::C; mov rax,rsi; add rsp,0x20; pop rsi; ret
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 e8 e6 01 00 00 " +
				"48 89 f0 48 83 c4 20 5e c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		StructureDataType classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create all functions before disassembly so auto-analysis finds them and does not
		// auto-create overlapping functions at the call targets.
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType());
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr);
		make = builder.createEmptyFunction("make", null, conv, MAKE, 35, classCPtr);
		builder.disassemble(MAKE, 35, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRecognizesConstructorAndAllocationTargets() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, make);

		ConstructedObject object = recognizeSoleConstruction(highFunction);
		assertNotNull("matcher declined a genuine new C() construction", object);

		Function ctor = program.getFunctionManager().getFunctionAt(object.constructorTarget());
		assertNotNull("recovered constructor target resolved to no function", ctor);
		assertEquals("recovered constructor target is the wrong function", "C", ctor.getName());

		Function alloc = program.getFunctionManager().getFunctionAt(object.allocationTarget());
		assertNotNull("recovered allocation target resolved to no function", alloc);
		assertEquals("recovered allocation target is the wrong function", "operator.new",
			alloc.getName());
	}

	@Test
	public void testDeclinesAllocationCallItself() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, make);

		// The operator.new CALL's argument is the size constant, not a call result, so the fusion
		// matcher must decline it; only the constructor CALL matches.
		int matched = 0;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL &&
				CppConstructorRecognizer.recognize(op) != null) {
				matched++;
			}
		}
		assertEquals("exactly one of the two CALLs (the constructor) must match", 1, matched);
	}

	@Test
	public void testRecognizeNullIsSafe() {
		assertNull("null call site must be declined, not throw",
			CppConstructorRecognizer.recognize(null));
	}

	private static ConstructedObject recognizeSoleConstruction(HighFunction highFunction) {
		ConstructedObject found = null;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			ConstructedObject candidate = CppConstructorRecognizer.recognize(op);
			if (candidate != null) {
				assertNull("fixture unexpectedly matched more than one construction", found);
				found = candidate;
			}
		}
		return found;
	}
}
