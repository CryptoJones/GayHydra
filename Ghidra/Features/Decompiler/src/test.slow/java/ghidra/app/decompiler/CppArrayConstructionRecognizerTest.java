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

import ghidra.app.util.cpp.CppArrayConstructionRecognizer;
import ghidra.app.util.cpp.CppArrayConstructionRecognizer.ArrayAllocation;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.LongLongDataType;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.Undefined1DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;

/**
 * Integration coverage for the Rec 37 {@code #37-9d-b-1}
 * {@link CppArrayConstructionRecognizer} forward matcher, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0033).
 *
 * <p>The fixture is a hand-assembled x86-64 (Windows-ABI) factory {@code C* makeArray()} whose body
 * is the trivial-element array-{@code new} idiom {@code p = operator.new[](0x28); return (C *)p;}
 * &mdash; a sized allocation whose raw {@code void *} result is reinterpreted to {@code C *}
 * downstream ({@code 0x28 = 5 * sizeof(C)}). The matcher anchors on the allocation {@code CALL},
 * recovers the target ({@code operator.new[]}) and the byte-size argument ({@code 0x28}), and walks
 * <em>forward</em> over the {@code CAST} to the typed result varnode carrying {@code C *}. Whether the
 * callee really is {@code operator new[]}, and the element class/count it implies, is the
 * {@code #37-9d-b-2} driver's concern, not the matcher's.
 */
public class CppArrayConstructionRecognizerTest extends AbstractDecompilerHighFunctionTest {

	private static final String MAKE = "0x401000";
	private static final String OP_NEW_ARRAY = "0x401100";

	private ProgramBuilder builder;
	private Program program;
	private Function makeArray;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("arrnew", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x200);
		// makeArray(): mov ecx,0x28 ; call operator.new[] ; ret  (result returned in rax)
		builder.setBytes(MAKE, "b9 28 00 00 00 e8 f6 00 00 00 c3", false);
		builder.setBytes(OP_NEW_ARRAY, "c3", false);

		StructureDataType classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create both functions before disassembly so auto-analysis finds them and does not
		// auto-create an overlapping function at the call target.
		builder.createEmptyFunction("operator.new[]", null, conv, OP_NEW_ARRAY, 1, voidPtr,
			new LongLongDataType());
		makeArray = builder.createEmptyFunction("makeArray", null, conv, MAKE, 11, classCPtr);
		builder.disassemble(MAKE, 11, false);
		builder.disassemble(OP_NEW_ARRAY, 1, false);
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRecoversAllocationTargetSizeAndTypedResult() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, makeArray);

		ArrayAllocation alloc = recognizeSoleAllocation(highFunction);
		assertNotNull("matcher declined a genuine array-new allocation", alloc);

		Function target = program.getFunctionManager().getFunctionAt(alloc.allocationTarget());
		assertNotNull("recovered allocation target resolved to no function", target);
		assertEquals("recovered allocation target is the wrong function", "operator.new[]",
			target.getName());

		assertTrue("byte-size argument should be the constant total size",
			alloc.byteSize().isConstant());
		assertEquals("byte size should be 5 * sizeof(C) = 0x28", 0x28, alloc.byteSize().getOffset());

		assertNotNull("no typed result recovered", alloc.typedResult());
		assertNotNull("typed result carried no HighVariable", alloc.typedResult().getHigh());
		DataType resultType = alloc.typedResult().getHigh().getDataType();
		assertTrue("typed result should carry a pointer type, was " + resultType,
			resultType instanceof Pointer);
		DataType element = ((Pointer) resultType).getDataType();
		assertTrue("pointer should point at the element structure, was " + element,
			element instanceof Structure);
		assertEquals("element structure should be C", "C", element.getName());
	}

	@Test
	public void testRecognizeNullIsSafe() {
		assertNull("null call site must be declined, not throw",
			CppArrayConstructionRecognizer.recognize(null));
	}

	@Test
	public void testDeclinesNonCallOps() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, makeArray);

		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		int nonCall = 0;
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() == PcodeOp.CALL) {
				continue;
			}
			nonCall++;
			assertNull("matcher matched a non-CALL op: " + op.getMnemonic(),
				CppArrayConstructionRecognizer.recognize(op));
		}
		assertTrue("fixture produced no non-CALL ops to decline", nonCall > 0);
	}

	private static ArrayAllocation recognizeSoleAllocation(HighFunction highFunction) {
		ArrayAllocation found = null;
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			ArrayAllocation candidate = CppArrayConstructionRecognizer.recognize(op);
			if (candidate != null) {
				assertNull("fixture unexpectedly matched more than one allocation", found);
				found = candidate;
			}
		}
		return found;
	}
}
