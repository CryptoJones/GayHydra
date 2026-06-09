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

import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.test.AbstractGhidraHeadlessIntegrationTest;
import ghidra.util.task.TaskMonitor;

/**
 * Headless integration harness (Rec 30) for tests that need a real
 * {@link HighFunction} &mdash; the decompiler's p-code syntax tree &mdash; rather
 * than a synthetic stand-in.
 *
 * <p>The fast headless-unit layer ({@code gradle :test}, the C++
 * {@code decomp_test_dbg} corpus) never spins up a live {@link Program} or runs
 * the decompiler process, so it cannot exercise any pass that walks a
 * {@code HighFunction}. The Rec 37 C++ <em>recognition</em> wrappers &mdash; the
 * Program-coupled half of the {@code ghidra.app.util.cpp} family that detects
 * ctor/dtor/cast/{@code new}/{@code delete} idioms in a live function and dispatches
 * to the already-shipped {@link ghidra.app.util.cpp.CppDecompilerHints} renderers
 * &mdash; are blocked on exactly that capability. This base class supplies it: load
 * or build a small {@code Program}, decompile a function to a real
 * {@code HighFunction} headlessly (no {@code DISPLAY}, no Swing), and assert against
 * the result. It is the gate that makes that recognition work testable before it is
 * committed. See {@code docs/decisions/0023-rec30-headless-highfunction-harness.md}.
 *
 * <p>This is deliberately a thin, lifecycle-managing wrapper over
 * {@link DecompInterface}, not a new abstraction: each helper opens a private
 * {@code DecompInterface} against the supplied program, decompiles the one function,
 * and disposes the native process in a {@code finally} block. The returned
 * {@link DecompileResults} is fully materialized at construction
 * ({@code DecompileResults} decodes the entire result stream eagerly), so the
 * {@code HighFunction}, the C markup, and the pretty-printed C remain valid after the
 * interface is disposed.
 *
 * <p>It extends {@link AbstractGhidraHeadlessIntegrationTest} rather than the
 * {@code Headed} variant so it carries no GUI tool: the only live components are the
 * in-memory program and the decompiler subprocess. Subclasses provide the program
 * (commonly via {@code ToyProgramBuilder} or {@code ProgramBuilder}) and assert on
 * the {@code HighFunction} / C output the helpers return.
 */
public abstract class AbstractDecompilerHighFunctionTest
		extends AbstractGhidraHeadlessIntegrationTest {

	/** Default per-function decompile timeout, in seconds. */
	protected static final int DEFAULT_TIMEOUT_SECS =
		DecompileOptions.SUGGESTED_DECOMPILE_TIMEOUT_SECS;

	/**
	 * Decompiles {@code function} within {@code program} to a completed
	 * {@link DecompileResults}, using the default timeout.
	 *
	 * @param program the program owning the function; must not be null
	 * @param function the function to decompile; must not be null
	 * @return the completed decompile results (HighFunction and C markup populated)
	 * @see #decompile(Program, Function, int)
	 */
	protected DecompileResults decompile(Program program, Function function) {
		return decompile(program, function, DEFAULT_TIMEOUT_SECS);
	}

	/**
	 * Decompiles {@code function} within {@code program} to a completed
	 * {@link DecompileResults} headlessly.
	 *
	 * <p>Opens a private {@link DecompInterface} against {@code program}, decompiles
	 * the single function, asserts the decompile completed, and disposes the native
	 * process before returning. The returned results object is self-contained and
	 * safe to read after this method returns.
	 *
	 * @param program the program owning the function; must not be null
	 * @param function the function to decompile; must not be null
	 * @param timeoutSecs the per-function decompile timeout, in seconds
	 * @return the completed decompile results
	 * @throws IllegalArgumentException if {@code program} or {@code function} is null
	 */
	protected DecompileResults decompile(Program program, Function function, int timeoutSecs) {
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		if (function == null) {
			throw new IllegalArgumentException("function must not be null");
		}
		DecompInterface decompiler = new DecompInterface();
		try {
			decompiler.setOptions(new DecompileOptions());
			assertTrue("openProgram failed for " + program.getName(),
				decompiler.openProgram(program));
			DecompileResults results =
				decompiler.decompileFunction(function, timeoutSecs, TaskMonitor.DUMMY);
			assertTrue(
				"decompile did not complete for " + function.getName() + ": " +
					results.getErrorMessage(),
				results.decompileCompleted());
			return results;
		}
		finally {
			decompiler.dispose();
		}
	}

	/**
	 * Decompiles {@code function} and returns its {@link HighFunction} directly &mdash;
	 * the convenience form for recognition tests, which walk the p-code syntax tree.
	 *
	 * @param program the program owning the function; must not be null
	 * @param function the function to decompile; must not be null
	 * @return the non-null HighFunction for the function
	 */
	protected HighFunction decompileToHighFunction(Program program, Function function) {
		HighFunction highFunction = decompile(program, function).getHighFunction();
		assertNotNull("decompile produced no HighFunction for " + function.getName(),
			highFunction);
		return highFunction;
	}
}
