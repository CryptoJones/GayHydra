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

import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppDecompilerHints;
import ghidra.app.util.cpp.CppMethod;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.app.util.cpp.CppVTable;
import ghidra.app.util.cpp.CppVirtualCallDriver;
import ghidra.app.util.cpp.CppVirtualCallDriver.RenderedVirtualCall;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Integration coverage for the Rec 37 {@code #37-7b-2}
 * {@link CppVirtualCallDriver}, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0025).
 *
 * <p>The fixture is a hand-assembled x86-64 (Windows-ABI) method body
 * {@code mov rax,[rcx]; call qword [rax+8]; ret} with a {@code __fastcall this} typed {@code C *}
 * (so the receiver register is {@code RCX}, the x64 first-argument register; an {@code RDI}-based
 * System-V body would leave {@code this} unrecovered under this compiler spec). The decompiler
 * recovers the receiver as parameter {@code param_1} of type {@code C *}; the driver resolves that
 * to the modelled {@code CppClass} named {@code C}, whose vtable slot 1 is the method {@code draw},
 * and renders {@code param_1->draw()}.
 */
public class CppVirtualCallDriverTest extends AbstractDecompilerHighFunctionTest {

	private static final String ENTRY = "0x401000";

	private ProgramBuilder builder;
	private Program program;
	private Function function;
	private StructureDataType classC;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("vcallDriver", ProgramBuilder._X64);
		builder.createMemory("text", ENTRY, 0x100);
		// void C::f(C* this):  mov rax,[rcx] ; call qword [rax+8] ; ret   (this in RCX, win x64)
		builder.setBytes(ENTRY, "48 8b 01 ff 50 08 c3", true);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		program = builder.getProgram();
		String defaultConvention = program.getCompilerSpec().getDefaultCallingConvention().getName();
		function = builder.createEmptyFunction("f", null, defaultConvention, ENTRY, 7,
			VoidDataType.dataType, classCPtr);
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRendersVirtualCallThroughNamedSlot() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, function);

		CppTypeSystem typeSystem = new CppTypeSystem();
		CppClass cppClass = typeSystem.defineClass(classC);
		CppVTable vtable = new CppVTable();
		vtable.addSlot(new CppMethod("vfn0"));  // slot 0
		vtable.addSlot(new CppMethod("draw"));  // slot 1 — the dispatched method
		cppClass.setVtable(vtable);

		CppVirtualCallDriver driver = new CppVirtualCallDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedVirtualCall> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered virtual call", 1, hints.size());
		assertEquals("wrong rendered virtual call", "param_1->draw()", hints.get(0).rendering());
		assertNotNull("hint carried no call-site address", hints.get(0).site());
	}

	@Test
	public void testDeclinesWhenClassNotModelled() throws Exception {
		HighFunction highFunction = decompileToHighFunction(program, function);

		// Empty type system: the receiver's class C is not modelled, so nothing resolves.
		CppVirtualCallDriver driver =
			new CppVirtualCallDriver(new CppDecompilerHints(), new CppTypeSystem());
		List<RenderedVirtualCall> hints = driver.recognizeAndRender(highFunction);

		assertTrue("unmodelled receiver class must yield no hints", hints.isEmpty());
	}

	@Test
	public void testConstructorRejectsNulls() {
		try {
			new CppVirtualCallDriver(null, new CppTypeSystem());
			fail("null renderer must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
		try {
			new CppVirtualCallDriver(new CppDecompilerHints(), null);
			fail("null type system must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
