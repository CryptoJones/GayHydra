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
package ghidra.app.util.cpp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.app.util.cpp.CppItaniumRttiDecoder.DecodedClass;
import ghidra.app.util.cpp.CppRttiFeeder.BaseSpec;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;

/**
 * Rec 37 {@code #37-4b-1}: the Itanium typeinfo decoder over a hand-laid fixture — the three
 * ABI flavors back-to-back in one block, each bracketed by the next symbol, exactly as a
 * compiler emits them. Layout (x86-64, 8-byte words, little-endian):
 *
 * <pre>
 * 0x1000  _ZTI4Base      __class_type_info     [vptr][nameptr]
 * 0x1010  _ZTI7Derived   __si_class_type_info  [vptr][nameptr][&_ZTI4Base]
 * 0x1028  _ZTI5Other     __class_type_info     [vptr][nameptr]
 * 0x1038  _ZTI8Derived2  __vmi_class_type_info [vptr][nameptr][flags=0|count=2]
 *                                              [&_ZTI5Other][offset_flags 0x02]
 *                                              [&_ZTI4Base ][offset_flags 0x1002]
 * 0x1070  _ZTI3Bad       __vmi with a VIRTUAL base (offset_flags bit 0) — declines
 * 0x1098  end            bracket symbol
 * </pre>
 */
public class CppItaniumRttiDecoderTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private Program program;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("itaniumRtti", ProgramBuilder._X64);
		builder.createMemory(".data.rel.ro", "0x1000", 0x100);

		// _ZTI4Base: plain __class_type_info (vtable ptr + name ptr; values irrelevant —
		// the decode anchors on symbols, not on these words).
		setWords(0x1000, 0xdead0000L, 0xbeef0000L);
		// _ZTI7Derived: __si_class_type_info, base = _ZTI4Base.
		setWords(0x1010, 0xdead0000L, 0xbeef0001L, 0x1000L);
		// _ZTI5Other: plain.
		setWords(0x1028, 0xdead0000L, 0xbeef0002L);
		// _ZTI8Derived2: __vmi_class_type_info — flags=0 (low int), count=2 (high int);
		// base 0 = Other at offset 0 public (0x02), base 1 = Base at offset 16 public (0x1002).
		setWords(0x1038, 0xdead0000L, 0xbeef0003L, 0x2_00000000L, 0x1028L, 0x02L, 0x1000L,
			0x1002L);
		// _ZTI3Bad: vmi whose single base is VIRTUAL (offset_flags bit 0) — must decline.
		setWords(0x1070, 0xdead0000L, 0xbeef0004L, 0x1_00000000L, 0x1000L, 0x03L);

		builder.createLabel("0x1000", "_ZTI4Base");
		builder.createLabel("0x1010", "_ZTI7Derived");
		builder.createLabel("0x1028", "_ZTI5Other");
		builder.createLabel("0x1038", "_ZTI8Derived2");
		builder.createLabel("0x1070", "_ZTI3Bad");
		builder.createLabel("0x1098", "typeinfo_end_bracket");
		program = builder.getProgram();
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	private void setWords(long at, long... words) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (long word : words) {
			for (int i = 0; i < 8; i++) {
				hex.append(String.format("%02x ", (word >>> (8 * i)) & 0xff));
			}
		}
		builder.setBytes(String.format("0x%x", at), hex.toString().trim());
	}

	private Symbol symbolAt(long offset) {
		return program.getSymbolTable()
				.getPrimarySymbol(
					program.getAddressFactory().getDefaultAddressSpace().getAddress(offset));
	}

	@Test
	public void testPlainClassTypeinfoDecodesWithNoBases() {
		DecodedClass decoded = CppItaniumRttiDecoder.decodeClass(program, symbolAt(0x1000));
		assertNotNull("plain __class_type_info must decode", decoded);
		assertEquals("Base", decoded.derivedName());
		assertTrue(decoded.directBases().isEmpty());
	}

	@Test
	public void testSiClassTypeinfoDecodesItsSingleBase() {
		DecodedClass decoded = CppItaniumRttiDecoder.decodeClass(program, symbolAt(0x1010));
		assertNotNull("__si_class_type_info must decode", decoded);
		assertEquals("Derived", decoded.derivedName());
		assertEquals(List.of(new BaseSpec("Base", 0, false, true)), decoded.directBases());
	}

	@Test
	public void testVmiClassTypeinfoDecodesBothBasesWithOffsets() {
		DecodedClass decoded = CppItaniumRttiDecoder.decodeClass(program, symbolAt(0x1038));
		assertNotNull("__vmi_class_type_info must decode", decoded);
		assertEquals("Derived2", decoded.derivedName());
		assertEquals(List.of(
			new BaseSpec("Other", 0, false, true),
			new BaseSpec("Base", 16, false, true)), decoded.directBases());
	}

	@Test
	public void testVirtualBaseDeclinesTheWholeClass() {
		assertNull("a virtual base's offset rides the vtable — must decline",
			CppItaniumRttiDecoder.decodeClass(program, symbolAt(0x1070)));
	}

	@Test
	public void testNonTypeinfoSymbolDeclines() {
		assertNull(CppItaniumRttiDecoder.decodeClass(program, symbolAt(0x1098)));
		assertNull(CppItaniumRttiDecoder.decodeClass(program, null));
		assertNull(CppItaniumRttiDecoder.decodeClass(null, symbolAt(0x1000)));
	}

	@Test
	public void testNestedNameEncodingParses() throws Exception {
		// Re-label Base's typeinfo with a nested encoding: ns::Inner.
		int tx = program.startTransaction("relabel");
		try {
			symbolAt(0x1000).setName("_ZTIN2ns5InnerE", SourceType.USER_DEFINED);
		}
		finally {
			program.endTransaction(tx, true);
		}
		DecodedClass decoded = CppItaniumRttiDecoder.decodeClass(program, symbolAt(0x1000));
		assertNotNull(decoded);
		assertEquals("ns::Inner", decoded.derivedName());
	}

	@Test
	public void testTemplateEncodingDeclines() throws Exception {
		int tx = program.startTransaction("relabel");
		try {
			// _ZTI5MyVecIiE — a template; this slice declines rather than mis-names.
			symbolAt(0x1000).setName("_ZTI5MyVecIiE", SourceType.USER_DEFINED);
		}
		finally {
			program.endTransaction(tx, true);
		}
		assertNull(CppItaniumRttiDecoder.decodeClass(program, symbolAt(0x1000)));
	}
}
