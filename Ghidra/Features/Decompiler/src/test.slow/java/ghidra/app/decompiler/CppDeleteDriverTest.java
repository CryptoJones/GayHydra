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
import org.junit.Test;

import ghidra.app.util.cpp.CppDecompilerHints;
import ghidra.app.util.cpp.CppDeleteDriver;
import ghidra.app.util.cpp.CppDeleteDriver.RenderedDelete;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.Undefined1DataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Integration coverage for the Rec 37 {@code #37-9f-b-2}
 * {@link CppDeleteDriver}, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0026).
 *
 * <p>Each fixture is a hand-assembled x86-64 (Windows-ABI) method {@code f(C* p)} whose body is a
 * single direct {@code call} forwarding its pointer parameter (in {@code RCX}) to a callee of a
 * chosen name. When that callee is named {@code operator.delete} the driver renders
 * {@code delete param_1}; when it is {@code operator.delete[]} it renders {@code delete[] param_1};
 * any other callee resolves to no deallocation and yields no hint. The callee names are exactly the
 * demangled forms Ghidra's GNU demangler produces for {@code _ZdlPv} / {@code _ZdaPv}.
 */
public class CppDeleteDriverTest extends AbstractDecompilerHighFunctionTest {

	private static final String CALLER = "0x401000";
	private static final String CALLEE = "0x401010";

	private ProgramBuilder builder;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRendersScalarDelete() throws Exception {
		HighFunction highFunction = decompileCallerCalling("operator.delete");

		CppDeleteDriver driver = new CppDeleteDriver(new CppDecompilerHints());
		List<RenderedDelete> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered delete", 1, hints.size());
		assertEquals("wrong rendered scalar delete", "delete param_1", hints.get(0).rendering());
		assertNotNull("hint carried no call-site address", hints.get(0).site());
	}

	@Test
	public void testRendersArrayDelete() throws Exception {
		HighFunction highFunction = decompileCallerCalling("operator.delete[]");

		CppDeleteDriver driver = new CppDeleteDriver(new CppDecompilerHints());
		List<RenderedDelete> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered delete", 1, hints.size());
		assertEquals("wrong rendered array delete", "delete[] param_1", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesNonDeleteCallee() throws Exception {
		HighFunction highFunction = decompileCallerCalling("some_other_function");

		CppDeleteDriver driver = new CppDeleteDriver(new CppDecompilerHints());
		List<RenderedDelete> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a non-deallocation callee must yield no hints", hints.isEmpty());
	}

	@Test
	public void testConstructorRejectsNullRenderer() {
		try {
			new CppDeleteDriver(null);
			fail("null renderer must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Builds a fresh program whose {@code f(C* p)} forwards {@code p} to a callee of the given name,
	 * and returns the caller's decompiled {@link HighFunction}. Stores the builder for disposal.
	 */
	private HighFunction decompileCallerCalling(String calleeName) throws Exception {
		builder = new ProgramBuilder("delDrv", ProgramBuilder._X64);
		builder.createMemory("text", CALLER, 0x100);
		// f(C* p): call <callee> ; ret   (p forwarded in RCX) — E8 rel32 -> 0x401010 ; C3
		builder.setBytes(CALLER, "e8 0b 00 00 00 c3", false);
		builder.setBytes(CALLEE, "c3", false);

		StructureDataType classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create both functions before disassembly so auto-analysis finds them and does not
		// auto-create an overlapping function at the call target.
		builder.createEmptyFunction(calleeName, null, conv, CALLEE, 1, VoidDataType.dataType,
			voidPtr);
		Function caller = builder.createEmptyFunction("f", null, conv, CALLER, 6,
			VoidDataType.dataType, classCPtr);
		builder.disassemble(CALLER, 6, false);
		builder.disassemble(CALLEE, 1, false);

		return decompileToHighFunction(program, caller);
	}
}
