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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Builds a {@code en-XA} pseudo-locale bundle from a source
 * {@code messages.properties}. Each value is wrapped in
 * {@code [!! ... !!]} with selected ASCII characters replaced by Latin
 * diacritic equivalents — {@code Close} becomes {@code [!! Cłöşé !!]}.
 *
 * <p>Used for visual QA: launch with {@code -Dghidra.locale=en-XA} and
 * any unbracketed string is a hardcoded literal that escaped the sweep.
 *
 * <p><code>java.text.MessageFormat</code> placeholders (<code>'{0}'</code>,
 * <code>"'{'"</code>, etc.) are passed through unmodified — only literal
 * characters get pseudo-ed.
 */
public final class PseudoLocaleGenerator {

	private static final String PREFIX = "[!! ";
	private static final String SUFFIX = " !!]";

	private static final Map<Character, Character> SUBS = buildSubs();

	private PseudoLocaleGenerator() {
	}

	private static Map<Character, Character> buildSubs() {
		Map<Character, Character> m = new LinkedHashMap<>();
		m.put('a', 'ä'); m.put('A', 'Ä');
		m.put('c', 'ç'); m.put('C', 'Ç');
		m.put('e', 'é'); m.put('E', 'É');
		m.put('i', 'í'); m.put('I', 'Í');
		m.put('l', 'ł'); m.put('L', 'Ł');
		m.put('n', 'ñ'); m.put('N', 'Ñ');
		m.put('o', 'ö'); m.put('O', 'Ö');
		m.put('s', 'ş'); m.put('S', 'Ş');
		m.put('u', 'ü'); m.put('U', 'Ü');
		return m;
	}

	/** Wrap a single source value with pseudo-locale decorations. */
	public static String pseudo(String input) {
		if (input == null || input.isEmpty()) {
			return input;
		}
		StringBuilder sb = new StringBuilder(input.length() + 8);
		sb.append(PREFIX);
		boolean inPlaceholder = false;
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '{') {
				inPlaceholder = true;
				sb.append(c);
				continue;
			}
			if (c == '}') {
				inPlaceholder = false;
				sb.append(c);
				continue;
			}
			if (inPlaceholder) {
				sb.append(c);
				continue;
			}
			Character sub = SUBS.get(c);
			sb.append(sub != null ? sub : c);
		}
		sb.append(SUFFIX);
		return sb.toString();
	}

	/**
	 * Read {@code source} (a {@code messages.properties} file) and write a
	 * sibling {@code messages_en_XA.properties} with pseudo'd values.
	 *
	 * @param source path to the source properties file
	 * @param target path the pseudo bundle should be written to
	 */
	public static void generate(Path source, Path target) throws IOException {
		Properties props = new Properties();
		try (var in = Files.newInputStream(source)) {
			props.load(in);
		}

		Properties out = new Properties();
		for (String key : props.stringPropertyNames()) {
			out.setProperty(key, pseudo(props.getProperty(key)));
		}

		Files.createDirectories(target.getParent());
		try (OutputStream stream = Files.newOutputStream(target)) {
			out.store(stream,
				"Auto-generated pseudo-locale from " + source.getFileName() +
				". DO NOT EDIT - regenerate with :generatePseudoLocale.");
		}
	}

	/**
	 * CLI entry point so the Gradle {@code :generatePseudoLocale} task can
	 * invoke this from a {@code JavaExec}.
	 *
	 * <p>Usage: {@code java generic.i18n.PseudoLocaleGenerator <source> <target>}
	 */
	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.err.println(
				"Usage: PseudoLocaleGenerator <source-properties> <target-properties>");
			System.exit(2);
		}
		generate(Path.of(args[0]), Path.of(args[1]));
	}
}
