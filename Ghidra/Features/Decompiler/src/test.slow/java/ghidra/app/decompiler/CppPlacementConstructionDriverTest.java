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
