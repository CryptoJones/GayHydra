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
package ghidra.app.plugin.core.analysis;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.util.opinion.ElfLoader;
import ghidra.app.util.opinion.MachoLoader;
import ghidra.app.util.opinion.PeLoader;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;

/**
 * Rec 37 {@code #37-4b-4}: the Itanium vtable analyzer's lifecycle gate — claim ELF/Mach-O
 * (format-only, like the RTTI analyzer; the {@code _ZTV*} symbol presence is the real filter),
 * decline a Windows PE (the MSVC vtable analyzer's job). The harvest behaviour itself is the
 * {@link ghidra.app.util.cpp.CppItaniumVtableScan} test; this pins only the gate.
 */
public class CppItaniumVtableAnalyzerTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private ProgramDB program;
	private CppItaniumVtableAnalyzer analyzer;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("itaniumVtableAnalyzer", ProgramBuilder._X64);
		program = builder.getProgram();
		analyzer = new CppItaniumVtableAnalyzer();
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	private void setFormat(String format) {
		int tx = program.startTransaction("set format");
		try {
			program.setExecutableFormat(format);
		}
		finally {
			program.endTransaction(tx, true);
		}
	}

	@Test
	public void testEnabledByDefaultAndRunsAfterReferenceAnalysis() {
		assertTrue(analyzer.getDefaultEnablement(program));
		assertEquals(AnalysisPriority.REFERENCE_ANALYSIS.after().priority(),
			analyzer.getPriority().priority());
	}

	@Test
	public void testClaimsElf() {
		setFormat(ElfLoader.ELF_NAME);
		assertTrue(analyzer.canAnalyze(program));
	}

	@Test
	public void testClaimsMacho() {
		setFormat(MachoLoader.MACH_O_NAME);
		assertTrue(analyzer.canAnalyze(program));
	}

	@Test
	public void testDeclinesWindowsPe() {
		setFormat(PeLoader.PE_NAME);
		assertFalse("a Windows PE is the MSVC vtable analyzer's job", analyzer.canAnalyze(program));
	}
}
