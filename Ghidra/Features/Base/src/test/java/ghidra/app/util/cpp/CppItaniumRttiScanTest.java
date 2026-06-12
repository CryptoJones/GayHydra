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
import ghidra.program.model.listing.Program;

/**
 * Rec 37 {@code #37-4b-2}: the Itanium {@code _ZTI*} harvest over the same back-to-back
 * fixture the decoder test grounds (Base, Derived&rarr;Base, Other,
 * Derived2&rarr;{Other@0, Base@16}, plus a virtual-base typeinfo that must decline) — fed
 * into a real {@link CppTypeSystem}, asserting the end-state class graph.
 */
public class CppItaniumRttiScanTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private Program program;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("itaniumScan", ProgramBuilder._X64);
		builder.createMemory(".data.rel.ro", "0x1000", 0x100);
		setWords(0x1000, 0xdead0000L, 0xbeef0000L);
		setWords(0x1010, 0xdead0000L, 0xbeef0001L, 0x1000L);
		setWords(0x1028, 0xdead0000L, 0xbeef0002L);
		setWords(0x1038, 0xdead0000L, 0xbeef0003L, 0x2_00000000L, 0x1028L, 0x02L, 0x1000L,
			0x1002L);
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

	@Test
	public void testScanFeedsTheDecodableHierarchyAndSkipsDecliners() throws Exception {
		CppTypeSystem ts = new CppTypeSystem();
		List<CppClass> fed = CppItaniumRttiScan.feedProgram(program, new CppRttiFeeder(ts));

		// Four decodable typeinfos (Bad's virtual base declines).
		assertEquals(4, fed.size());
		assertNull("the virtual-base typeinfo must not create a class", ts.getCppClass("Bad"));

		CppClass derived = ts.getCppClass("Derived");
		assertNotNull(derived);
		assertEquals(1, derived.getBaseClasses().size());
		assertSame(ts.getCppClass("Base"), derived.getBaseClasses().get(0).getBaseClass());

		CppClass derived2 = ts.getCppClass("Derived2");
		assertNotNull(derived2);
		assertEquals(2, derived2.getBaseClasses().size());
		assertSame(ts.getCppClass("Other"), derived2.getBaseClasses().get(0).getBaseClass());
		assertEquals(0, derived2.getBaseClasses().get(0).getOffset());
		assertSame(ts.getCppClass("Base"), derived2.getBaseClasses().get(1).getBaseClass());
		assertEquals(16, derived2.getBaseClasses().get(1).getOffset());
	}

	@Test
	public void testRescanAddsNoDuplicateEdges() throws Exception {
		CppTypeSystem ts = new CppTypeSystem();
		CppRttiFeeder feeder = new CppRttiFeeder(ts);
		CppItaniumRttiScan.feedProgram(program, feeder);
		CppItaniumRttiScan.feedProgram(program, feeder);

		assertEquals("re-feeding must not duplicate edges (the DD-0063 idempotence contract)",
			1, ts.getCppClass("Derived").getBaseClasses().size());
		assertEquals(2, ts.getCppClass("Derived2").getBaseClasses().size());
	}

	@Test
	public void testNullArgumentsAreRejected() {
		try {
			CppItaniumRttiScan.feedProgram(null, new CppRttiFeeder(new CppTypeSystem()));
			fail("null program must be rejected");
		}
		catch (IllegalArgumentException e) {
			// expected
		}
		try {
			CppItaniumRttiScan.feedProgram(program, null);
			fail("null feeder must be rejected");
		}
		catch (IllegalArgumentException e) {
			// expected
		}
	}
}
