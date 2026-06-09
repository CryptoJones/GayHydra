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
import ghidra.app.util.cpp.CppConstructorDriver;
import ghidra.app.util.cpp.CppConstructorDriver.RenderedConstruction;
import ghidra.app.util.cpp.CppDecompilerHints;
import ghidra.app.util.cpp.CppTypeSystem;
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

/**
 * Integration coverage for the Rec 37 {@code #37-9b-2}
 * {@link CppConstructorDriver}, driven through the Rec 30 headless
 * {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0030).
 *
 * <p>Each fixture is a hand-assembled x86-64 (Windows-ABI) factory {@code C* make()} whose body is
 * the heap-{@code new} idiom {@code p = alloc(8); ctor(p); return p;} — an allocation call feeding a
 * constructor call. When the constructor callee's local name equals its class namespace
 * ({@code C} in namespace {@code C}), the allocation callee is {@code operator.new}, and the class
 * {@code C} is modelled, the driver renders {@code new C()}. A callee whose name does not equal its
 * class (not a constructor), an allocation that is not {@code operator new}, or a class the type
 * system does not model each yields no hint. The {@code C}-in-namespace-{@code C} shape is the form
 * Ghidra's GNU demangler produces for a constructor (e.g. {@code _ZN3Bar4FredC1Ei} &rarr; local name
 * {@code Fred} in namespace {@code Fred}).
 */
public class CppConstructorDriverTest extends AbstractDecompilerHighFunctionTest {

	private static final String MAKE = "0x401000";
	private static final String OP_NEW = "0x401100";
	private static final String CTOR = "0x401200";

	private ProgramBuilder builder;
	private StructureDataType classC;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRendersConstruction() throws Exception {
		HighFunction highFunction = decompileMake("operator.new", "C", "C");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("wrong rendered construction", "new C()", hints.get(0).rendering());
		assertNotNull("hint carried no call-site address", hints.get(0).site());
	}

	@Test
	public void testRendersConstructionWithArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithArg();

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("constructor argument was not threaded into the rendering", "new C(param_1)",
			hints.get(0).rendering());
	}

	@Test
	public void testDeclinesNonConstructorCallee() throws Exception {
		// The "constructor" callee is named build, not C, so its name != its class namespace.
		HighFunction highFunction = decompileMake("operator.new", "build", "C");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a non-constructor callee must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDeclinesWhenAllocationNotOperatorNew() throws Exception {
		// The allocation callee is a custom allocator, not operator new.
		HighFunction highFunction = decompileMake("my_alloc", "C", "C");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a non-operator-new allocation must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDeclinesWhenClassNotModelled() throws Exception {
		HighFunction highFunction = decompileMake("operator.new", "C", "C");

		// Empty type system: the constructed class C is not modelled, so nothing resolves.
		CppConstructorDriver driver =
			new CppConstructorDriver(new CppDecompilerHints(), new CppTypeSystem());
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unmodelled constructed class must yield no hints", hints.isEmpty());
	}

	@Test
	public void testConstructorRejectsNulls() {
		try {
			new CppConstructorDriver(null, new CppTypeSystem());
			fail("null renderer must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
		try {
			new CppConstructorDriver(new CppDecompilerHints(), null);
			fail("null type system must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code p = <allocName>(8); <ctorName>(p);
	 * return p;}, with the constructor callee created in namespace {@code ctorNamespace}, and returns
	 * the {@code make} decompiled {@link HighFunction}. Stores the builder for disposal and the
	 * {@code C} structure for type-system modelling.
	 */
	private HighFunction decompileMake(String allocName, String ctorName, String ctorNamespace)
			throws Exception {
		builder = new ProgramBuilder("ctorDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// make(): push rsi; sub rsp,0x20; mov ecx,8; call alloc; mov rsi,rax; mov rcx,rax;
		//         call ctor; mov rax,rsi; add rsp,0x20; pop rsi; ret
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 e8 e6 01 00 00 " +
				"48 89 f0 48 83 c4 20 5e c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// create all functions before disassembly so auto-analysis finds them and does not
		// auto-create overlapping functions at the call targets.
		builder.createEmptyFunction(allocName, null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType());
		builder.createEmptyFunction(ctorName, ctorNamespace, conv, CTOR, 1, VoidDataType.dataType,
			classCPtr);
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 35, classCPtr);
		builder.disassemble(MAKE, 35, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make(longlong v)} does {@code return new C(v);}
	 * ({@code p = operator.new(8); C::C(p, v); return p;}), so the constructor {@code CALL} carries the
	 * explicit argument {@code v} as its third input (after the call target and the {@code this}
	 * receiver). Grounds the {@code #37-10b} constructor-argument threading.
	 */
	private HighFunction decompileMakeWithArg() throws Exception {
		builder = new ProgramBuilder("ctorArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// make(longlong v): push rsi; push rdi; mov rsi,rcx (save v); mov ecx,8 (size);
		//   call op_new; mov rdi,rax (save this); mov rcx,rax (this); mov rdx,rsi (v -> ctor arg1);
		//   call ctor; mov rax,rdi (return this); pop rdi; pop rsi; ret
		builder.setBytes(MAKE,
			"56 57 48 89 ce b9 08 00 00 00 e8 f1 00 00 00 48 89 c7 48 89 c1 48 89 f2 " +
				"e8 e3 01 00 00 48 89 f8 5f 5e c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType());
		// the constructor takes the this receiver and one explicit longlong argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new LongLongDataType());
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 35, classCPtr,
			new LongLongDataType());
		builder.disassemble(MAKE, 35, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}
}
