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
import ghidra.framework.plugintool.PluginTool;
import ghidra.test.AbstractGhidraHeadedIntegrationTest;
import ghidra.test.TestEnv;

/**
 * Rec 38 #38-4, behind the Xvfb layer: the propagate-name action is installed
 * on the decompiler provider. The propagation behaviour itself is headless
 * ({@code ScopeGraphRenamePropagatorTest}); the parameter-token enablement
 * rides the same token resolution every rename action uses.
 */
public class PropagateNameActionHeadedTest extends AbstractGhidraHeadedIntegrationTest {

	private TestEnv env;
	private PluginTool tool;

	@Before
	public void setUp() throws Exception {
		setErrorGUIEnabled(false);
		env = new TestEnv();
		tool = env.getTool();
		tool.addPlugin(DecompilePlugin.class.getName());
		showTool(tool);
		tool.showComponentProvider(tool.getComponentProvider("Decompiler"), true);
		waitForComponentProvider(DecompilerProvider.class);
	}

	@After
	public void tearDown() {
		env.dispose();
	}

	@Test
	public void testActionInstalled() {
		DockingActionIf action = getAction(tool, "Propagate Name to Same-Value Peers");
		assertNotNull("propagate-name action is not installed on the decompiler", action);
	}
}
