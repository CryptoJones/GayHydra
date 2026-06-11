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

import java.awt.GraphicsEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ghidra.framework.plugintool.PluginTool;
import ghidra.test.AbstractGhidraHeadedIntegrationTest;
import ghidra.test.TestEnv;

/**
 * The "Rec 30 for the GUI" enabler pilot (meta-review 2026-06-11, the
 * DISPLAY-ceiling reframe in SprintPlanning.md): proves a headed Swing test
 * can run under Xvfb on a Linux CI runner, so the DISPLAY-gated tails
 * (Rec 35 #35-5b-2 retry action, Rec 38 #38-4 rename UI, the Rec 37 hints
 * margin) have a test layer to land behind.
 *
 * <p>Deliberately tiny and fork-owned — it exercises the Xvfb plumbing (a
 * real tool frame showing on a real X display), not any feature, so it
 * cannot inherit upstream GUI-test flake. The GUI features each bring their
 * own headed tests beside this one; {@code .github/workflows/xvfb-gui-tests.yml}
 * is the runner.
 */
public class XvfbPilotHeadedTest extends AbstractGhidraHeadedIntegrationTest {

	private TestEnv env;

	@Before
	public void setUp() throws Exception {
		env = new TestEnv();
	}

	@After
	public void tearDown() {
		if (env != null) {
			env.dispose();
		}
	}

	@Test
	public void testToolFrameShowsOnDisplay() throws Exception {
		assertFalse("environment is headless — Xvfb plumbing is broken",
			GraphicsEnvironment.isHeadless());
		PluginTool tool = env.showTool();
		assertNotNull("no tool", tool);
		assertNotNull("tool has no frame", tool.getToolFrame());
		waitForSwing();
		assertTrue("tool frame is not showing on the display",
			tool.getToolFrame().isShowing());
	}
}
