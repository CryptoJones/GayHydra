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

import ghidra.app.util.cpp.CppArrayConstructionDriver;
import ghidra.app.util.cpp.CppArrayConstructionDriver.RenderedArrayConstruction;
import ghidra.app.util.cpp.CppDecompilerHints;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.LongLongDataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.Undefined1DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Integration coverage for the Rec 37 {@code #37-9d-b-2}
 * {@link CppArrayConstructionDriver}, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0033).
 *
 * <p>Each fixture is a hand-assembled x86-64 (Windows-ABI) factory {@code C* makeArray()} whose body
 * is the trivial-element array-{@code new} idiom {@code p = <allocName>(0x28); return (C *)p;} &mdash;
 * a sized allocation whose raw {@code void *} result is reinterpreted to {@code C *} downstream
 * ({@code 0x28 = 5 * sizeof(C)}). When the allocation callee is {@code operator.new[]} and the class
 * {@code C} is modelled, the driver renders {@code new C[5]} &mdash; the count recovered as
 * {@code 0x28 / sizeof(C) = 5}. A scalar {@code operator.new} (not the array form) or an unmodelled
 * class each yields no hint.
 */
public class CppArrayConstructionDriverTest extends AbstractDecompilerHighFunctionTest {

	private static final String MAKE = "0x401000";
	private static final String OP_NEW_ARRAY = "0x401100";

	private ProgramBuilder builder;
	private StructureDataType classC;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRendersArrayConstruction() throws Exception {
		HighFunction highFunction = decompileMakeArray("operator.new[]");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppArrayConstructionDriver driver =
			new CppArrayConstructionDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedArrayConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered array construction", 1, hints.size());
		assertEquals("wrong rendered array construction", "new C[5]", hints.get(0).rendering());
		assertNotNull("hint carried no call-site address", hints.get(0).site());
	}

	@Test
	public void testDeclinesScalarOperatorNew() throws Exception {
		// The allocation callee is the scalar operator new, not the array operator new[].
		HighFunction highFunction = decompileMakeArray("operator.new");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppArrayConstructionDriver driver =
			new CppArrayConstructionDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedArrayConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a scalar operator new must yield no array-new hints", hints.isEmpty());
	}

	@Test
	public void testDeclinesWhenClassNotModelled() throws Exception {
		HighFunction highFunction = decompileMakeArray("operator.new[]");

		// Empty type system: the element class C is not modelled, so nothing resolves.
		CppArrayConstructionDriver driver =
			new CppArrayConstructionDriver(new CppDecompilerHints(), new CppTypeSystem());
		List<RenderedArrayConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unmodelled element class must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDriverRejectsNulls() {
		try {
			new CppArrayConstructionDriver(null, new CppTypeSystem());
			fail("null renderer must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
		try {
			new CppArrayConstructionDriver(new CppDecompilerHints(), null);
			fail("null type system must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Builds a fresh program whose {@code C* makeArray()} does {@code p = <allocName>(0x28);
	 * return (C *)p;} and returns the {@code makeArray} decompiled {@link HighFunction}. Stores the
	 * builder for disposal and the {@code C} structure for type-system modelling.
	 */
	private HighFunction decompileMakeArray(String allocName) throws Exception {
		builder = new ProgramBuilder("arrnewDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x200);
		// makeArray(): mov ecx,0x28 ; call alloc ; ret  (result returned in rax)
		builder.setBytes(MAKE, "b9 28 00 00 00 e8 f6 00 00 00 c3", false);
		builder.setBytes(OP_NEW_ARRAY, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create both functions before disassembly so auto-analysis finds them and does not
		// auto-create an overlapping function at the call target.
		builder.createEmptyFunction(allocName, null, conv, OP_NEW_ARRAY, 1, voidPtr,
			new LongLongDataType());
		Function makeArray =
			builder.createEmptyFunction("makeArray", null, conv, MAKE, 11, classCPtr);
		builder.disassemble(MAKE, 11, false);
		builder.disassemble(OP_NEW_ARRAY, 1, false);

		return decompileToHighFunction(program, makeArray);
	}
}
