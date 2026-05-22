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
package generic.i18n;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PseudoLocaleGeneratorTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void pseudo_wrapsValueWithBracketSentinels() {
		String out = PseudoLocaleGenerator.pseudo("Close");
		assertTrue("expected leading sentinel, got: " + out, out.startsWith("[!! "));
		assertTrue("expected trailing sentinel, got: " + out, out.endsWith(" !!]"));
	}

	@Test
	public void pseudo_appliesDiacriticSubstitutionsToLatinCharacters() {
		// Each 'o' becomes 'ö', 'C' becomes 'Ç', 'l' becomes 'ł', 'e' becomes 'é'.
		assertEquals("[!! Çłöşé !!]", PseudoLocaleGenerator.pseudo("Close"));
	}

	@Test
	public void pseudo_preservesMessageFormatPlaceholders() {
		// "{0}" must pass through unchanged so MessageFormat substitution
		// still works under the pseudo-locale.
		String out = PseudoLocaleGenerator.pseudo("Hello, {0}!");
		assertTrue("placeholder lost: " + out, out.contains("{0}"));
	}

	@Test
	public void pseudo_passesThroughEmptyAndNullValues() {
		assertNull(PseudoLocaleGenerator.pseudo(null));
		assertEquals("", PseudoLocaleGenerator.pseudo(""));
	}

	@Test
	public void generate_writesPseudoBundleNextToSource() throws IOException {
		Path source = tmp.newFile("messages.properties").toPath();
		Files.writeString(source, "test.greeting=Hello\ntest.fmt=Hello, {0}!\n");
		Path target = tmp.getRoot().toPath().resolve("messages_en_XA.properties");

		PseudoLocaleGenerator.generate(source, target);

		assertTrue(Files.exists(target));
		Properties props = new Properties();
		try (var in = Files.newInputStream(target)) {
			props.load(in);
		}
		assertEquals("[!! Hellö !!]", props.getProperty("test.greeting"));
		String fmt = props.getProperty("test.fmt");
		assertTrue("placeholder lost in generated file: " + fmt, fmt.contains("{0}"));
	}
}
