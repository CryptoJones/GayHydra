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

import java.io.File;
import java.io.IOException;

import org.junit.*;

import ghidra.test.AbstractGhidraHeadlessIntegrationTest;

/**
 * With nothing set, {@link DecompileProcessFactory#get()} spawns the stock native
 * {@code decompile} found through the Application; with the system property set, it
 * spawns that path instead, and goes back to stock once the property is cleared. Needs
 * an initialized Application for the stock lookup, hence the integration sourceset.
 */
public class DecompileProcessFactoryStockPathTest extends AbstractGhidraHeadlessIntegrationTest {

	private File alternate;

	@Before
	public void setUp() throws IOException {
		alternate = File.createTempFile("decompile-alternate", ".sh");
		assertTrue(alternate.setExecutable(true));
		DecompileProcessFactory.resetExecutable();
		System.clearProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY);
	}

	@After
	public void tearDown() {
		DecompileProcessFactory.resetExecutable();
		System.clearProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY);
		alternate.delete();
	}

	@Test
	public void testStockPathWhenNothingIsSet() {
		Assume.assumeTrue("GHIDRA_DECOMPILER_EXE is set in this environment",
			System.getenv(DecompileProcessFactory.EXECUTABLE_ENV) == null);

		String stock = DecompileProcessFactory.getExecutablePath();
		assertNotNull("the stock decompile of this module must be found", stock);
		assertTrue("stock path should name the native decompile: " + stock,
			new File(stock).getName().startsWith("decompile"));

		DecompileProcess process = DecompileProcessFactory.get();
		try {
			assertEquals(stock, process.getExecutable());
		}
		finally {
			DecompileProcessFactory.release(process);
		}
	}

	@Test
	public void testPropertyIsSpawnedThenStockAgainOnceCleared() {
		Assume.assumeTrue("GHIDRA_DECOMPILER_EXE is set in this environment",
			System.getenv(DecompileProcessFactory.EXECUTABLE_ENV) == null);

		System.setProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY,
			alternate.getAbsolutePath());
		DecompileProcess process = DecompileProcessFactory.get();
		try {
			assertEquals(alternate.getAbsolutePath(), process.getExecutable());
		}
		finally {
			DecompileProcessFactory.release(process);
		}

		System.clearProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY);
		String stock = DecompileProcessFactory.getExecutablePath();
		assertNotEquals(alternate.getAbsolutePath(), stock);
		assertTrue(new File(stock).getName().startsWith("decompile"));
	}
}
