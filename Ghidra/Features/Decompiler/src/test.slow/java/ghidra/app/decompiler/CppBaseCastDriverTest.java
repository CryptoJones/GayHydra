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

import ghidra.app.util.cpp.CppBaseCastDriver;
import ghidra.app.util.cpp.CppBaseCastDriver.RenderedCast;
import ghidra.app.util.cpp.CppBaseClass;
import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppDecompilerHints;
import ghidra.app.util.cpp.CppTypeSystem;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * Integration coverage for the Rec 37 {@code #37-8b-2} {@link CppBaseCastDriver}, driven through the
 * Rec 30 headless {@link AbstractDecompilerHighFunctionTest} harness (DD-0023, DD-0035).
 *
 * <p>Each fixture is a hand-assembled x86-64 (Windows-ABI) one-instruction pointer adjustment typed
 * at both ends as two related classes: an upcast {@code Base* f(Derived*)} ({@code lea rax,[rcx+0x10]})
 * and a downcast {@code Derived* f(Base*)} ({@code lea rax,[rcx-0x10]}). With {@code Derived} modelled
 * as inheriting {@code Base} at offset {@code 0x10}, the driver renders
 * {@code static_cast<Base*>(param_1)} for the upcast and {@code static_cast<Derived*>(param_1)} for the
 * downcast. An unmodelled class, or a modelled class with no base edge at the recovered offset, each
 * yields no hint.
 */
public class CppBaseCastDriverTest extends AbstractDecompilerHighFunctionTest {

	private static final String FUNC = "0x401000";
	private static final int BASE_OFFSET = 0x10;

	private ProgramBuilder builder;
	private StructureDataType baseStruct;
	private StructureDataType derivedStruct;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRendersUpcast() throws Exception {
		HighFunction highFunction = decompileCast("48 8d 41 10 c3", true);

		CppBaseCastDriver driver =
			new CppBaseCastDriver(new CppDecompilerHints(), typeSystemWithInheritance());
		List<RenderedCast> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered cast", 1, hints.size());
		assertEquals("wrong rendered upcast", "static_cast<Base*>(param_1)",
			hints.get(0).rendering());
		assertNotNull("hint carried no cast-site address", hints.get(0).site());
	}

	@Test
	public void testRendersDowncast() throws Exception {
		HighFunction highFunction = decompileCast("48 8d 41 f0 c3", false);

		CppBaseCastDriver driver =
			new CppBaseCastDriver(new CppDecompilerHints(), typeSystemWithInheritance());
		List<RenderedCast> hints = driver.recognizeAndRender(highFunction);

		assertEquals("expected exactly one rendered cast", 1, hints.size());
		assertEquals("wrong rendered downcast", "static_cast<Derived*>(param_1)",
			hints.get(0).rendering());
	}

	@Test
	public void testDeclinesWhenClassNotModelled() throws Exception {
		HighFunction highFunction = decompileCast("48 8d 41 10 c3", true);

		// Empty type system: neither class is modelled, so nothing resolves.
		CppBaseCastDriver driver =
			new CppBaseCastDriver(new CppDecompilerHints(), new CppTypeSystem());
		List<RenderedCast> hints = driver.recognizeAndRender(highFunction);

		assertTrue("an unmodelled class must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDeclinesWhenNoBaseEdgeAtOffset() throws Exception {
		HighFunction highFunction = decompileCast("48 8d 41 10 c3", true);

		// Both classes modelled, but Derived records no inheritance edge at the recovered offset, so the
		// adjustment is not a base cast and the driver declines rather than emitting the renderer's
		// neutral fallback.
		CppTypeSystem typeSystem = new CppTypeSystem();
		typeSystem.defineClass(baseStruct);
		typeSystem.defineClass(derivedStruct);
		CppBaseCastDriver driver = new CppBaseCastDriver(new CppDecompilerHints(), typeSystem);
		List<RenderedCast> hints = driver.recognizeAndRender(highFunction);

		assertTrue("no base edge at the offset must yield no hints", hints.isEmpty());
	}

	@Test
	public void testDriverRejectsNulls() {
		try {
			new CppBaseCastDriver(null, new CppTypeSystem());
			fail("null renderer must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
		try {
			new CppBaseCastDriver(new CppDecompilerHints(), null);
			fail("null type system must be rejected");
		}
		catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/** A type system in which {@code Derived} inherits {@code Base} at offset {@code 0x10}. */
	private CppTypeSystem typeSystemWithInheritance() {
		CppTypeSystem typeSystem = new CppTypeSystem();
		CppClass base = typeSystem.defineClass(baseStruct);
		CppClass derived = typeSystem.defineClass(derivedStruct);
		derived.addBaseClass(new CppBaseClass(base, BASE_OFFSET, false, true));
		return typeSystem;
	}

	/**
	 * Builds a fresh program whose one-instruction body adjusts a class pointer by the base offset, and
	 * returns the decompiled {@link HighFunction}. {@code upcast} selects the {@code Derived* -> Base*}
	 * signature versus the {@code Base* -> Derived*} downcast signature. Stores the builder for disposal
	 * and the two structures for type-system modelling.
	 */
	private HighFunction decompileCast(String bytes, boolean upcast) throws Exception {
		builder = new ProgramBuilder("baseCastDrv", ProgramBuilder._X64);
		builder.createMemory("text", FUNC, 0x100);
		builder.setBytes(FUNC, bytes, false);

		baseStruct = new StructureDataType("Base", 8);
		derivedStruct = new StructureDataType("Derived", 0x18);
		builder.addDataType(baseStruct);
		builder.addDataType(derivedStruct);
		DataType basePtr = new PointerDataType(baseStruct);
		DataType derivedPtr = new PointerDataType(derivedStruct);
		Program program = builder.getProgram();
		String conv = program.getCompilerSpec().getDefaultCallingConvention().getName();

		DataType ret = upcast ? basePtr : derivedPtr;
		DataType arg = upcast ? derivedPtr : basePtr;
		int len = (bytes.length() + 1) / 3;
		Function fn = builder.createEmptyFunction("conv", null, conv, FUNC, len, ret, arg);
		builder.disassemble(FUNC, len, false);

		return decompileToHighFunction(program, fn);
	}
}
