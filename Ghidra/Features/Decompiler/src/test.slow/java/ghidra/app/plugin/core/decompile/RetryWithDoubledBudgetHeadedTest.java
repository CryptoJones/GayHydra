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
package ghidra.app.plugin.core.decompile;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import docking.action.DockingActionIf;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.component.DecompilerController;
import ghidra.app.plugin.core.codebrowser.CodeBrowserPlugin;
import ghidra.app.services.ProgramManager;
import ghidra.framework.options.ToolOptions;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;
import ghidra.program.util.ProgramLocation;
import ghidra.test.*;
import generic.test.TestUtils;

/**
 * Rec 35 #35-5b-2, behind the Xvfb layer (see XvfbPilotHeadedTest /
 * xvfb-gui-tests.yml): the retry-with-doubled-budget action's GUI mechanics.
 *
 * <p>Covers: the action is installed on the provider; it is disabled while
 * the displayed result is complete (the common case); and the retry core
 * doubles the iteration-budget tool option, which the options-changed
 * listener turns into a re-decompile. Inducing a genuinely budget-truncated
 * result needs a function large enough to exhaust a real budget — the toy
 * fixture cannot honestly provide one, so the enabled-on-partial half rests
 * on {@code DecompileResults.isPartial()} (decode-tested headlessly) feeding
 * the three-line enablement predicate exercised here on its false branch.
 */
public class RetryWithDoubledBudgetHeadedTest extends AbstractGhidraHeadedIntegrationTest {

	private TestEnv env;
	private PluginTool tool;
	private CodeBrowserPlugin codeBrowser;
	private ProgramDB program;
	private DecompilerProvider provider;
	private Address functionAddr;

	@Before
	public void setUp() throws Exception {
		setErrorGUIEnabled(false);
		env = new TestEnv();
		tool = env.getTool();
		tool.addPlugin(CodeBrowserPlugin.class.getName());
		tool.addPlugin(DecompilePlugin.class.getName());
		codeBrowser = env.getPlugin(CodeBrowserPlugin.class);

		ToyProgramBuilder builder = new ToyProgramBuilder("retryBudget", true);
		builder.createMemory(".text", "0x1004000", 0x100);
		builder.addBytesReturn("0x1004000");
		builder.disassemble("0x1004000", 2, true);
		builder.createFunction("0x1004000");
		functionAddr = builder.addr("0x1004000");
		program = builder.getProgram();

		ProgramManager pm = tool.getService(ProgramManager.class);
		pm.openProgram(program.getDomainFile());
		showTool(tool);
		tool.showComponentProvider(tool.getComponentProvider("Decompiler"), true);
		provider = waitForComponentProvider(DecompilerProvider.class);
	}

	@After
	public void tearDown() {
		env.dispose();
	}

	private void goToFunctionAndWait() {
		assertTrue(codeBrowser.goTo(new ProgramLocation(program, functionAddr), true));
		waitForSwing();
		DecompilerController controller =
			(DecompilerController) TestUtils.getInstanceField("controller", provider);
		waitForCondition(() -> !controller.isDecompiling());
	}

	@Test
	public void testActionInstalledAndDisabledOnCompleteResult() {
		goToFunctionAndWait();
		DockingActionIf action = getAction(tool, "Retry With Doubled Budget");
		assertNotNull("retry action is not installed", action);
		assertFalse("retry must be disabled while the displayed result is complete",
			provider.isRetryWithDoubledBudgetEnabled());
	}

	@Test
	public void testRetryDoublesBudgetOptionAndRedecompiles() {
		goToFunctionAndWait();
		ToolOptions opt = tool.getOptions(DecompilePlugin.OPTIONS_TITLE);
		runSwing(() -> opt.setInt(DecompileOptions.DECOMPILEBUDGET_OPTIONSTRING, 7));
		goToFunctionAndWait();	// absorb the option-change redecompile

		runSwing(() -> provider.retryWithDoubledBudget());
		waitForSwing();

		assertEquals("retry did not double the budget option", 14,
			opt.getInt(DecompileOptions.DECOMPILEBUDGET_OPTIONSTRING, 0));
		DecompilerController controller =
			(DecompilerController) TestUtils.getInstanceField("controller", provider);
		waitForCondition(() -> !controller.isDecompiling());
		assertTrue("display lost its results after the retry redecompile",
			controller.getDecompileData() != null &&
				controller.getDecompileData().hasDecompileResults());
	}

	@Test
	public void testRetrySaturatesInsteadOfOverflowing() {
		goToFunctionAndWait();
		ToolOptions opt = tool.getOptions(DecompilePlugin.OPTIONS_TITLE);
		runSwing(
			() -> opt.setInt(DecompileOptions.DECOMPILEBUDGET_OPTIONSTRING, Integer.MAX_VALUE - 1));
		goToFunctionAndWait();

		runSwing(() -> provider.retryWithDoubledBudget());
		waitForSwing();

		assertEquals("near-max budget must saturate, not overflow", Integer.MAX_VALUE,
			opt.getInt(DecompileOptions.DECOMPILEBUDGET_OPTIONSTRING, 0));
	}
}
