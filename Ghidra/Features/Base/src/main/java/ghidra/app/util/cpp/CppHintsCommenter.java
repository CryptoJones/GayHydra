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
package ghidra.app.util.cpp;

import java.util.List;

import ghidra.app.util.cpp.CppHintsCollector.CppHint;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;

/**
 * The first visible consumer of {@link CppHintsCollector} ({@code #37-11d-2}): writes collected
 * {@link CppHint}s into the listing as {@code PRE} comments at their sites, in the form
 * {@code C++: param_1->draw()} — visible in both the listing and the decompiler view (which
 * displays {@code PRE} comments by default, unlike {@code EOL}), without the {@code DISPLAY}-bound
 * margin surface Sprint 14 has deferred.
 *
 * <p><b>Idempotent and additive.</b> A hint line already present at the site is skipped, so
 * re-running an annotation pass (the same posture as the analyzer re-triggers) never duplicates;
 * an existing unrelated {@code PRE} comment is preserved, with the hint line appended below it —
 * the commenter never clobbers a user's comment.
 *
 * <p>The caller owns the transaction (a script or analyzer runs inside its own), matching upstream
 * listing-mutation utilities. Null arguments are programming errors and are rejected.
 */
public final class CppHintsCommenter {

	/**
	 * The marker every written hint line starts with — also the idempotence key.
	 */
	public static final String HINT_PREFIX = "C++: ";

	private CppHintsCommenter() {
		// static utility
	}

	/**
	 * Writes one {@code PRE}-comment line per hint at its site, skipping lines already present.
	 *
	 * @param program the program whose listing to annotate; must not be null
	 * @param hints the collected hints to write; must not be null (may be empty)
	 * @return the number of hint lines actually written (0 when everything was already present)
	 */
	public static int annotate(Program program, List<CppHint> hints) {
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		if (hints == null) {
			throw new IllegalArgumentException("hints must not be null");
		}
		Listing listing = program.getListing();
		int written = 0;
		for (CppHint hint : hints) {
			String line = HINT_PREFIX + hint.rendering();
			String existing = listing.getComment(CommentType.PRE, hint.site());
			if (existing != null && containsLine(existing, line)) {
				continue;
			}
			listing.setComment(hint.site(), CommentType.PRE,
				existing == null ? line : existing + "\n" + line);
			written++;
		}
		return written;
	}

	private static boolean containsLine(String comment, String line) {
		for (String commentLine : comment.split("\n", -1)) {
			if (commentLine.equals(line)) {
				return true;
			}
		}
		return false;
	}
}
