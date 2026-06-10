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

import ghidra.program.model.address.Address;

/**
 * One value-identity in the Rec 38 scope graph (RFC-0002 {@code #38-2}): an opaque handle to "the
 * same value" as it appears across functions, so a rename of one appearance can find the others.
 * The four kinds mirror the RFC's enum; each is a value-semantics record, so two nodes describing
 * the same identity are <em>equal</em> — the graph deduplicates by this equality, no synthetic ids.
 */
public sealed interface ScopeNode {

	/**
	 * A global variable, identified by its address.
	 *
	 * @param address the global's address; must not be null
	 */
	record GlobalAddress(Address address) implements ScopeNode {
		public GlobalAddress {
			if (address == null) {
				throw new IllegalArgumentException("address must not be null");
			}
		}
	}

	/**
	 * A structure field, identified by the structure's name and the field's byte offset. The
	 * structure is named (not a {@code DataType} reference) so the node stays value-semantic and
	 * program-decoupled; resolution back to a live type is the consumer's concern.
	 *
	 * @param structureName the structure's name; must not be null or blank
	 * @param fieldOffset the field's byte offset within the structure; must not be negative
	 */
	record StructField(String structureName, int fieldOffset) implements ScopeNode {
		public StructField {
			if (structureName == null || structureName.isBlank()) {
				throw new IllegalArgumentException("structure name must not be null or blank");
			}
			if (fieldOffset < 0) {
				throw new IllegalArgumentException("field offset must not be negative");
			}
		}
	}

	/**
	 * A function parameter slot, identified by the function's entry address and the parameter's
	 * ordinal.
	 *
	 * @param functionEntry the owning function's entry address; must not be null
	 * @param parameterIndex the zero-based parameter ordinal; must not be negative
	 */
	record Parameter(Address functionEntry, int parameterIndex) implements ScopeNode {
		public Parameter {
			if (functionEntry == null) {
				throw new IllegalArgumentException("function entry must not be null");
			}
			if (parameterIndex < 0) {
				throw new IllegalArgumentException("parameter index must not be negative");
			}
		}
	}

	/**
	 * A local-variable equivalence class within one function, identified by the function's entry
	 * address and an analysis-assigned class id (stable within one population pass).
	 *
	 * @param functionEntry the owning function's entry address; must not be null
	 * @param equivalenceClassId the analysis-assigned class id; must not be negative
	 */
	record LocalEquiv(Address functionEntry, long equivalenceClassId) implements ScopeNode {
		public LocalEquiv {
			if (functionEntry == null) {
				throw new IllegalArgumentException("function entry must not be null");
			}
			if (equivalenceClassId < 0) {
				throw new IllegalArgumentException("equivalence class id must not be negative");
			}
		}
	}
}
