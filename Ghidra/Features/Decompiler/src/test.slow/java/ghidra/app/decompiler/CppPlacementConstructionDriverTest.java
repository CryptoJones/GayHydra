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
	public void testRendersPlacementWithStringPtrArgument() throws Exception {
		// A const char* string-pointer argument is not a constant varnode: the decompiler resolves the
		// global address (0x402000, holding "Hi\0") into an unnamed char* temp (a HighOther). #37-10k
		// traces that temp's COPY def-chain to the constant address and reads the NUL-terminated bytes,
		// so it renders the string literal new (param_1) C("Hi") rather than declining.
		Fixture fixture = placementWithStringPtrArgFixture("48 69 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("char* argument must render as the string literal \"Hi\", not UNNAMED",
			"new (param_1) C(\"Hi\")", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithEscapedStringPtrArgument() throws Exception {
		// Bytes 0x41 0x09 0x01 0x00 at 0x402000: 'A', a tab (a named C escape), and 0x01 (no named
		// escape, so a 3-digit octal escape \001 — never \x01, which is greedy in a string literal),
		// then the NUL terminator. The string must render new (param_1) C("A\t\001").
		Fixture fixture = placementWithStringPtrArgFixture("41 09 01 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("non-printable string bytes must escape (named escape + 3-digit octal)",
			"new (param_1) C(\"A\\t\\001\")", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesUnnamedNonCharPointerArgument() throws Exception {
		// An unnamed non-char* pointer argument (here an int*, the same global-address load as the
		// string fixture but a different pointee type) is still a HighOther named UNNAMED, but the
		// string renderer is gated on a pointer-to-char, so it does not apply. With no name, no string,
		// and a non-constant varnode, the whole hint declines — the never-wrong contract still holds for
		// a genuinely unnameable pointer argument.
		Fixture fixture =
			placementWithPtrArgFixture(new PointerDataType(new IntegerDataType()), "48 69 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unnamed non-char* pointer argument must yield no hints", hints.isEmpty());
	}

	@Test
	public void testRendersPlacementWithWideStringPtrArgument() throws Exception {
		// A const wchar_t* argument (grounded #37-10l): the global address 0x402000 holds UTF-16LE "Hi\0"
		// (48 00 69 00 00 00), loaded into an unnamed wchar_t* temp (a HighOther). The pointee is
		// WideCharDataType (2 bytes on this _X64 spec), so the renderer reads 2-byte code units to the
		// zero terminator and emits the L-prefixed wide string literal new (param_1) C(L"Hi").
		Fixture fixture =
			placementWithPtrArgFixture(new PointerDataType(new WideCharDataType()), "48 00 69 00 00 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("wchar_t* argument must render as the wide string literal L\"Hi\"",
			"new (param_1) C(L\"Hi\")", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithChar16StringPtrArgument() throws Exception {
		// A const char16_t* argument: UTF-16LE "Hi\0" at 0x402000, pointee WideChar16DataType (2 bytes),
		// rendered with the u prefix: new (param_1) C(u"Hi").
		Fixture fixture = placementWithPtrArgFixture(new PointerDataType(new WideChar16DataType()),
			"48 00 69 00 00 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("char16_t* argument must render as the wide string literal u\"Hi\"",
			"new (param_1) C(u\"Hi\")", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithChar32StringPtrArgument() throws Exception {
		// A const char32_t* argument: UTF-32LE "Hi\0" at 0x402000 (48 00 00 00 69 00 00 00 00 00 00 00),
		// pointee WideChar32DataType (4 bytes), so the renderer reads 4-byte code units and emits the
		// U-prefixed literal new (param_1) C(U"Hi").
		Fixture fixture = placementWithPtrArgFixture(new PointerDataType(new WideChar32DataType()),
			"48 00 00 00 69 00 00 00 00 00 00 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("char32_t* argument must render as the wide string literal U\"Hi\"",
			"new (param_1) C(U\"Hi\")", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithEscapedWideStringPtrArgument() throws Exception {
		// A const char16_t* with units 'A' (0x41), tab (0x09 -> named escape \t), 0x01 (no named escape,
		// a control unit <= 0x7f -> 3-digit octal \001), and 0x20ac (the Euro sign, a high code point ->
		// a non-greedy 4-hex-digit universal-character-name €), then the terminator. UTF-16LE bytes:
		// 41 00 09 00 01 00 ac 20 00 00. Must render new (param_1) C(u"A\t\001€").
		Fixture fixture = placementWithPtrArgFixture(new PointerDataType(new WideChar16DataType()),
			"41 00 09 00 01 00 ac 20 00 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("wide non-ASCII units must escape (named + 3-digit octal + \\uXXXX)",
			"new (param_1) C(u\"A\\t\\001\\u20ac\")", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesPlacementWideStringPtrWithLoneSurrogate() throws Exception {
		// A const char16_t* whose first unit is a lone surrogate 0xd800 (UTF-16LE bytes 00 d8), which has
		// no well-formed universal-character-name. Rather than emit an ill-formed literal the renderer
		// declines the unit, so argumentExpr returns null and the whole hint declines — the never-wrong
		// contract holds for an unrepresentable wide code unit.
		Fixture fixture =
			placementWithPtrArgFixture(new PointerDataType(new WideChar16DataType()), "00 d8 00 00");
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a lone-surrogate wide code unit must yield no hints", hints.isEmpty());
	}

	@Test
	public void testRendersPlacementWithAddExpressionArgument() throws Exception {
		// new (param_1) C(v + 7): the ctor's value arg is an unnamed temp defined by INT_ADD of the named
		// param_2 and the constant 7 (grounded #37-10m), so it renders the binary expression, not UNNAMED.
		Fixture fixture = placementWithBinaryArgFixture("48 8d 56 07", 4); // lea rdx,[rsi+7]
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a compound add argument must render as the binary expression param_2 + 7",
			"new (param_1) C(param_2 + 7)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithBitwiseAndExpressionArgument() throws Exception {
		// new (param_1) C(v & 7): INT_AND of the named param_2 and the constant 7 renders param_2 & 7.
		Fixture fixture = placementWithBinaryArgFixture("48 89 f2 48 83 e2 07", 7); // mov rdx,rsi; and rdx,7
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a compound bitwise-and argument must render as param_2 & 7",
			"new (param_1) C(param_2 & 7)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithShiftLeftExpressionArgument() throws Exception {
		// new (param_1) C(v << 3): INT_LEFT of the named param_2 and the constant 3 renders param_2 << 3.
		Fixture fixture = placementWithBinaryArgFixture("48 89 f2 48 c1 e2 03", 7); // mov rdx,rsi; shl rdx,3
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a compound shift-left argument must render as param_2 << 3",
			"new (param_1) C(param_2 << 3)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithArithmeticShiftRightExpressionArgument() throws Exception {
		// new (param_1) C(v >> 3) on a signed operand: an arithmetic shift right is INT_SRIGHT, whose left
		// operand is the named param_2 directly (no cast), so it renders param_2 >> 3 (grounded #37-10m).
		Fixture fixture = placementWithBinaryArgFixture("48 89 f2 48 c1 fa 03", 7); // mov rdx,rsi; sar rdx,3
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("an arithmetic shift-right argument must render as param_2 >> 3",
			"new (param_1) C(param_2 >> 3)", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesPlacementLogicalShiftRightExpressionArgument() throws Exception {
		// new (param_1) C(v >> 3) where the decompiler emits a *logical* INT_RIGHT: the signed param_2 is
		// first CAST to unsigned, so the left operand is a cast temp, not a leaf. Rendering a bare
		// param_2 >> 3 over the signed operand would silently become an arithmetic shift, so the binary
		// renderer declines the cast-wrapped operand and the whole hint declines (never-wrong, #37-10m).
		Fixture fixture = placementWithBinaryArgFixture("48 89 f2 48 c1 ea 03", 7); // mov rdx,rsi; shr rdx,3
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("a logical shift-right whose operand is cast to unsigned must yield no hints",
			hints.isEmpty());
	}

	@Test
	public void testRendersPlacementWithSignedDivisionExpressionArgument() throws Exception {
		// new (param_1) C(v / 7) on a signed operand: INT_SDIV's left operand is the named param_2 directly
		// (no cast), so it renders param_2 / 7 (grounded #37-10n).
		Fixture fixture = placementWithBinaryArgFixture(
			"48 89 f0 48 99 49 c7 c0 07 00 00 00 49 f7 f8 48 89 c2", 18); // signed idiv by 7
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a signed division argument must render as param_2 / 7",
			"new (param_1) C(param_2 / 7)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithSignedRemainderExpressionArgument() throws Exception {
		// new (param_1) C(v % 7) on a signed operand: INT_SREM's left operand is the named param_2 directly
		// (no cast), so it renders param_2 % 7 (grounded #37-10n).
		Fixture fixture = placementWithBinaryArgFixture(
			"48 89 f0 48 99 49 c7 c0 07 00 00 00 49 f7 f8", 15); // signed idiv by 7, take remainder
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a signed remainder argument must render as param_2 % 7",
			"new (param_1) C(param_2 % 7)", hints.get(0).rendering());
	}

	@Test
	public void testDeclinesPlacementUnsignedDivisionExpressionArgument() throws Exception {
		// new (param_1) C(v / 7) where the decompiler emits an *unsigned* INT_DIV: the signed param_2 is
		// first CAST to unsigned, so the left operand is a cast temp, not a leaf, and the whole hint
		// declines (never-wrong, grounded #37-10n).
		Fixture fixture = placementWithBinaryArgFixture(
			"48 89 f0 48 31 d2 49 c7 c0 07 00 00 00 49 f7 f0 48 89 c2", 19); // unsigned div by 7
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unsigned division whose operand is cast to unsigned must yield no hints",
			hints.isEmpty());
	}

	@Test
	public void testDeclinesPlacementUnsignedRemainderExpressionArgument() throws Exception {
		// new (param_1) C(v % 7) where the decompiler emits an *unsigned* INT_REM: the signed param_2 is
		// first CAST to unsigned, so the left operand is a cast temp, not a leaf, and the whole hint
		// declines (never-wrong, grounded #37-10n).
		Fixture fixture = placementWithBinaryArgFixture(
			"48 89 f0 48 31 d2 49 c7 c0 07 00 00 00 49 f7 f0", 16); // unsigned div by 7, take remainder
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unsigned remainder whose operand is cast to unsigned must yield no hints",
			hints.isEmpty());
	}

	@Test
	public void testRendersPlacementWithArithmeticNegationExpressionArgument() throws Exception {
		// new (param_1) C(-v): an arithmetic unary minus is INT_2COMP of the named param_2, a
		// single-operand op whose result is the same width as the operand (no widening cast), so it
		// renders -param_2 (grounded #37-10o).
		Fixture fixture = placementWithBinaryArgFixture("48 89 f2 48 f7 da", 6); // mov rdx,rsi; neg rdx
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("an arithmetic negation argument must render as -param_2",
			"new (param_1) C(-param_2)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithBitwiseComplementExpressionArgument() throws Exception {
		// new (param_1) C(~v): a bitwise complement is INT_NEGATE of the named param_2, a single-operand
		// op whose result is the same width as the operand, so it renders ~param_2 (grounded #37-10o).
		Fixture fixture = placementWithBinaryArgFixture("48 89 f2 48 f7 d2", 6); // mov rdx,rsi; not rdx
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("a bitwise complement argument must render as ~param_2",
			"new (param_1) C(~param_2)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithEqualityExpressionArgument() throws Exception {
		// new (param_1) C(v == 7): the one-byte boolean of INT_EQUAL(param_2, 7) is widened to the longlong
		// arg slot by an INT_ZEXT, so the comparison sits one hop below the value varnode's def; peeling
		// exactly that one extension renders param_2 == 7 (grounded #37-10p).
		Fixture fixture = placementWithBinaryArgFixture(
			"48 83 fe 07 0f 94 c2 48 0f b6 d2", 11); // cmp rsi,7; sete dl; movzx rdx,dl
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("an equality argument must render as param_2 == 7",
			"new (param_1) C(param_2 == 7)", hints.get(0).rendering());
	}

	@Test
	public void testRendersPlacementWithInequalityExpressionArgument() throws Exception {
		// new (param_1) C(v != 7): INT_NOTEQUAL(param_2, 7) widened by INT_ZEXT; peeling the extension
		// renders param_2 != 7 (grounded #37-10p).
		Fixture fixture = placementWithBinaryArgFixture(
			"48 83 fe 07 0f 95 c2 48 0f b6 d2", 11); // cmp rsi,7; setne dl; movzx rdx,dl
		HighFunction highFunction = decompileToHighFunction(fixture.program, fixture.make);

		CppPlacementConstructionDriver driver =
			new CppPlacementConstructionDriver(new CppDecompilerHints(), typeSystemWithC());
		List<RenderedPlacement> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered placement construction", 1, hints.size());
		assertEquals("an inequality argument must render as param_2 != 7",
			"new (param_1) C(param_2 != 7)", hints.get(0).rendering());
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

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C("...")} where the constructor takes a
	 * {@code char *} and the global NUL-terminated string bytes are {@code stringBytesHex}. A convenience
	 * wrapper over {@link #placementWithPtrArgFixture} fixing the pointee type to {@code char}. Grounds the
	 * {@code #37-10k} string-literal rendering.
	 */
	private Fixture placementWithStringPtrArgFixture(String stringBytesHex) throws Exception {
		return placementWithPtrArgFixture(new PointerDataType(new CharDataType()), stringBytesHex);
	}

	/**
	 * Builds {@code C* makeAt(void* buf)} doing {@code new (buf) C(p)} where the constructor takes the
	 * pointer type {@code ptrParamType} and {@code p} is the address {@code 0x402000} of a global memory
	 * region initialised to {@code dataBytesHex}. A pointer-typed global-address argument is <em>not</em> a
	 * constant varnode — the decompiler resolves the global address (loaded via {@code mov rdx,imm64}) into
	 * a typed pointer temp with no backing symbol, whose
	 * {@link ghidra.program.model.pcode.HighVariable} is a {@code HighOther} carrying the {@code "UNNAMED"}
	 * placeholder name, defined by a {@code COPY} of the {@code const}-space address. Used both to render a
	 * {@code char *} as a string literal ({@code #37-10k}) and, with a non-char pointee, to confirm the
	 * string renderer's pointer-to-char gate declines. The body is 32 bytes: the {@code #37-10c} fixture's
	 * 5-byte {@code mov edx,imm32} becomes a 10-byte {@code mov rdx,imm64}, so the constructor-call
	 * displacement is recomputed to {@code e1 01 00 00}.
	 */
	private Fixture placementWithPtrArgFixture(DataType ptrParamType, String dataBytesHex)
			throws Exception {
		builder = new ProgramBuilder("placementPtrArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		builder.createMemory("data", "0x402000", 0x100);
		builder.setBytes("0x402000", dataBytesHex, false);
		builder.setBytes(MAKE,
			"48 89 ca b9 08 00 00 00 e8 f3 00 00 00 48 89 c1 48 ba " +
				"00 20 40 00 00 00 00 00 e8 e1 01 00 00 c3",
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
		// the constructor takes the this receiver and one explicit pointer argument
		builder.createEmptyFunction("C", "C", conv, CTOR, 1, VoidDataType.dataType, classCPtr,
			ptrParamType);
		Function make =
			builder.createEmptyFunction("makeAt", null, conv, MAKE, 32, classCPtr, voidPtr);
		builder.disassemble(MAKE, 32, false);
		builder.disassemble(OP_NEW, 1, false);
		builder.disassemble(CTOR, 1, false);
		return new Fixture(program, make);
	}

	/**
	 * Builds {@code C* makeAt(void* buf, longlong v)} doing {@code new (buf) C(v OP k);}, where
	 * {@code computeHex} is the {@code computeLen}-byte instruction sequence that writes {@code rdx = f(rsi)}
	 * (the saved argument {@code v}) and so makes the constructor {@code CALL}'s value input an unnamed
	 * temporary defined by the corresponding arithmetic/bitwise/shift p-code op over the named
	 * {@code param_2} and a constant. Grounds the {@code #37-10m} compound-expression rendering for the
	 * placement form. The body is a fixed 24-byte prefix ({@code push rsi; push rdi; mov rsi,rdx;
	 * mov rdx,rcx; mov ecx,8; call op_new; mov rdi,rax; mov rcx,rax}), the variable {@code computeHex}, the
	 * 5-byte constructor {@code call} (rel32 recomputed to {@code 483 - computeLen}), and a fixed 6-byte
	 * suffix ({@code mov rax,rdi; pop rdi; pop rsi; ret}), so the total body length is {@code 35 + computeLen}.
	 * The placement allocation takes two args ({@code size_t, void* buffer}) exactly as the other placement
	 * fixtures, so the {@code mov rdx,rcx} forwards {@code buf} into the op_new buffer register.
	 */
	private Fixture placementWithBinaryArgFixture(String computeHex, int computeLen) throws Exception {
		builder = new ProgramBuilder("placementBinArgDrv", ProgramBuilder._X64);
		builder.createMemory("text", MAKE, 0x300);
		int rel = 483 - computeLen;
		String callHex = String.format("e8 %02x %02x 00 00", rel & 0xff, (rel >> 8) & 0xff);
		builder.setBytes(MAKE,
			"56 57 48 89 d6 48 89 ca b9 08 00 00 00 e8 ee 00 00 00 48 89 c7 48 89 c1 " + computeHex +
				" " + callHex + " 48 89 f8 5f 5e c3",
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
		int bodyLen = 35 + computeLen;
		Function make = builder.createEmptyFunction("makeAt", null, conv, MAKE, bodyLen, classCPtr,
			voidPtr, new LongLongDataType());
		builder.disassemble(MAKE, bodyLen, false);
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
