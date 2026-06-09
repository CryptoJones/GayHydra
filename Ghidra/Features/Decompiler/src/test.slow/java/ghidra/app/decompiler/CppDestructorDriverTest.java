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

import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppDecompilerHints;
import ghidra.app.util.cpp.CppDestructorDriver;
import ghidra.app.util.cpp.CppDestructorDriver.RenderedDestructorCall;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Integration coverage for the Rec 37 {@code #37-9c-b-2}
 * {@link CppDestructorDriver}, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0028).
 *
 * <p>Each fixture is a hand-assembled x86-64 (Windows-ABI) method {@code f(C* p)} whose body is a
 * single direct {@code call} forwarding its pointer parameter (in {@code RCX}) to a callee of a
 * chosen name. When that callee is named {@code ~C} and the class {@code C} is modelled in the type
 * system, the driver renders {@code param_1->~C()} (pointer receiver). A callee that is not a
 * {@code ~ClassName} destructor, or a destructor of a class the type system does not model, yields no
 * hint. The destructor local name {@code ~C} is the form Ghidra's GNU demangler produces (e.g.
 * {@code _ZN6Magick5ImageD1Ev} &rarr; local name {@code ~Image}).
 */
public class CppDestructorDriverTest extends AbstractDecompilerHighFunctionTest {

	private static final String CALLER = "0x401000";
	private static final String CALLEE = "0x401010";

	private ProgramBuilder builder;
	private StructureDataType classC;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRendersDestructorCall() throws Exception {
		HighFunction highFunction = decompileCallerCalling("~C");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppDestructorDriver driver = new CppDestructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedDestructorCall> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered destructor call", 1, hints.size());
		assertEquals("wrong rendered destructor call", "param_1->~C()", hints.get(0).rendering());
		assertNotNull("hint carried no call-site address", hints.get(0).site());
	}

	@Test
	public void testDeclinesNonDestructorCallee() throws Exception {
		HighFunction highFunction = decompileCallerCalling("some_other_function");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppDestructorDriver driver = new CppDestructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedDestructorCall> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a non-destructor callee must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDeclinesWhenClassNotModelled() throws Exception {
		HighFunction highFunction = decompileCallerCalling("~C");

		// Empty type system: the destructor's class C is not modelled, so nothing resolves.
		CppDestructorDriver driver =
			new CppDestructorDriver(new CppDecompilerHints(), new CppTypeSystem());
		List<RenderedDestructorCall> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unmodelled destructor class must yield no hints", hints.isEmpty());
	}

	@Test
	public void testConstructorRejectsNulls() {
		try {
			new CppDestructorDriver(null, new CppTypeSystem());
			fail("null renderer must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
		try {
			new CppDestructorDriver(new CppDecompilerHints(), null);
			fail("null type system must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Builds a fresh program whose {@code f(C* p)} forwards {@code p} to a callee of the given name,
	 * and returns the caller's decompiled {@link HighFunction}. Stores the builder for disposal and
	 * the {@code C} structure for type-system modelling.
	 */
	private HighFunction decompileCallerCalling(String calleeName) throws Exception {
		builder = new ProgramBuilder("dtorDrv", ProgramBuilder._X64);
		builder.createMemory("text", CALLER, 0x100);
		// f(C* p): call <callee> ; ret   (p forwarded in RCX) — E8 rel32 -> 0x401010 ; C3
		builder.setBytes(CALLER, "e8 0b 00 00 00 c3", false);
		builder.setBytes(CALLEE, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create both functions before disassembly so auto-analysis finds them and does not
		// auto-create an overlapping function at the call target.
		builder.createEmptyFunction(calleeName, null, conv, CALLEE, 1, VoidDataType.dataType,
			classCPtr);
		Function caller = builder.createEmptyFunction("f", null, conv, CALLER, 6,
			VoidDataType.dataType, classCPtr);
		builder.disassemble(CALLER, 6, false);
		builder.disassemble(CALLEE, 1, false);

		return decompileToHighFunction(program, caller);
	}
}
