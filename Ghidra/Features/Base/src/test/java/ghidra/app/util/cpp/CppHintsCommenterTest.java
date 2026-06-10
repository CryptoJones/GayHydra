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
import ghidra.app.util.cpp.CppHintsCollector.CppHint;
import ghidra.app.util.cpp.CppHintsCollector.Kind;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CommentType;

/**
 * Coverage for the Rec 37 {@code #37-11d-2} {@link CppHintsCommenter} (DD-0068): collected hints
 * written as idempotent, additive {@code PRE} comments.
 */
public class CppHintsCommenterTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private ProgramDB program;
	private Address site;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("hintsCommenter", ProgramBuilder._X64);
		builder.createMemory("text", "0x401000", 0x100);
		program = builder.getProgram();
		site = builder.addr(0x401010L);
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	@Test
	public void testWritesHintAsPreComment() throws Exception {
		int written = annotate(List.of(hint("param_1->draw()")));

		assertEquals(1, written);
		assertEquals("C++: param_1->draw()",
			program.getListing().getComment(CommentType.PRE, site));
	}

	@Test
	public void testReannotationIsIdempotent() throws Exception {
		List<CppHint> hints = List.of(hint("param_1->draw()"));

		annotate(hints);
		int secondRun = annotate(hints);

		assertEquals("a re-run must write nothing new", 0, secondRun);
		assertEquals("the comment must not be duplicated", "C++: param_1->draw()",
			program.getListing().getComment(CommentType.PRE, site));
	}

	@Test
	public void testAppendsBelowAnExistingUnrelatedComment() throws Exception {
		int txID = program.startTransaction("user comment");
		try {
			program.getListing().setComment(site, CommentType.PRE, "analyst note");
		}
		finally {
			program.endTransaction(txID, true);
		}

		int written = annotate(List.of(hint("delete param_1")));

		assertEquals(1, written);
		assertEquals("the user's comment must be preserved, the hint appended",
			"analyst note\nC++: delete param_1",
			program.getListing().getComment(CommentType.PRE, site));
	}

	@Test
	public void testDistinctHintsAtTheSameSiteBothAppend() throws Exception {
		annotate(List.of(hint("new C()")));
		int written = annotate(List.of(hint("new C(5)")));

		assertEquals("a different rendering at the same site is not a duplicate", 1, written);
		assertEquals("C++: new C()\nC++: new C(5)",
			program.getListing().getComment(CommentType.PRE, site));
	}

	@Test
	public void testEmptyHintListWritesNothing() throws Exception {
		assertEquals(0, annotate(List.of()));
		assertNull(program.getListing().getComment(CommentType.PRE, site));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullProgram() {
		CppHintsCommenter.annotate(null, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullHints() {
		CppHintsCommenter.annotate(program, null);
	}

	private CppHint hint(String rendering) {
		return new CppHint(site, Kind.VIRTUAL_CALL, rendering);
	}

	private int annotate(List<CppHint> hints) throws Exception {
		int txID = program.startTransaction("annotate");
		boolean commit = false;
		try {
			int written = CppHintsCommenter.annotate(program, hints);
			commit = true;
			return written;
		}
		finally {
			program.endTransaction(txID, commit);
		}
	}
}
