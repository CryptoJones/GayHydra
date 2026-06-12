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
import ghidra.program.database.ProgramBuilder;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Program;

/**
 * Rec 37 {@code #37-4b-4}: the {@code _ZTV} harvest fed into a real {@link CppTypeSystem}. Same
 * fixture as the decoder test — Base (1 slot), Multi (2 slots), Pure (abstract, declines),
 * Unnamed (declines) — asserting the end-state vtables attached to classes.
 */
public class CppItaniumVtableScanTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private Program program;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("itaniumVtableScan", ProgramBuilder._X64);
		builder.createMemory(".text", "0x1000", 0x100);
		builder.createMemory(".data.rel.ro", "0x2000", 0x100);
		builder.createEmptyFunction("draw", "0x1000", 1, IntegerDataType.dataType);
		builder.createEmptyFunction("id", "0x1010", 1, IntegerDataType.dataType);
		builder.createEmptyFunction("__cxa_pure_virtual", "0x1020", 1, VoidDataType.dataType);
		builder.createEmptyFunction(null, "0x1030", 1, null);
		setWords(0x2000, 0, 0x1234, 0x1000, 0);
		setWords(0x2020, 0, 0x1234, 0x1000, 0x1010, 0);
		setWords(0x2048, 0, 0x1234, 0x1020, 0);
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

	@Test
	public void testScanFeedsDecodableVtablesAndSkipsDecliners() {
		CppTypeSystem ts = new CppTypeSystem();
		List<CppVTable> fed = CppItaniumVtableScan.feedProgram(program, new CppVTableFeeder(ts));

		// Base + Multi decode; Pure + Unnamed decline.
		assertEquals(2, fed.size());
		assertNull("an abstract-class vtable must not create a class", ts.getCppClass("Pure"));
		assertNull(ts.getCppClass("Unnamed"));

		CppVTable base = ts.getCppClass("Base").getVtable();
		assertNotNull(base);
		assertEquals(1, base.getSlots().size());
		assertEquals("draw", base.getSlots().get(0).getName());

		CppVTable multi = ts.getCppClass("Multi").getVtable();
		assertNotNull(multi);
		assertEquals(List.of("draw", "id"),
			multi.getSlots().stream().map(CppMethod::getName).toList());
		// Slot index is the dispatch contract: draw is slot 0, id is slot 1.
		assertTrue(multi.getSlots().get(0).isVirtual());
	}

	@Test
	public void testRescanAddsNoDuplicateVtables() {
		CppTypeSystem ts = new CppTypeSystem();
		CppVTableFeeder feeder = new CppVTableFeeder(ts);
		CppItaniumVtableScan.feedProgram(program, feeder);
		CppItaniumVtableScan.feedProgram(program, feeder);

		assertEquals("re-feeding must not duplicate slots", 1,
			ts.getCppClass("Base").getVtable().getSlots().size());
		assertEquals(2, ts.getCppClass("Multi").getVtable().getSlots().size());
	}

	@Test
	public void testNullArgumentsRejected() {
		try {
			CppItaniumVtableScan.feedProgram(null, new CppVTableFeeder(new CppTypeSystem()));
			fail("null program must be rejected");
		}
		catch (IllegalArgumentException e) {
			// expected
		}
		try {
			CppItaniumVtableScan.feedProgram(program, null);
			fail("null feeder must be rejected");
		}
		catch (IllegalArgumentException e) {
			// expected
		}
	}
}
