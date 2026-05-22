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

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central lookup helper for translated UI strings (GayHydra l10n PoC).
 *
 * <p>Keys follow {@code <module>.<scope>.<element>[.<role>]} convention,
 * lowercase, dot-separated, snake_case for multi-word — e.g.
 * {@code docking.action.close.label}. The leading segment selects the
 * module bundle; {@code core.*} is the shared bundle for OK/Cancel/Apply.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>Caller's module bundle ({@code <module>.messages})</li>
 *   <li>{@code core.messages}</li>
 *   <li>Returns {@code ???key???} placeholder (never throws)</li>
 * </ol>
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code -Dghidra.locale=zh-CN} — handled by {@code GhidraLauncher}</li>
 *   <li>{@code -Dghidra.i18n.strict=true} — log WARN on missing keys
 *       (intended for CI). Off by default.</li>
 * </ul>
 */
public final class I18n {

	private static final String CORE_MODULE = "core";
	private static final String BUNDLE_BASE = ".messages";
	private static final boolean STRICT =
		Boolean.getBoolean("ghidra.i18n.strict");

	private static final ConcurrentHashMap<String, ResourceBundle> CACHE =
		new ConcurrentHashMap<>();

	private I18n() {
	}

	/**
	 * Look up the translated string for {@code key}. Returns {@code ???key???}
	 * placeholder if not found in any bundle. Never throws.
	 */
	public static String tr(String key) {
		String value = lookup(key);
		return value != null ? value : "???" + key + "???";
	}

	/**
	 * Look up and {@link MessageFormat#format} with the supplied arguments.
	 * Returns {@code ???key???} placeholder if not found.
	 */
	public static String tr(String key, Object... args) {
		String pattern = lookup(key);
		if (pattern == null) {
			return "???" + key + "???";
		}
		if (args == null || args.length == 0) {
			return pattern;
		}
		return MessageFormat.format(pattern, args);
	}

	/**
	 * Returns the {@link ResourceBundle} for a module by name. Intended for
	 * hot paths where repeated {@link #tr(String)} lookups would be wasteful.
	 * Throws {@link MissingResourceException} if the bundle does not exist.
	 */
	public static ResourceBundle bundle(String module) {
		return resolveBundle(module);
	}

	private static String lookup(String key) {
		int dot = key.indexOf('.');
		String module = (dot > 0) ? key.substring(0, dot) : CORE_MODULE;
		String value = tryBundle(module, key);
		if (value == null && !CORE_MODULE.equals(module)) {
			value = tryBundle(CORE_MODULE, key);
		}
		if (value == null && STRICT) {
			System.err.println("[i18n] missing key: " + key + " (locale=" +
				java.util.Locale.getDefault() + ")");
		}
		return value;
	}

	private static String tryBundle(String module, String key) {
		ResourceBundle bundle;
		try {
			bundle = resolveBundle(module);
		}
		catch (MissingResourceException e) {
			return null;
		}
		try {
			return bundle.getString(key);
		}
		catch (MissingResourceException e) {
			return null;
		}
	}

	private static ResourceBundle resolveBundle(String module) {
		return CACHE.computeIfAbsent(module,
			m -> ResourceBundle.getBundle(m + BUNDLE_BASE));
	}

	/**
	 * Test-only: drop cached bundles so a subsequent lookup picks up the
	 * current {@link java.util.Locale#getDefault()}.
	 */
	static void clearCacheForTesting() {
		CACHE.clear();
	}
}
