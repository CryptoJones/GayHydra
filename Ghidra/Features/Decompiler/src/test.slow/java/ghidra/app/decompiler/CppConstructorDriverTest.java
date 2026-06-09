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
import ghidra.program.model.data.BooleanDataType;
import ghidra.program.model.data.CharDataType;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DoubleDataType;
import ghidra.program.model.data.EnumDataType;
import ghidra.program.model.data.FloatDataType;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.LongLongDataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.Undefined1DataType;
import ghidra.program.model.data.UnsignedLongLongDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.data.WideChar16DataType;
import ghidra.program.model.data.WideChar32DataType;
import ghidra.program.model.data.WideCharDataType;
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
	public void testRendersConstructionWithConstantArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithConstArg();

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("integer constant argument was not rendered", "new C(5)",
			hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithSignedNegativeArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithSignedNegArg();

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("negative signed constant must sign-extend, not render as a large unsigned number",
			"new C(-1)", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithUnsignedWideArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithUnsignedWideArg();

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("wide unsigned constant must render across the full unsigned range",
			"new C(18446744073709551615)", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithBooleanTrueArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithBoolArg(true);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("boolean constant 1 must render as true, not 1", "new C(true)",
			hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithBooleanFalseArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithBoolArg(false);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("boolean constant 0 must render as false, not 0", "new C(false)",
			hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithCharArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithCharArg(0x41);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("printable char constant must render as a 'A' literal, not the decimal 65",
			"new C('A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithEscapedCharArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithCharArg(0x0a);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("a control char must render as its escaped C literal, not the decimal 10",
			"new C('\\n')", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithEnumArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithEnumArg(2);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("enum constant must render as its qualified member name, not the decimal 2",
			"new C(Color::GREEN)", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesEnumArgumentWithUnnamedValue() throws Exception {
		// 7 is not RED(0)/GREEN(2)/BLUE(3): Enum.getName returns null, so rather than fabricate a name
		// or render a bare decimal that would mislead, the whole hint declines.
		HighFunction highFunction = decompileMakeWithEnumArg(7);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an enum value naming no member must yield no hints", hints.isEmpty());
	}

	@Test
	public void testRendersConstructionWithWideCharArgument() throws Exception {
		HighFunction highFunction = decompileMakeWithWideCharArg(new WideCharDataType(), 0x41);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("wchar_t constant must render as the L-prefixed literal L'A', not a decimal",
			"new C(L'A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithChar16Argument() throws Exception {
		HighFunction highFunction = decompileMakeWithWideCharArg(new WideChar16DataType(), 0x41);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("char16_t constant must render as the u-prefixed literal u'A', not a decimal",
			"new C(u'A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithChar32Argument() throws Exception {
		HighFunction highFunction = decompileMakeWithWideCharArg(new WideChar32DataType(), 0x41);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("char32_t constant must render as the U-prefixed literal U'A', not a decimal",
			"new C(U'A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithNonAsciiWideCharArgument() throws Exception {
		// U+20AC (euro) is not printable ASCII: a char16_t constant must render as a width-padded hex
		// escape u'\x20ac', not a bare decimal and not a malformed universal-character-name.
		HighFunction highFunction = decompileMakeWithWideCharArg(new WideChar16DataType(), 0x20ac);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("a non-printable wide-char unit must render as a width-padded hex escape",
			"new C(u'\\x20ac')", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithFloatArgument() throws Exception {
		// 0x40200000 is the IEEE-754 single-precision bit pattern for 2.5f.
		HighFunction highFunction = decompileMakeWithFloatArg(0x40200000);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("float constant must render as the f-suffixed literal 2.5f, not a bit pattern",
			"new C(2.5f)", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithDoubleArgument() throws Exception {
		// 0x4004000000000000 is the IEEE-754 double-precision bit pattern for 2.5.
		HighFunction highFunction = decompileMakeWithDoubleArg(0x4004000000000000L);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("double constant must render as the unsuffixed literal 2.5, not a bit pattern",
			"new C(2.5)", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesNonFiniteFloatArgument() throws Exception {
		// 0x7fc00000 is a quiet NaN: NaN has no bare C++ literal, so rather than emit the invalid text
		// "NaN" the whole hint declines, keeping the never-wrong contract.
		HighFunction highFunction = decompileMakeWithFloatArg(0x7fc00000);

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a non-finite float value must yield no hints", hints.isEmpty());
	}

	@Test
	public void testRendersConstructionWithStringPtrArgument() throws Exception {
		// A const char* string-pointer argument is not a constant varnode: the decompiler resolves the
		// global address (0x402000, holding "Hi\0") into an unnamed char* temp (a HighOther). #37-10k
		// traces that temp's COPY def-chain to the constant address and reads the NUL-terminated bytes,
		// so it renders the string literal new C("Hi") rather than declining.
		HighFunction highFunction = decompileMakeWithStringPtrArg("48 69 00");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("char* argument must render as the string literal \"Hi\", not UNNAMED",
			"new C(\"Hi\")", hints.get(0).rendering());
	}

	@Test
	public void testRendersConstructionWithEscapedStringPtrArgument() throws Exception {
		// Bytes 0x41 0x09 0x01 0x00 at 0x402000: 'A', a tab (a named C escape), and 0x01 (no named
		// escape, so a 3-digit octal escape \001 — never \x01, which is greedy in a string literal),
		// then the NUL terminator. The string must render new C("A\t\001").
		HighFunction highFunction = decompileMakeWithStringPtrArg("41 09 01 00");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered construction", 1, hints.size());
		assertEquals("non-printable string bytes must escape (named escape + 3-digit octal)",
			"new C(\"A\\t\\001\")", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesUnnamedNonCharPointerArgument() throws Exception {
		// An unnamed non-char* pointer argument (here an int*, the same global-address load as the
		// string fixture but a different pointee type) is still a HighOther named UNNAMED, but the
		// string renderer is gated on a pointer-to-char, so it does not apply. With no name, no string,
		// and a non-constant varnode, the whole hint declines — the never-wrong contract still holds for
		// a genuinely unnameable pointer argument.
		HighFunction highFunction =
			decompileMakeWithPtrArg(new PointerDataType(new IntegerDataType()), "48 69 00");

		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);

		CppConstructorDriver driver = new CppConstructorDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedConstruction> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unnamed non-char* pointer argument must yield no hints", hints.isEmpty());
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

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(5);}
	 * ({@code p = operator.new(8); C::C(p, 5); return p;}), so the constructor {@code CALL} carries the
	 * literal {@code 5} (an integer-typed constant varnode) as its third input. Grounds the
	 * {@code #37-10c} integer-constant argument rendering.
	 */
	private HighFunction decompileMakeWithConstArg() throws Exception {
		builder = new ProgramBuilder("ctorConstArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// make(): push rsi; sub rsp,0x20; mov ecx,8 (size); call op_new; mov rsi,rax (save this);
		//   mov rcx,rax (this); mov edx,5 (ctor arg1 = 5); call ctor; mov rax,rsi (return this);
		//   add rsp,0x20; pop rsi; ret
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 ba 05 00 00 00 " +
				"e8 e1 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 40, classCPtr);
		builder.disassemble(MAKE, 40, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(-1);} where the
	 * constructor takes a 4-byte signed {@code int}, so the constructor {@code CALL} carries the literal
	 * as a size-4 signed-integer constant varnode whose {@link ghidra.program.model.pcode.Varnode#getOffset()}
	 * is {@code 0xffffffff}. Grounds the {@code #37-10d} sign-extension fix: the raw offset
	 * {@code Long.toString}s as {@code 4294967295}, but the rendered hint must be {@code -1}. Same
	 * 40-byte body as the {@code #37-10c} fixture with {@code mov edx,5} replaced by {@code mov edx,-1}
	 * ({@code ba ff ff ff ff}).
	 */
	private HighFunction decompileMakeWithSignedNegArg() throws Exception {
		builder = new ProgramBuilder("ctorSignedNegArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 ba ff ff ff ff " +
				"e8 e1 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit 4-byte signed int argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new IntegerDataType());
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 40, classCPtr);
		builder.disassemble(MAKE, 40, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(~0ull);} where the
	 * constructor takes an 8-byte {@code unsigned long long}, so the constructor {@code CALL} carries the
	 * literal as a size-8 unsigned constant varnode whose offset is {@code 0xffffffffffffffff}. Grounds
	 * the {@code #37-10d} unsigned full-range rendering: that offset {@code Long.toString}s as
	 * {@code -1}, but the rendered hint must be {@code 18446744073709551615}. The body sets the argument
	 * with {@code mov rdx,-1} ({@code 48 c7 c2 ff ff ff ff}), a 7-byte instruction, so the body is 42
	 * bytes and the constructor call's rel32 is recomputed to {@code df 01 00 00}.
	 */
	private HighFunction decompileMakeWithUnsignedWideArg() throws Exception {
		builder = new ProgramBuilder("ctorUnsignedWideArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 48 c7 c2 ff ff ff ff " +
				"e8 df 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit 8-byte unsigned long long argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new UnsignedLongLongDataType());
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 42, classCPtr);
		builder.disassemble(MAKE, 42, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(value);} where the
	 * constructor takes a {@code bool}, so the constructor {@code CALL} carries the literal as a size-1
	 * {@link BooleanDataType} constant ({@code 1} for {@code true}, {@code 0} for {@code false}). Grounds
	 * the {@code #37-10e} boolean rendering: the constant must render {@code true}/{@code false}, not its
	 * decimal {@code 1}/{@code 0}. Same 40-byte body as the {@code #37-10c} fixture with the {@code mov
	 * edx,imm} loading the boolean value.
	 */
	private HighFunction decompileMakeWithBoolArg(boolean value) throws Exception {
		builder = new ProgramBuilder("ctorBoolArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String immByte = value ? "01" : "00";
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 ba " + immByte +
				" 00 00 00 e8 e1 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit bool argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new BooleanDataType());
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 40, classCPtr);
		builder.disassemble(MAKE, 40, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(value);} where the
	 * constructor takes a {@code char}, so the constructor {@code CALL} carries the literal as a size-1
	 * {@link CharDataType} constant whose offset is the character byte. Grounds the {@code #37-10f} char
	 * rendering: a printable byte ({@code 0x41}) must render {@code 'A'} and a control byte ({@code 0x0a})
	 * must render the escaped {@code '\n'}, not their decimals {@code 65}/{@code 10}. Same 40-byte body as
	 * the {@code #37-10e} fixture with the {@code mov edx,imm} loading the character byte.
	 */
	private HighFunction decompileMakeWithCharArg(int charByte) throws Exception {
		builder = new ProgramBuilder("ctorCharArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String immByte = String.format("%02x", charByte & 0xff);
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 ba " + immByte +
				" 00 00 00 e8 e1 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit char argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new CharDataType());
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 40, classCPtr);
		builder.disassemble(MAKE, 40, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(value);} where the
	 * constructor takes a 4-byte {@code enum Color { RED=0, GREEN=2, BLUE=3 }}, so the constructor
	 * {@code CALL} carries the literal as a size-4 {@link EnumDataType} constant whose offset is the
	 * underlying value. Grounds the {@code #37-10g} enum rendering: value {@code 2} must render
	 * {@code Color::GREEN} (not the decimal {@code 2}), and a value naming no member (e.g. {@code 7}) must
	 * decline. Same 40-byte body as the {@code #37-10e} fixture with the {@code mov edx,imm} loading the
	 * enum value.
	 */
	private HighFunction decompileMakeWithEnumArg(int enumValue) throws Exception {
		builder = new ProgramBuilder("ctorEnumArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String immByte = String.format("%02x", enumValue & 0xff);
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 ba " + immByte +
				" 00 00 00 e8 e1 01 00 00 48 89 f0 48 83 c4 20 5e c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		EnumDataType color = new EnumDataType("Color", 4);
		color.add("RED", 0);
		color.add("GREEN", 2);
		color.add("BLUE", 3);
		builder.addDataType(color);
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType());
		// the constructor takes the this receiver and one explicit enum argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr, color);
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 40, classCPtr);
		builder.disassemble(MAKE, 40, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(value);} where the
	 * constructor takes a wide-char type ({@code wchar_t}/{@code char16_t}/{@code char32_t}), so the
	 * constructor {@code CALL} carries the literal as a wide-char constant whose offset is the code unit.
	 * Grounds the {@code #37-10h} wide-char rendering: a printable unit renders the prefixed literal
	 * ({@code L'A'}/{@code u'A'}/{@code U'A'}) and a non-ASCII unit renders a width-padded hex escape, not
	 * a bare decimal. Same 40-byte body as the {@code #37-10f} fixture, but the full {@code mov edx,imm32}
	 * 4-byte little-endian immediate carries the (possibly multi-byte) code unit.
	 */
	private HighFunction decompileMakeWithWideCharArg(DataType wideCharType, int value) throws Exception {
		builder = new ProgramBuilder("ctorWideCharArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String imm = String.format("%02x %02x %02x %02x", value & 0xff, (value >> 8) & 0xff,
			(value >> 16) & 0xff, (value >> 24) & 0xff);
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 ba " + imm +
				" e8 e1 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit wide-char argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			wideCharType);
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 40, classCPtr);
		builder.disassemble(MAKE, 40, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(value);} where the
	 * constructor takes a 4-byte {@code float}, so the constructor {@code CALL} carries the literal as a
	 * size-4 {@link FloatDataType} constant whose offset is the IEEE-754 single-precision bit pattern.
	 * Grounds the {@code #37-10i} float rendering: bits {@code 0x40200000} must render {@code 2.5f} (not the
	 * bit pattern) and a non-finite value (e.g. a NaN {@code 0x7fc00000}) must decline. In the Windows x64
	 * ABI a {@code float} argument is passed in {@code xmm1} (the slot positionally after the {@code this}
	 * pointer in {@code rcx}); the body loads the bits into {@code eax} and moves them to {@code xmm1} via
	 * {@code movd}, so the 44-byte body differs from the integer fixtures only in that argument-load
	 * sequence (and the recomputed constructor-call displacement).
	 */
	private HighFunction decompileMakeWithFloatArg(int floatBits) throws Exception {
		builder = new ProgramBuilder("ctorFloatArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String imm = String.format("%02x %02x %02x %02x", floatBits & 0xff, (floatBits >> 8) & 0xff,
			(floatBits >> 16) & 0xff, (floatBits >> 24) & 0xff);
		// ... mov eax,imm32 ; movd xmm1,eax ; call ctor (rel32 0x1dd from the shifted call site) ...
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 b8 " + imm +
				" 66 0f 6e c8 e8 dd 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit float argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new FloatDataType());
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 44, classCPtr);
		builder.disassemble(MAKE, 44, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(value);} where the
	 * constructor takes an 8-byte {@code double}, so the constructor {@code CALL} carries the literal as a
	 * size-8 {@link DoubleDataType} constant whose offset is the IEEE-754 double-precision bit pattern.
	 * Grounds the {@code #37-10i} double rendering: bits {@code 0x4004000000000000} must render the
	 * unsuffixed {@code 2.5} (not the bit pattern). In the Windows x64 ABI a {@code double} argument is
	 * passed in {@code xmm1}; the body loads the 64-bit bits into {@code rax} and moves them to {@code xmm1}
	 * via {@code movq}, so the 50-byte body differs from the float fixture only in the wider immediate and
	 * the {@code movq} (and the recomputed constructor-call displacement).
	 */
	private HighFunction decompileMakeWithDoubleArg(long doubleBits) throws Exception {
		builder = new ProgramBuilder("ctorDoubleArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		StringBuilder imm = new StringBuilder();
		for (int i = 0; i < 8; i++) {
			imm.append(String.format("%02x ", (doubleBits >> (i * 8)) & 0xff));
		}
		// ... mov rax,imm64 ; movq xmm1,rax ; call ctor (rel32 0x1d7 from the shifted call site) ...
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 48 b8 " + imm +
				"66 48 0f 6e c8 e8 d7 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit double argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new DoubleDataType());
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 50, classCPtr);
		builder.disassemble(MAKE, 50, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C("...");} where the
	 * constructor takes a {@code char *}, the global NUL-terminated string bytes are
	 * {@code stringBytesHex}, and returns the {@code make} decompiled {@link HighFunction}. A convenience
	 * wrapper over {@link #decompileMakeWithPtrArg} fixing the pointee type to {@code char}. Grounds the
	 * {@code #37-10k} string-literal rendering.
	 */
	private HighFunction decompileMakeWithStringPtrArg(String stringBytesHex) throws Exception {
		return decompileMakeWithPtrArg(new PointerDataType(new CharDataType()), stringBytesHex);
	}

	/**
	 * Builds a fresh program whose {@code C* make()} does {@code return new C(p);} where the constructor
	 * takes the pointer type {@code ptrParamType} and {@code p} is the address {@code 0x402000} of a
	 * global memory region initialised to {@code dataBytesHex}. A pointer-typed global-address argument is
	 * <em>not</em> a constant varnode — the decompiler resolves the global address (loaded via
	 * {@code mov rdx,imm64}) into a typed pointer temp with no backing symbol, whose
	 * {@link ghidra.program.model.pcode.HighVariable} is a {@code HighOther} carrying the {@code "UNNAMED"}
	 * placeholder name, defined by a {@code COPY} of the {@code const}-space address. Used both to render a
	 * {@code char *} as a string literal ({@code #37-10k}) and, with a non-char pointee, to confirm the
	 * string renderer's pointer-to-char gate declines. The body is 45 bytes: the integer fixtures' 5-byte
	 * {@code mov edx,imm32} becomes a 10-byte {@code mov rdx,imm64} (the absolute address), so the
	 * constructor-call displacement is recomputed to {@code dc 01 00 00}.
	 */
	private HighFunction decompileMakeWithPtrArg(DataType ptrParamType, String dataBytesHex)
			throws Exception {
		builder = new ProgramBuilder("ctorPtrArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		builder.createMemory("data", "0x402000", 0x100);
		builder.setBytes("0x402000", dataBytesHex, false);
		builder.setBytes(MAKE,
			"56 48 83 ec 20 b9 08 00 00 00 e8 f1 00 00 00 48 89 c6 48 89 c1 48 ba " +
				"00 20 40 00 00 00 00 00 e8 dc 01 00 00 48 89 f0 48 83 c4 20 5e c3",
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
		// the constructor takes the this receiver and one explicit pointer argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			ptrParamType);
		Function make = builder.createEmptyFunction("make", null, conv, MAKE, 45, classCPtr);
		builder.disassemble(MAKE, 45, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);

		return decompileToHighFunction(program, make);
	}
}
