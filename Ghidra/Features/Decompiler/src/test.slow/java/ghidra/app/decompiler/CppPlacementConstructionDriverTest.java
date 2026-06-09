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
import ghidra.app.util.cpp.CppPlacementConstructionDriver;
import ghidra.app.util.cpp.CppPlacementConstructionDriver.RenderedPlacement;
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
 * Integration coverage for the Rec 37 {@code #37-9e-b-2} {@link CppPlacementConstructionDriver}, driven
 * through the Rec 30 headless {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0038).
 *
 * <p>The fixture is a hand-assembled x86-64 (Windows-ABI) factory {@code C* makeAt(void* buf)} whose
 * body is the non-elided placement-{@code new} idiom {@code p = operator.new(8, buf); C::C(p); ...}.
 * With {@code C} modelled and the ctor named {@code C::C} and the allocation named {@code operator.new}
 * (a two-arg placement overload), the driver renders {@code new (param_1) C()}. An unmodelled class, an
 * allocation that is not {@code operator new}, and a heap {@code new} (no buffer operand) each yield no
 * hint.
 */
public class CppPlacementConstructionDriverTest extends AbstractDecompilerHighFunctionTest {

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
	public void testRendersPlacement() throws Exception {
		Fixture fixture = placementFixture("operator.new");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("wrong rendered placement new", "new (param_1) C()",
			hints.get(0).rendering());
		assertNotNull("hint carried no construction-site address", hints.get(0).site());
	}

	@Test
	public void testRendersPlacementWithArgument() throws Exception {
		Fixture fixture = placementWithArgFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("constructor argument was not threaded into the rendering",
			"new (param_1) C(param_2)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithConstantArgument() throws Exception {
		Fixture fixture = placementWithConstArgFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("integer constant argument was not rendered", "new (param_1) C(5)",
			hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithBooleanTrueArgument() throws Exception {
		Fixture fixture = placementWithBoolArgFixture(true);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("boolean constant 1 must render as true, not a decimal", "new (param_1) C(true)",
			hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithBooleanFalseArgument() throws Exception {
		Fixture fixture = placementWithBoolArgFixture(false);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("boolean constant 0 must render as false, not a decimal", "new (param_1) C(false)",
			hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithCharArgument() throws Exception {
		Fixture fixture = placementWithCharArgFixture(0x41);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("printable char constant must render as a 'A' literal, not the decimal 65",
			"new (param_1) C('A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithEscapedCharArgument() throws Exception {
		Fixture fixture = placementWithCharArgFixture(0x0a);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a control char must render as its escaped C literal, not the decimal 10",
			"new (param_1) C('\\n')", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithEnumArgument() throws Exception {
		Fixture fixture = placementWithEnumArgFixture(2);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("enum constant must render as its qualified member name, not the decimal 2",
			"new (param_1) C(Color::GREEN)", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesPlacementEnumArgumentWithUnnamedValue() throws Exception {
		// 7 is not RED(0)/GREEN(2)/BLUE(3): Enum.getName returns null, so rather than fabricate a name
		// or render a bare decimal that would mislead, the whole hint declines.
		Fixture fixture = placementWithEnumArgFixture(7);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an enum value naming no member must yield no placement hints", hints.isEmpty());
	}

	@Test
	public void testRendersPlacementWithWideCharArgument() throws Exception {
		Fixture fixture = placementWithWideCharArgFixture(new WideCharDataType(), 0x41);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("wchar_t constant must render as the L-prefixed literal L'A', not a decimal",
			"new (param_1) C(L'A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithChar16Argument() throws Exception {
		Fixture fixture = placementWithWideCharArgFixture(new WideChar16DataType(), 0x41);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("char16_t constant must render as the u-prefixed literal u'A', not a decimal",
			"new (param_1) C(u'A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithChar32Argument() throws Exception {
		Fixture fixture = placementWithWideCharArgFixture(new WideChar32DataType(), 0x41);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("char32_t constant must render as the U-prefixed literal U'A', not a decimal",
			"new (param_1) C(U'A')", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithNonAsciiWideCharArgument() throws Exception {
		// U+20AC (euro) is not printable ASCII: a char16_t constant must render as a width-padded hex
		// escape u'\x20ac', not a bare decimal and not a malformed universal-character-name.
		Fixture fixture = placementWithWideCharArgFixture(new WideChar16DataType(), 0x20ac);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a non-printable wide-char unit must render as a width-padded hex escape",
			"new (param_1) C(u'\\x20ac')", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithFloatArgument() throws Exception {
		// 0x40200000 is the IEEE-754 single-precision bit pattern for 2.5f.
		Fixture fixture = placementWithFloatArgFixture(0x40200000);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("float constant must render as the f-suffixed literal 2.5f, not a bit pattern",
			"new (param_1) C(2.5f)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithDoubleArgument() throws Exception {
		// 0x4004000000000000 is the IEEE-754 double-precision bit pattern for 2.5.
		Fixture fixture = placementWithDoubleArgFixture(0x4004000000000000L);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("double constant must render as the unsuffixed literal 2.5, not a bit pattern",
			"new (param_1) C(2.5)", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesPlacementNonFiniteFloatArgument() throws Exception {
		// 0x7fc00000 is a quiet NaN: NaN has no bare C++ literal, so rather than emit the invalid text
		// "NaN" the whole hint declines, keeping the never-wrong contract.
		Fixture fixture = placementWithFloatArgFixture(0x7fc00000);
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a non-finite float value must yield no hints", hints.isEmpty());
	}

	@Test
	public void testRendersPlacementWithSignedNegativeArgument() throws Exception {
		Fixture fixture = placementWithSignedNegArgFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("negative signed constant must sign-extend, not render as a large unsigned number",
			"new (param_1) C(-1)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithUnsignedWideArgument() throws Exception {
		Fixture fixture = placementWithUnsignedWideArgFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("wide unsigned constant must render across the full unsigned range",
			"new (param_1) C(18446744073709551615)", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesWhenClassNotModelled() throws Exception {
		Fixture fixture = placementFixture("operator.new");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		// Empty type system: class C is not modelled, so nothing resolves.
		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), new CppTypeSystem());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unmodelled class must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDeclinesWhenAllocationNotOperatorNew() throws Exception {
		// The allocation carries a buffer (so the matcher matches) but is not operator new, so the
		// driver declines rather than rendering a placement new on an arbitrary allocator.
		Fixture fixture = placementFixture("make_in_place");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a non-operator-new allocation must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDeclinesHeapNew() throws Exception {
		// A heap new (no buffer operand) is the #37-9b form, not placement; the matcher declines it, so
		// the placement driver emits no hint.
		Fixture fixture = heapFixture();
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a heap new must yield no placement hints", hints.isEmpty());
	}

	@Test
	public void testDriverRejectsNulls() {
		try {
			new CppPlacementConstructionDriver(null, new CppTypeSystem());
			fail("null renderer must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
		try {
			new CppPlacementConstructionDriver(new CppDecompilerHints(), null);
			fail("null type system must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/** A type system modelling class {@code C}. */
	private CppTypeSystem typeSystemWithC() {
		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(classC);
		return typeSystem;
	}

	private record Fixture(Program program, Function make) {}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C()}: a two-arg placement allocation
	 * (named {@code allocName}) whose result feeds the {@code C::C} constructor.
	 */
	private Fixture placementFixture(String allocName) throws Exception {
		builder = new ProgramBuilder("placementDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// makeAt(void* buf): mov rdx,rcx; mov ecx,8; call alloc; mov rcx,rax; call C::C; ret
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 e8 eb 01 00 00 c3", false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction(allocName, null, conv, OP_NEW, 1, voidPtr,
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
	 * Builds {@code C* makeAt(void* buf, longlong v)} doing {@code new (buf) C(v)}: the placement
	 * allocation feeds {@code C::C(this, v)}, so the constructor {@code CALL} carries the explicit
	 * argument {@code v} as its third input (after the call target and the {@code this} receiver).
	 * Grounds the {@code #37-10a} constructor-argument threading.
	 */
	private Fixture placementWithArgFixture() throws Exception {
		builder = new ProgramBuilder("placementArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// makeAt(void* buf, longlong v):
		//   push rsi; push rdi; mov rdi,rdx (save v); mov rdx,rcx (buf -> alloc arg1);
		//   mov ecx,8 (size); call op_new; mov rsi,rax (save this); mov rcx,rax (this);
		//   mov rdx,rdi (v -> ctor arg1); call C::C; mov rax,rsi (return this); pop rdi; pop rsi; ret
		builder.setBytes(MAKE,
			"56 57 48 89 d7 48 89 ca b9 08 00 00 00 e8 ee 00 00 00 48 89 c6 48 89 c1 " +
				"48 89 fa e8 e0 01 00 00 48 89 f0 5f 5e c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit longlong argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new LongLongDataType());
		Function make = builder.createEmptyFunction("makeAt", null, conv, MAKE, 38, classCPtr,
			voidPtr, new LongLongDataType());
		builder.disassemble(MAKE, 38, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(5)}: the constructor {@code CALL}
	 * carries the literal {@code 5} (an integer-typed constant varnode) as its third input. Grounds the
	 * {@code #37-10c} integer-constant argument rendering.
	 */
	private Fixture placementWithConstArgFixture() throws Exception {
		builder = new ProgramBuilder("placementConstArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		// makeAt(void* buf): mov rdx,rcx (buf -> alloc arg1); mov ecx,8 (size); call op_new;
		//   mov rcx,rax (this); mov edx,5 (ctor arg1 = 5); call C::C; ret
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 ba 05 00 00 00 e8 e6 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit longlong argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new LongLongDataType());
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 27, classCPtr, voidPtr);
		builder.disassemble(MAKE, 27, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(true)} or {@code new (buf) C(false)}
	 * where the constructor takes a {@code bool}, so the constructor {@code CALL} carries the literal as a
	 * size-1 {@link BooleanDataType} constant whose offset is {@code 1} or {@code 0}. Grounds the
	 * {@code #37-10e} boolean rendering: the raw offset {@code Long.toString}s as {@code 1}/{@code 0}, but
	 * the rendered hint must be {@code true}/{@code false}. Same 27-byte body as the {@code #37-10c}
	 * fixture with the {@code mov edx,5} immediate replaced by {@code 1}/{@code 0}.
	 */
	private Fixture placementWithBoolArgFixture(boolean value) throws Exception {
		builder = new ProgramBuilder("placementBoolArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String immByte = value ? "01" : "00";
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 ba " + immByte +
				" 00 00 00 e8 e6 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit bool argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new BooleanDataType());
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 27, classCPtr, voidPtr);
		builder.disassemble(MAKE, 27, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C('A')} (or {@code C('\n')}) where the
	 * constructor takes a {@code char}, so the constructor {@code CALL} carries the literal as a size-1
	 * {@link CharDataType} constant whose offset is the character byte. Grounds the {@code #37-10f} char
	 * rendering: a printable byte ({@code 0x41}) must render {@code 'A'} and a control byte ({@code 0x0a})
	 * must render the escaped {@code '\n'}, not their decimals {@code 65}/{@code 10}. Same 27-byte body as
	 * the {@code #37-10c} fixture with the {@code mov edx,imm} loading the character byte.
	 */
	private Fixture placementWithCharArgFixture(int charByte) throws Exception {
		builder = new ProgramBuilder("placementCharArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String immByte = String.format("%02x", charByte & 0xff);
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 ba " + immByte +
				" 00 00 00 e8 e6 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit char argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new CharDataType());
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 27, classCPtr, voidPtr);
		builder.disassemble(MAKE, 27, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(Color::GREEN)} where the constructor
	 * takes a 4-byte {@code enum Color { RED=0, GREEN=2, BLUE=3 }}, so the constructor {@code CALL}
	 * carries the literal as a size-4 {@link EnumDataType} constant whose offset is the underlying value.
	 * Grounds the {@code #37-10g} enum rendering: value {@code 2} must render {@code Color::GREEN} (not the
	 * decimal {@code 2}), and a value naming no member (e.g. {@code 7}) must decline. Same 27-byte body as
	 * the {@code #37-10c} fixture with the {@code mov edx,imm} loading the enum value.
	 */
	private Fixture placementWithEnumArgFixture(int enumValue) throws Exception {
		builder = new ProgramBuilder("placementEnumArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String immByte = String.format("%02x", enumValue & 0xff);
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 ba " + immByte +
				" 00 00 00 e8 e6 01 00 00 c3",
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

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit enum argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr, color);
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 27, classCPtr, voidPtr);
		builder.disassemble(MAKE, 27, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(value)} where the constructor takes a
	 * wide-char type ({@code wchar_t}/{@code char16_t}/{@code char32_t}), so the constructor {@code CALL}
	 * carries the literal as a wide-char constant whose offset is the code unit. Grounds the
	 * {@code #37-10h} wide-char rendering: a printable unit renders the prefixed literal
	 * ({@code L'A'}/{@code u'A'}/{@code U'A'}) and a non-ASCII unit renders a width-padded hex escape. Same
	 * 27-byte body as the {@code #37-10c} fixture, but the full {@code mov edx,imm32} 4-byte little-endian
	 * immediate carries the (possibly multi-byte) code unit.
	 */
	private Fixture placementWithWideCharArgFixture(DataType wideCharType, int value) throws Exception {
		builder = new ProgramBuilder("placementWideCharArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String imm = String.format("%02x %02x %02x %02x", value & 0xff, (value >> 8) & 0xff,
			(value >> 16) & 0xff, (value >> 24) & 0xff);
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 ba " + imm + " e8 e6 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit wide-char argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			wideCharType);
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 27, classCPtr, voidPtr);
		builder.disassemble(MAKE, 27, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(-1)} where the constructor takes a
	 * 4-byte signed {@code int}, so the constructor {@code CALL} carries the literal as a size-4
	 * signed-integer constant whose offset is {@code 0xffffffff}. Grounds the {@code #37-10d}
	 * sign-extension fix: the raw offset {@code Long.toString}s as {@code 4294967295}, but the rendered
	 * hint must be {@code -1}. Same 27-byte body as the {@code #37-10c} fixture with {@code mov edx,5}
	 * replaced by {@code mov edx,-1} ({@code ba ff ff ff ff}).
	 */
	private Fixture placementWithSignedNegArgFixture() throws Exception {
		builder = new ProgramBuilder("placementSignedNegArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 ba ff ff ff ff e8 e6 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit 4-byte signed int argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new IntegerDataType());
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 27, classCPtr, voidPtr);
		builder.disassemble(MAKE, 27, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(~0ull)} where the constructor takes an
	 * 8-byte {@code unsigned long long}, so the constructor {@code CALL} carries the literal as a size-8
	 * unsigned constant whose offset is {@code 0xffffffffffffffff}. Grounds the {@code #37-10d} unsigned
	 * full-range rendering: that offset {@code Long.toString}s as {@code -1}, but the rendered hint must
	 * be {@code 18446744073709551615}. The argument is set with {@code mov rdx,-1}
	 * ({@code 48 c7 c2 ff ff ff ff}), a 7-byte instruction, so the body is 29 bytes and the constructor
	 * call's rel32 is recomputed to {@code e4 01 00 00}.
	 */
	private Fixture placementWithUnsignedWideArgFixture() throws Exception {
		builder = new ProgramBuilder("placementUnsignedWideArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 48 c7 c2 ff ff ff ff e8 e4 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit 8-byte unsigned long long argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new UnsignedLongLongDataType());
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 29, classCPtr, voidPtr);
		builder.disassemble(MAKE, 29, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(value)} where the constructor takes a
	 * 4-byte {@code float}, so the constructor {@code CALL} carries the literal as a size-4
	 * {@link FloatDataType} constant whose offset is the IEEE-754 single-precision bit pattern. Grounds the
	 * {@code #37-10i} float rendering: bits {@code 0x40200000} must render {@code 2.5f} and a non-finite
	 * value (e.g. a NaN {@code 0x7fc00000}) must decline. In the Windows x64 ABI a {@code float} argument is
	 * passed in {@code xmm1}; the body loads the bits into {@code eax} and moves them to {@code xmm1} via
	 * {@code movd}, so the 31-byte body differs from the {@code #37-10c} fixture only in that argument-load
	 * sequence (and the recomputed constructor-call displacement {@code e2 01 00 00}).
	 */
	private Fixture placementWithFloatArgFixture(int floatBits) throws Exception {
		builder = new ProgramBuilder("placementFloatArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		String imm = String.format("%02x %02x %02x %02x", floatBits & 0xff, (floatBits >> 8) & 0xff,
			(floatBits >> 16) & 0xff, (floatBits >> 24) & 0xff);
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 b8 " + imm +
				" 66 0f 6e c8 e8 e2 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit float argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new FloatDataType());
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 31, classCPtr, voidPtr);
		builder.disassemble(MAKE, 31, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(value)} where the constructor takes an
	 * 8-byte {@code double}, so the constructor {@code CALL} carries the literal as a size-8
	 * {@link DoubleDataType} constant whose offset is the IEEE-754 double-precision bit pattern. Grounds the
	 * {@code #37-10i} double rendering: bits {@code 0x4004000000000000} must render the unsuffixed
	 * {@code 2.5}. In the Windows x64 ABI a {@code double} argument is passed in {@code xmm1}; the body
	 * loads the 64-bit bits into {@code rax} and moves them to {@code xmm1} via {@code movq}, so the 37-byte
	 * body differs from the float fixture only in the wider immediate and the {@code movq} (and the
	 * recomputed constructor-call displacement {@code dc 01 00 00}).
	 */
	private Fixture placementWithDoubleArgFixture(long doubleBits) throws Exception {
		builder = new ProgramBuilder("placementDoubleArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		StringBuilder imm = new StringBuilder();
		for (int i = 0; i < 8; i++) {
			imm.append(String.format("%02x ", (doubleBits >> (i * 8)) & 0xff));
		}
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 48 b8 " + imm +
				"66 48 0f 6e c8 e8 dc 01 00 00 c3",
			false);
		builder.setBytes(OP_NEW, "c3", false);
		builder.setBytes(CTOR, "c3", false);

		classC = new StructureDataType("C", 8);
		builder.addDataType(classC);
		DataType classCPtr = new PointerDataType(classC);
		DataType voidPtr = new PointerDataType(new Undefined1DataType());
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		// the placement allocation takes TWO args: (size_t, void* buffer)
		builder.createEmptyFunction("operator.new", null, conv, OP_NEW, 1, voidPtr,
			new LongLongDataType(), voidPtr);
		// the constructor takes the this receiver and one explicit double argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			new DoubleDataType());
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 37, classCPtr, voidPtr);
		builder.disassemble(MAKE, 37, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/** Builds the {@code #37-9b} heap {@code C* make()} doing {@code new C()} (no buffer operand). */
	private Fixture heapFixture() throws Exception {
		builder = new ProgramBuilder("heapDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
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
