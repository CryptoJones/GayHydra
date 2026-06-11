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
package ghidra.app.util.scope;

import java.util.StringJoiner;

import db.Transaction;
import ghidra.app.util.scope.ScopeEdge.Kind;
import ghidra.app.util.scope.ScopeEdge.Origin;
import ghidra.app.util.scope.ScopeNode.GlobalAddress;
import ghidra.app.util.scope.ScopeNode.LocalEquiv;
import ghidra.app.util.scope.ScopeNode.Parameter;
import ghidra.app.util.scope.ScopeNode.StructField;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.ProgramUserData;

/**
 * The durability half of the Rec 38 scope graph (RFC-0002 {@code #38-2b}): persists the
 * {@link ScopeGraph#userAssertedEdges() user-asserted edges} — the only part of the graph that
 * cannot be recomputed — as one versioned, program-level string property in
 * {@link ProgramUserData}, and loads them back on a later open.
 *
 * <p><b>Why this shape (DD-0074):</b> the RFC's table schema has no public extension point, and
 * the analysis-produced edges are recomputed by their producers, so the whole storage problem
 * reduces to this small, per-user, forward-only blob — exactly what the {@code ProgramUserData}
 * program-level string properties offer. Per-user durability matches {@code ProgramUserData}'s
 * nature and is acceptable: assertions are the asserting user's overrides.
 *
 * <p><b>Forward-only, never-wrong loading.</b> A fresh program (no property) loads nothing — the
 * RFC's empty-on-first-open migration. An unknown format version loads nothing and leaves the
 * stored property untouched. A malformed or undecodable line (corruption, a renamed address space)
 * is skipped rather than failing the whole load; every line that decodes feeds through
 * {@link ScopeGraph#addEdge}, which deduplicates.
 */
public final class ScopeGraphUserAssertions {

	// Program-level string property key (fork-owned, namespaced) and the blob's format version.
	static final String PROPERTY_NAME = "GayHydra.ScopeGraph.UserAssertedEdges";
	static final String FORMAT_VERSION = "1";

	private static final String GLOBAL_TAG = "G";
	private static final String STRUCT_FIELD_TAG = "F";
	private static final String PARAMETER_TAG = "P";
	private static final String LOCAL_EQUIV_TAG = "L";

	private ScopeGraphUserAssertions() {
		// static persistence utility
	}

	/**
	 * Saves the graph's user-asserted edges, replacing any previously saved set (saving an empty
	 * set removes the property).
	 *
	 * @param graph the graph whose user-asserted edges to persist; must not be null
	 * @param program the program whose user data stores them; must not be null
	 */
	public static void save(ScopeGraph graph, Program program) {
		if (graph == null) {
			throw new IllegalArgumentException("graph must not be null");
		}
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		StringJoiner lines = new StringJoiner("\n");
		lines.add(FORMAT_VERSION);
		int edgeCount = 0;
		for (ScopeEdge edge : graph.userAssertedEdges()) {
			lines.add(encodeEdge(edge));
			edgeCount++;
		}
		ProgramUserData userData = program.getProgramUserData();
		try (Transaction tx = userData.openTransaction()) {
			if (edgeCount == 0) {
				userData.removeStringProperty(PROPERTY_NAME);
			}
			else {
				userData.setStringProperty(PROPERTY_NAME, lines.toString());
			}
		}
	}

	/**
	 * Loads previously saved user-asserted edges into the graph (deduplicated by
	 * {@link ScopeGraph#addEdge}).
	 *
	 * @param program the program whose user data to read; must not be null
	 * @param graph the graph to feed; must not be null
	 * @return the number of edges loaded (0 for a fresh program, an unknown format version, or a
	 *         fully undecodable blob)
	 */
	public static int load(Program program, ScopeGraph graph) {
		if (program == null) {
			throw new IllegalArgumentException("program must not be null");
		}
		if (graph == null) {
			throw new IllegalArgumentException("graph must not be null");
		}
		String stored = program.getProgramUserData().getStringProperty(PROPERTY_NAME, null);
		if (stored == null) {
			return 0;
		}
		String[] lines = stored.split("\n", -1);
		if (lines.length == 0 || !FORMAT_VERSION.equals(lines[0])) {
			return 0;
		}
		int loaded = 0;
		for (int i = 1; i < lines.length; i++) {
			ScopeEdge edge = decodeEdge(lines[i], program);
			if (edge != null && graph.addEdge(edge)) {
				loaded++;
			}
		}
		return loaded;
	}

	// One edge per line: kind|confidence|encodedSource|encodedDestination (origin is implicitly
	// USER_ASSERTED -- only those edges are saved).
	private static String encodeEdge(ScopeEdge edge) {
		return edge.kind().name() + "|" + Float.toString(edge.confidence()) + "|" +
			encodeNode(edge.source()) + "|" + encodeNode(edge.destination());
	}

	private static ScopeEdge decodeEdge(String line, Program program) {
		String[] fields = line.split("\\|", -1);
		if (fields.length != 4) {
			return null;
		}
		try {
			Kind kind = Kind.valueOf(fields[0]);
			float confidence = Float.parseFloat(fields[1]);
			ScopeNode source = decodeNode(fields[2], program);
			ScopeNode destination = decodeNode(fields[3], program);
			if (source == null || destination == null) {
				return null;
			}
			return new ScopeEdge(source, destination, kind, confidence, Origin.USER_ASSERTED);
		}
		catch (RuntimeException e) {
			// Corrupt field (unknown kind, bad float, contract violation) -- skip the line.
			return null;
		}
	}

	private static String encodeNode(ScopeNode node) {
		if (node instanceof GlobalAddress global) {
			return GLOBAL_TAG + ":" + encodeAddress(global.address());
		}
		if (node instanceof StructField field) {
			return STRUCT_FIELD_TAG + ":" + escape(field.structureName()) + ":" +
				field.fieldOffset();
		}
		if (node instanceof Parameter parameter) {
			return PARAMETER_TAG + ":" + encodeAddress(parameter.functionEntry()) + ":" +
				parameter.parameterIndex();
		}
		LocalEquiv local = (LocalEquiv) node;
		return LOCAL_EQUIV_TAG + ":" + encodeAddress(local.functionEntry()) + ":" +
			escape(local.storageKey());
	}

	private static ScopeNode decodeNode(String encoded, Program program) {
		String[] parts = encoded.split(":", -1);
		try {
			switch (parts[0]) {
				case GLOBAL_TAG:
					return parts.length == 3
							? new GlobalAddress(decodeAddress(parts[1], parts[2], program))
							: null;
				case STRUCT_FIELD_TAG:
					return parts.length == 3
							? new StructField(unescape(parts[1]), Integer.parseInt(parts[2]))
							: null;
				case PARAMETER_TAG:
					return parts.length == 4
							? new Parameter(decodeAddress(parts[1], parts[2], program),
								Integer.parseInt(parts[3]))
							: null;
				case LOCAL_EQUIV_TAG:
					return parts.length == 4
							? new LocalEquiv(decodeAddress(parts[1], parts[2], program),
								unescape(parts[3]))
							: null;
				default:
					return null;
			}
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static String encodeAddress(Address address) {
		return escape(address.getAddressSpace().getName()) + ":" +
			Long.toHexString(address.getOffset());
	}

	// Throws on an unknown space or unparsable offset; decodeNode's catch turns that into a
	// skipped line.
	private static Address decodeAddress(String spaceName, String offsetHex, Program program) {
		AddressSpace space =
			program.getAddressFactory().getAddressSpace(unescape(spaceName));
		if (space == null) {
			throw new IllegalArgumentException("unknown address space: " + spaceName);
		}
		return space.getAddress(Long.parseUnsignedLong(offsetHex, 16));
	}

	// Percent-escapes the characters the formats reserve (field/line separators and the escape
	// character itself), so structure and space names round-trip.
	private static String escape(String raw) {
		return raw.replace("%", "%25")
				.replace(":", "%3A")
				.replace("|", "%7C")
				.replace("\n", "%0A");
	}

	private static String unescape(String escaped) {
		return escaped.replace("%0A", "\n")
				.replace("%7C", "|")
				.replace("%3A", ":")
				.replace("%25", "%");
	}
}
