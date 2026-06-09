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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import ghidra.app.util.cpp.CppBaseCastRecognizer;
import ghidra.app.util.cpp.CppBaseCastRecognizer.BaseCast;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;

/**
 * Integration coverage for the Rec 37 {@code #37-8b-1} {@link CppBaseCastRecognizer}, driven through
 * the Rec 30 headless {@link AbstractDecompilerHighFunctionTest} harness (DD-0023).
 *
 * <p>Each fixture is a hand-assembled x86-64 (Windows-ABI) one-instruction pointer adjustment
 * (\code{lea rax,[rcx+disp]; ret}) whose parameter and return are typed as two related classes, so
 * the decompiler emits the base-subobject cast idiom. An upcast {@code Base* f(Derived*)} adds the
 * base offset ({@code (Base *)&d->field_0x10}, a {@code PTRSUB}); a downcast
 * {@code Derived* f(Base*)} subtracts it ({@code (Derived *)(b + -2L)}, a {@code PTRADD}). The
 * matcher recovers the source pointer, the signed byte offset (positive upcast / negative downcast),
 * and the typed cast result from either shape.
 */
public class CppBaseCastRecognizerTest extends AbstractDecompilerHighFunctionTest {

	private static final String FUNC = "0x401000";

	private ProgramBuilder builder;

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testRecoversUpcast() throws Exception {
		// Base* upcast(Derived* d) { return (Base*)((char*)d + 0x10); }  -> CAST(PTRSUB(d, 0x10))
		HighFunction highFunction = decompileCast("48 8d 41 10 c3", true);
		BaseCast cast = soleCast(highFunction);

		assertNotNull("expected a recovered base cast", cast);
		assertEquals("upcast byte offset", 0x10, cast.byteOffset());
		assertEquals("source pointer points at the derived class", "Derived",
			pointedTypeName(cast.sourcePointer()));
		assertEquals("cast result points at the base class", "Base",
			pointedTypeName(cast.castResult()));
	}

	@Test
	public void testRecoversDowncast() throws Exception {
		// Derived* downcast(Base* b) { return (Derived*)((char*)b - 0x10); }  -> CAST(PTRADD(b,-2,8))
		HighFunction highFunction = decompileCast("48 8d 41 f0 c3", false);
		BaseCast cast = soleCast(highFunction);

		assertNotNull("expected a recovered base cast", cast);
		assertEquals("downcast byte offset is negative", -0x10, cast.byteOffset());
		assertEquals("source pointer points at the base class", "Base",
			pointedTypeName(cast.sourcePointer()));
		assertEquals("cast result points at the derived class", "Derived",
			pointedTypeName(cast.castResult()));
	}

	@Test
	public void testRecognizeNullIsSafe() {
		assertNull("a null op must yield no cast", CppBaseCastRecognizer.recognize(null));
	}

	@Test
	public void testDeclinesNonCastOps() throws Exception {
		HighFunction highFunction = decompileCast("48 8d 41 10 c3", true);
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != ghidra.program.model.pcode.PcodeOp.CAST) {
				assertNull("a non-CAST op must yield no base cast: " + op.getMnemonic(),
					CppBaseCastRecognizer.recognize(op));
			}
		}
	}

	private static BaseCast soleCast(HighFunction highFunction) {
		List<BaseCast> found = new ArrayList<>();
		Iterator<PcodeOpAST> ops = highFunction.getPcodeOps();
		while (ops.hasNext()) {
			BaseCast cast = CppBaseCastRecognizer.recognize(ops.next());
			if (cast != null) {
				found.add(cast);
			}
		}
		assertEquals("expected exactly one recovered base cast", 1, found.size());
		return found.get(0);
	}

	private static String pointedTypeName(Varnode pointerVarnode) {
		DataType dataType = pointerVarnode.getHigh().getDataType();
		assertTrue("varnode is not pointer-typed", dataType instanceof Pointer);
		return ((Pointer) dataType).getDataType().getName();
	}

	/**
	 * Builds a fresh program whose one-instruction body adjusts a class pointer by a base offset, and
	 * returns the decompiled {@link HighFunction}. {@code upcast} selects the {@code Derived* -> Base*}
	 * signature (return Base, arg Derived) versus the {@code Base* -> Derived*} downcast signature.
	 */
	private HighFunction decompileCast(String bytes, boolean upcast) throws Exception {
		builder = new ProgramBuilder("baseCast", ProgramBuilder._X64);
		builder.createMemory("text", FUNC, 0x100);
		builder.setBytes(FUNC, bytes, false);

		StructureDataType base = new StructureDataType("Base", 8);
		StructureDataType derived = new StructureDataType("Derived", 0x18);
		builder.addDataType(base);
		builder.addDataType(derived);
		DataType basePtr = new PointerDataType(base);
		DataType derivedPtr = new PointerDataType(derived);
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
