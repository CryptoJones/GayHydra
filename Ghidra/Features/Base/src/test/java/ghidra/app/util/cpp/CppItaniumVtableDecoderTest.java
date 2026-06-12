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
import ghidra.app.util.cpp.CppItaniumVtableDecoder.DecodedVtable;
import ghidra.app.util.cpp.CppVTableFeeder.SlotSpec;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;

/**
 * Rec 37 {@code #37-4b-4}: the Itanium {@code _ZTV} vtable decoder over a hand-laid fixture.
 * Functions live in a text block; vtables live in a rodata block as 8-byte pointer words to
 * those functions (x86-64, little-endian). Layout per vtable:
 * {@code [ offset-to-top=0 ][ typeinfo ptr (ignored) ][ slot fn ptr… ][ 0 terminator ]}.
 */
public class CppItaniumVtableDecoderTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private Program program;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("itaniumVtable", ProgramBuilder._X64);
		builder.createMemory(".text", "0x1000", 0x100);
		builder.createMemory(".data.rel.ro", "0x2000", 0x100);

		// Functions the slots point at.
		builder.createEmptyFunction("draw", "0x1000", 1, IntegerDataType.dataType);
		builder.createEmptyFunction("id", "0x1010", 1, IntegerDataType.dataType);
		builder.createEmptyFunction("__cxa_pure_virtual", "0x1020", 1, VoidDataType.dataType);
		builder.createEmptyFunction(null, "0x1030", 1, null); // default FUN_ name

		// _ZTV4Base @ 0x2000: one slot (draw).
		setWords(0x2000, 0, 0x1234, 0x1000, 0);
		// _ZTV5Multi @ 0x2020: two slots (draw, id) in order.
		setWords(0x2020, 0, 0x1234, 0x1000, 0x1010, 0);
		// _ZTV4Pure @ 0x2048: slot is __cxa_pure_virtual -> decline whole table.
		setWords(0x2048, 0, 0x1234, 0x1020, 0);
		// _ZTV7Unnamed @ 0x2068: slot points at a default-named function -> decline.
		setWords(0x2068, 0, 0x1234, 0x1030, 0);

		builder.createLabel("0x2000", "_ZTV4Base");
		builder.createLabel("0x2020", "_ZTV5Multi");
		builder.createLabel("0x2048", "_ZTV4Pure");
		builder.createLabel("0x2068", "_ZTV7Unnamed");
		builder.createLabel("0x2088", "vtable_end_bracket");
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
	public void testSingleSlotVtableDecodes() {
		DecodedVtable decoded = CppItaniumVtableDecoder.decodeVtable(program, symbolAt(0x2000));
		assertNotNull(decoded);
		assertEquals("Base", decoded.owningClassName());
		assertEquals(1, decoded.slots().size());
		assertEquals("draw", decoded.slots().get(0).methodName());
	}

	@Test
	public void testMultiSlotVtableDecodesInOrder() {
		DecodedVtable decoded = CppItaniumVtableDecoder.decodeVtable(program, symbolAt(0x2020));
		assertNotNull(decoded);
		assertEquals("Multi", decoded.owningClassName());
		assertEquals(List.of("draw", "id"),
			decoded.slots().stream().map(SlotSpec::methodName).toList());
	}

	@Test
	public void testPureVirtualSlotDeclinesWholeTable() {
		assertNull("an abstract-class vtable (__cxa_pure_virtual) must decline",
			CppItaniumVtableDecoder.decodeVtable(program, symbolAt(0x2048)));
	}

	@Test
	public void testUnnamedSlotDeclinesWholeTable() {
		assertNull("a default-named slot function must decline the whole table",
			CppItaniumVtableDecoder.decodeVtable(program, symbolAt(0x2068)));
	}

	@Test
	public void testNonVtableSymbolAndNullsDecline() {
		assertNull(CppItaniumVtableDecoder.decodeVtable(program, symbolAt(0x2088)));
		assertNull(CppItaniumVtableDecoder.decodeVtable(program, null));
		assertNull(CppItaniumVtableDecoder.decodeVtable(null, symbolAt(0x2000)));
	}
}
