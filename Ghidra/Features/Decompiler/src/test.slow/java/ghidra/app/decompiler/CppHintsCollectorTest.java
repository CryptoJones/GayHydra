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
import ghidra.app.util.cpp.CppHintsCollector;
import ghidra.app.util.cpp.CppHintsCollector.CppHint;
import ghidra.app.util.cpp.CppHintsCollector.Kind;
import ghidra.app.util.cpp.CppMethod;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.app.util.cpp.CppTypeSystemProvider;
import ghidra.app.util.cpp.CppVTable;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Integration coverage for the Rec 37 {@code #37-11d-1} {@link CppHintsCollector} (DD-0067), driven
 * through the Rec 30 headless {@link AbstractDecompilerHighFunctionTest} harness. The fixture is
 * the {@link CppVirtualCallDriverTest} x86-64 virtual-call body; the difference is the wiring under
 * test — the class is fed into the {@link CppTypeSystemProvider}'s shared per-program instance (the
 * way the analyzer wrappers feed in production), and the collector finds it there with nothing but
 * the {@link HighFunction}.
 */
public class CppHintsCollectorTest extends AbstractDecompilerHighFunctionTest {

	private static final String ENTRY = "0x401000";

	private ProgramBuilder builder;
	private Program program;
	private Function function;
	private StructureDataType classC;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("hintsCollector", ProgramBuilder._X64);
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
	public void testCollectsVirtualCallHintFromTheProviderTypeSystem() throws Exception {
		// Feed the class the way a production contributor does: into the provider's shared
		// per-program instance, not a hand-passed one.
		CppTypeSystem typeSystem = CppTypeSystemProvider.get(program);
		CppClass cppClass = typeSystem.defineClass(classC);
		CppVTable vtable = new CppVTable();
		vtable.addSlot(new CppMethod("vfn0"));  // slot 0
		vtable.addSlot(new CppMethod("draw"));  // slot 1 — the dispatched method
		cppClass.setVtable(vtable);

		HighFunction highFunction = decompileToHighFunction(program, function);
		List<CppHint> hints = CppHintsCollector.collect(highFunction);

		assertEquals("expected exactly one collected hint", 1, hints.size());
		CppHint hint = hints.get(0);
		assertEquals(Kind.VIRTUAL_CALL, hint.kind());
		assertEquals("param_1->draw()", hint.rendering());
		assertNotNull("hint carried no call-site address", hint.site());
	}

	@Test
	public void testUnfedTypeSystemYieldsNoHints() throws Exception {
		// Nothing fed the provider's type system — the collector must return nothing rather than
		// invent a hint (the drivers' advisory decline, surfaced through the facade).
		HighFunction highFunction = decompileToHighFunction(program, function);

		List<CppHint> hints = CppHintsCollector.collect(highFunction);

		assertTrue("an unfed type system must yield no hints", hints.isEmpty());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullFunction() {
		CppHintsCollector.collect(null);
	}
}
