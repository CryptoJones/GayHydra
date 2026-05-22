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

import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class I18nTest {

	private Locale originalLocale;

	@Before
	public void setUp() {
		originalLocale = Locale.getDefault();
		Locale.setDefault(Locale.ROOT);
		I18n.clearCacheForTesting();
	}

	@After
	public void tearDown() {
		Locale.setDefault(originalLocale);
		I18n.clearCacheForTesting();
	}

	@Test
	public void plainLookup_resolvesFromModuleBundle() {
		assertEquals("Hello", I18n.tr("testmod.plain"));
	}

	@Test
	public void messageFormatLookup_substitutesSingleArgument() {
		assertEquals("Hello, Aaron!", I18n.tr("testmod.with.args", "Aaron"));
	}

	@Test
	public void messageFormatLookup_substitutesTwoArguments() {
		assertEquals("a of b", I18n.tr("testmod.two.args", "a", "b"));
	}

	@Test
	public void missingKey_returnsPlaceholder_neverThrows() {
		assertEquals("???testmod.nope???", I18n.tr("testmod.nope"));
		assertEquals("???testmod.nope???", I18n.tr("testmod.nope", "ignored"));
	}

	@Test
	public void coreFallback_resolvesWhenModuleBundleLacksKey() {
		// "core.button.ok" is defined in the main core/messages.properties.
		// It is NOT defined in testmod/messages.properties.  Looking it up
		// with the "core." module prefix should hit the core bundle directly;
		// this confirms the core bundle is reachable from tests.
		assertEquals("OK", I18n.tr("core.button.ok"));
	}

	@Test
	public void localeSwitch_picksUpPseudoLocale() {
		Locale.setDefault(Locale.forLanguageTag("en-XA"));
		I18n.clearCacheForTesting();
		assertEquals("[!! Hellö !!]", I18n.tr("testmod.plain"));
	}

	@Test
	public void bundle_returnsCachedResourceBundle() {
		assertNotNull(I18n.bundle("testmod"));
		// Repeated calls return the same instance via the cache.
		assertSame(I18n.bundle("testmod"), I18n.bundle("testmod"));
	}
}
