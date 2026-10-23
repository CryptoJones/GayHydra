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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The seams of {@link DecompileProcessFactory} for choosing the decompiler executable:
 * the runtime override, the system property, the environment variable, in that order,
 * with a setting that does not name an executable file ignored. No Ghidra runtime is
 * needed, so this lives in the fast {@code test} sourceset. The fall-through to the
 * stock executable needs an initialized Application and is covered by
 * {@code DecompileProcessFactoryStockPathTest} in the integration sourceset.
 */
public class DecompileProcessFactoryExecutableTest {

	private File first;
	private File second;

	@Before
	public void setUp() throws IOException {
		first = executableFile("first");
		second = executableFile("second");
		DecompileProcessFactory.resetExecutable();
		System.clearProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY);
	}

	@After
	public void tearDown() {
		DecompileProcessFactory.resetExecutable();
		System.clearProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY);
		first.delete();
		second.delete();
	}

	private static File executableFile(String name) throws IOException {
		File file = File.createTempFile("decompile-" + name, ".sh");
		assertTrue("temp file must be executable for the test", file.setExecutable(true));
		return file;
	}

	@Test
	public void testOverrideWinsOverPropertyAndEnvironment() {
		String path = DecompileProcessFactory.resolve("/override/decompile",
			first.getAbsolutePath(), second.getAbsolutePath());
		assertEquals("/override/decompile", path);
	}

	@Test
	public void testPropertyWinsOverEnvironment() {
		String path = DecompileProcessFactory.resolve(null, first.getAbsolutePath(),
			second.getAbsolutePath());
		assertEquals(first.getAbsolutePath(), path);
	}

	@Test
	public void testEnvironmentUsedWhenPropertyIsUnset() {
		assertEquals(second.getAbsolutePath(),
			DecompileProcessFactory.resolve(null, null, second.getAbsolutePath()));
		assertEquals(second.getAbsolutePath(),
			DecompileProcessFactory.resolve(null, "  ", second.getAbsolutePath()));
	}

	@Test
	public void testSettingThatIsNotAnExecutableFileIsIgnored() throws IOException {
		File missing = new File(first.getParentFile(), "no-such-decompile-" + System.nanoTime());
		assertFalse(missing.exists());
		File plain = File.createTempFile("decompile-plain", ".txt");
		try {
			plain.setExecutable(false);
			assertEquals("a missing property falls through to the environment",
				second.getAbsolutePath(), DecompileProcessFactory.resolve(null,
					missing.getAbsolutePath(), second.getAbsolutePath()));
			assertNull("a missing property with no environment falls through to stock",
				DecompileProcessFactory.resolve(null, missing.getAbsolutePath(), null));
			assertNull("a file that is not executable is not a decompiler",
				DecompileProcessFactory.resolve(null, plain.getAbsolutePath(), null));
			assertNull("nothing set means the stock executable",
				DecompileProcessFactory.resolve(null, null, null));
		}
		finally {
			plain.delete();
		}
	}

	@Test
	public void testGetSpawnsThePropertyPath() {
		System.setProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY, first.getAbsolutePath());
		DecompileProcess process = DecompileProcessFactory.get();
		try {
			assertEquals(first.getAbsolutePath(), process.getExecutable());
			assertEquals(first.getAbsolutePath(), DecompileProcessFactory.getExecutablePath());
		}
		finally {
			DecompileProcessFactory.release(process);
		}
	}

	@Test
	public void testSetExecutableBeatsThePropertyUntilReset() {
		System.setProperty(DecompileProcessFactory.EXECUTABLE_PROPERTY, first.getAbsolutePath());
		DecompileProcessFactory.setExecutable(second.getAbsolutePath());
		assertEquals(second.getAbsolutePath(), DecompileProcessFactory.getExecutablePath());

		DecompileProcessFactory.resetExecutable();
		assertEquals(first.getAbsolutePath(), DecompileProcessFactory.getExecutablePath());
	}

	@Test
	public void testSetExecutableRejectsWhatIsNotAnExecutableFile() {
		File missing = new File(first.getParentFile(), "no-such-decompile-" + System.nanoTime());
		try {
			DecompileProcessFactory.setExecutable(missing.getAbsolutePath());
			fail("a missing file must be rejected");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains(missing.getAbsolutePath()));
		}
		try {
			DecompileProcessFactory.setExecutable(first.getParentFile().getAbsolutePath());
			fail("a directory must be rejected");
		}
		catch (IllegalArgumentException e) {
			// expected
		}
	}
}
