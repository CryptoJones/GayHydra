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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;

/**
 * The hints-consumer facade of the Rec 37 {@code #37-11} band ({@code #37-11d-1}): given one
 * decompiled {@link HighFunction}, runs <em>all seven</em> shipped recognition drivers against the
 * program's shared {@link CppTypeSystem} (obtained from {@link CppTypeSystemProvider}) and returns
 * their renderings as one uniform, site-ordered list of {@link CppHint}s.
 *
 * <p>This is the production assembly point the drivers were built for: until this class, every
 * driver was constructed only by tests, each with a hand-fed type system. The collector closes the
 * loop with the analyzer half — the {@code CppRttiAnalyzer} / {@code CppVTableAnalyzer} wrappers
 * feed the provider's instance during auto-analysis, and the collector reads that same instance, so
 * a consumer needs nothing beyond the {@code HighFunction} it already has.
 *
 * <p><b>Advisory, never wrong — inherited.</b> Each driver declines (contributes nothing) for any
 * site it cannot faithfully render, so the collector never invents a hint; an empty list is the
 * normal result for a function with no recognized C++ idioms or a program whose type system was
 * never fed. A null function is a programming error and is rejected.
 *
 * <p>Hints are ordered by site address (kind as tie-break) so consumers get a deterministic,
 * listing-ordered stream regardless of driver iteration order.
 */
public final class CppHintsCollector {

	/**
	 * The C++ idiom form a hint renders — one constant per shipped renderer form.
	 */
	public enum Kind {
		VIRTUAL_CALL,
		CONSTRUCTION,
		ARRAY_CONSTRUCTION,
		PLACEMENT_CONSTRUCTION,
		DESTRUCTOR_CALL,
		DELETE,
		CAST
	}

	/**
	 * One collected hint: where it applies, which idiom form it is, and the rendered C++ source.
	 *
	 * @param site the call/cast site the hint applies to
	 * @param kind the idiom form
	 * @param rendering the rendered C++ expression (e.g. {@code param_1->draw()}, {@code new C(5)})
	 */
	public record CppHint(Address site, Kind kind, String rendering) {}

	private CppHintsCollector() {
		// static facade
	}

	/**
	 * Runs all seven recognition drivers over the decompiled function against the program's shared
	 * type system and returns every rendered hint, ordered by site address.
	 *
	 * @param function the decompiled function to collect hints for; must not be null
	 * @return the collected {@link CppHint}s in site order (possibly empty, never null)
	 */
	public static List<CppHint> collect(HighFunction function) {
		if (function == null) {
			throw new IllegalArgumentException("function must not be null");
		}
		Program program = function.getFunction().getProgram();
		CppTypeSystem typeSystem = CppTypeSystemProvider.get(program);
		CppDecompilerHints renderer = new CppDecompilerHints();

		List<CppHint> hints = new ArrayList<>();
		new CppVirtualCallDriver(renderer, typeSystem).recognizeAndRender(function)
				.forEach(h -> hints.add(new CppHint(h.site(), Kind.VIRTUAL_CALL, h.rendering())));
		new CppConstructorDriver(renderer, typeSystem).recognizeAndRender(function)
				.forEach(h -> hints.add(new CppHint(h.site(), Kind.CONSTRUCTION, h.rendering())));
		new CppArrayConstructionDriver(renderer, typeSystem).recognizeAndRender(function)
				.forEach(h -> hints
						.add(new CppHint(h.site(), Kind.ARRAY_CONSTRUCTION, h.rendering())));
		new CppPlacementConstructionDriver(renderer, typeSystem).recognizeAndRender(function)
				.forEach(h -> hints
						.add(new CppHint(h.site(), Kind.PLACEMENT_CONSTRUCTION, h.rendering())));
		new CppDestructorDriver(renderer, typeSystem).recognizeAndRender(function)
				.forEach(h -> hints.add(new CppHint(h.site(), Kind.DESTRUCTOR_CALL, h.rendering())));
		new CppDeleteDriver(renderer).recognizeAndRender(function)
				.forEach(h -> hints.add(new CppHint(h.site(), Kind.DELETE, h.rendering())));
		new CppBaseCastDriver(renderer, typeSystem).recognizeAndRender(function)
				.forEach(h -> hints.add(new CppHint(h.site(), Kind.CAST, h.rendering())));

		hints.sort(Comparator.comparing(CppHint::site).thenComparing(CppHint::kind));
		return hints;
	}
}
