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
 * Rec 37 {@code #37-4b-3}: the Itanium RTTI analyzer's lifecycle gate — it must claim ELF and
 * Mach-O GCC/Clang binaries (where {@code _ZTI*} typeinfo lives) and decline a Windows PE
 * (the MSVC analyzer's job). The harvest behaviour itself is the headless
 * {@link ghidra.app.util.cpp.CppItaniumRttiScan} test; this pins only the analyzer's gating.
 */
public class CppItaniumRttiAnalyzerTest extends AbstractGenericTest {

	private ProgramBuilder builder;
	private ProgramDB program;
	private CppItaniumRttiAnalyzer analyzer;

	@Before
	public void setUp() throws Exception {
		builder = new ProgramBuilder("itaniumAnalyzer", ProgramBuilder._X64);
		program = builder.getProgram();
		analyzer = new CppItaniumRttiAnalyzer();
	}

	@After
	public void tearDown() {
		if (builder != null) {
			builder.dispose();
		}
	}

	private void setFormatAndCompiler(String format, String compiler) {
		int tx = program.startTransaction("set format/compiler");
		try {
			program.setExecutableFormat(format);
			program.setCompiler(compiler);
		}
		finally {
			program.endTransaction(tx, true);
		}
	}

	@Test
	public void testEnabledByDefaultAndRunsAfterReferenceAnalysis() {
		assertTrue("the analyzer must be default-enabled", analyzer.getDefaultEnablement(program));
		assertEquals("must run after reference analysis (settled symbols)",
			AnalysisPriority.REFERENCE_ANALYSIS.after().priority(), analyzer.getPriority().priority());
	}

	@Test
	public void testClaimsElfGcc() {
		setFormatAndCompiler(ElfLoader.ELF_NAME, "gcc");
		assertTrue(analyzer.canAnalyze(program));
	}

	@Test
	public void testClaimsElfDefaultCompiler() {
		setFormatAndCompiler(ElfLoader.ELF_NAME, "default");
		assertTrue(analyzer.canAnalyze(program));
	}

	@Test
	public void testClaimsMachoGcc() {
		setFormatAndCompiler(MachoLoader.MACH_O_NAME, "gcc");
		assertTrue(analyzer.canAnalyze(program));
	}

	@Test
	public void testDeclinesWindowsPe() {
		setFormatAndCompiler(PeLoader.PE_NAME, "visualstudio:unknown");
		assertFalse("a Windows PE is the MSVC analyzer's job", analyzer.canAnalyze(program));
	}

	@Test
	public void testDeclinesElfWithNonGccCompiler() {
		setFormatAndCompiler(ElfLoader.ELF_NAME, "borlandcpp");
		assertFalse(analyzer.canAnalyze(program));
	}
}
