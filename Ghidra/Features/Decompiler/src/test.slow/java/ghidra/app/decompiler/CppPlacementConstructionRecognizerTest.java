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

import ghidra.app.util.cpp.CppConstructorRecognizer;
import ghidra.app.util.cpp.CppPlacementConstructionRecognizer;
import ghidra.app.util.cpp.CppPlacementConstructionRecognizer.PlacementConstruction;
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
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;

/**
 * Integration coverage for the Rec 37 {@code #37-9e-b-1} {@link CppPlacementConstructionRecognizer}
 * fusion matcher, driven through the Rec 30 headless {@link AbstractDecompilerHighFunctionTest}
 * harness (DD-0023, DD-0037).
 *
 * <p>The placement fixture is a hand-assembled x86-64 (Windows-ABI) factory
 * {@code C* makeAt(void* buf)} whose body is the non-elided placement-{@code new} idiom
 * {@code p = operator.new(8, buf); C::C(p); ...} &mdash; a placement allocation call (size +
 * <em>buffer</em>) feeding a constructor call. The matcher matches the {@code C::C} {@code CALL} whose
 * cast-stripped receiver is the result of that allocation, requires the allocation to carry a buffer
 * operand ({@code input[2]}, the placement target), and recovers the constructor target, the
 * allocation target, and the buffer varnode.
 *
 * <p>The heap fixture is the {@code #37-9b} {@code C* make()} idiom {@code p = operator.new(8);
 * C::C(p);} whose allocation is handed the size alone. It proves the partition: the placement matcher
 * declines it (no buffer operand), exactly as the heap matcher declines the placement fixture
 * ({@code testHeapMatcherDeclinesPlacement}).
 */
public class CppPlacementConstructionRecognizerTest extends AbstractDecompilerHighFunctionTest {

	private static final String MAKE = "0x401000";
	private static final String OP_NEW = "0x401100";
	private static final String CTOR = "0x401200";

	private ProgramBuilder builder;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRecoversPlacementConstruction() throws Exception {
		Fixture fixture = placementFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		PlacementConstruction object = recognizeSole(highFunction);
		assertNotNull("matcher declined a genuine placement new (buf) C() construction", object);

		Function ctor =
			fixture.program.getFunctionManager().getFunctionAt(object.constructorTarget());
		assertNotNull("recovered constructor target resolved to no function", ctor);
		assertEquals("recovered constructor target is the wrong function", "C", ctor.getName());

		Function alloc =
			fixture.program.getFunctionManager().getFunctionAt(object.allocationTarget());
		assertNotNull("recovered allocation target resolved to no function", alloc);
		assertEquals("recovered allocation target is the wrong function", "operator.new",
			alloc.getName());

		HighVariable bufferHigh = object.placementBuffer().getHigh();
		assertNotNull("recovered placement buffer carried no high variable", bufferHigh);
		assertEquals("recovered placement buffer is the wrong operand", "param_1",
			bufferHigh.getName());
	}

	@Test
	public void testDeclinesHeapNew() throws Exception {
		Fixture fixture = heapFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		// A heap operator new(size) carries no buffer operand, so the placement matcher must decline
		// every CALL in the function.
		assertNull("a heap new (no buffer operand) must not match the placement matcher",
			recognizeSole(highFunction));
	}

	@Test
	public void testHeapMatcherDeclinesPlacement() throws Exception {
		Fixture fixture = placementFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		// The partition's other half: the heap matcher (#37-9b) must decline the placement fixture,
		// so a placement site is never double-rendered as both new C() and new (buf) C().
		int heapMatches = 0;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL &&
				CppConstructorRecognizer.recognize(op) != null) {
				heapMatches++;
			}
		}
		assertEquals("the heap matcher must decline a buffer-carrying placement allocation", 0,
			heapMatches);
	}

	@Test
	public void testRecognizeNullIsSafe() {
		assertNull("null call site must be declined, not throw",
			CppPlacementConstructionRecognizer.recognize(null));
	}

	private static PlacementConstruction recognizeSole(HighFunction highFunction) {
		PlacementConstruction found = null;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			PlacementConstruction candidate = CppPlacementConstructionRecognizer.recognize(op);
			if (candidate != null) {
				assertNull("fixture unexpectedly matched more than one construction", found);
				found = candidate;
			}
		}
		return found;
	}

	private record Fixture(Program program, Function make) {}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C()}: a two-arg placement
	 * {@code operator.new(size, buffer)} whose result feeds the {@code C::C} constructor.
	 */
	private Fixture placementFixture() throws Exception {
		builder = new ProgramBuilder("placement", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// makeAt(void* buf): mov rdx,rcx; mov ecx,8; call operator.new; mov rcx,rax; call C::C; ret
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 e8 eb 01 00 00 c3", false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		StructureDataType classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// placement operator new takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr);
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 22, classCPtr, voidPtr);
		builder.disassemble(MAKE, 22, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds the {@code #37-9b} heap {@code C* make()} doing {@code new C()}: a one-arg
	 * {@code operator.new(size)} whose result feeds the {@code C::C} constructor (no buffer operand).
	 */
	private Fixture heapFixture() throws Exception {
		builder = new ProgramBuilder("heap", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
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
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType());
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr);
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 35, classCPtr);
		builder.disassemble(MAKE, 35, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}
}
