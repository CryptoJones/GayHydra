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
package ghidra.app.decompiler.component;

import static ghidra.framework.model.DomainObjectEvent.*;
import static ghidra.program.util.ProgramEvent.*;

import java.util.*;
import java.util.function.Consumer;

import ghidra.framework.model.*;
import ghidra.program.database.SpecExtension;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolType;
import ghidra.program.util.FunctionChangeRecord;
import ghidra.program.util.FunctionChangeRecord.FunctionChangeType;
import ghidra.program.util.ProgramChangeRecord;
import ghidra.program.util.ProgramEvent;
import ghidra.util.task.SwingUpdateManager;
import ghidra.util.task.TaskMonitor;

/**
 * Listener of {@link Program} events for decompiler panels. Program events are buffered using 
 * a {@link SwingUpdateManager} before triggering a new decompile process.
 */
public class DecompilerProgramListener implements DomainObjectListener {

	private DecompilerController controller;
	private SwingUpdateManager updater;
	private Consumer<AddressSetView> localChangeHandler;

	/**
	 * Construct a listener with a callback to be called when a decompile should occur. Program
	 * events are buffered using SwingUpdateManager before the callback is called.
	 * @param controller the DecompilerController
	 * @param callback the callback for when the decompile should be refreshed.
	 */
	public DecompilerProgramListener(DecompilerController controller, Runnable callback) {
		this(controller, new SwingUpdateManager(500, 5000, callback), null);
	}

	/**
	 * Construct a listener with a SwingUpdateManger that should be kicked for every
	 * program change.
	 * @param controller the DecompilerController
	 * @param updater A SwingUpdateManger to be kicked as program events are received which will
	 * eventually trigger a decompile refresh.
	 */
	public DecompilerProgramListener(DecompilerController controller, SwingUpdateManager updater) {
		this(controller, updater, null);
	}

	/**
	 * Construct a listener with a full-refresh updater plus a selective-invalidation handler.
	 * When a change batch consists entirely of function-local edits (e.g. a comment), the handler
	 * is invoked with just the changed addresses so only the affected functions are invalidated,
	 * instead of kicking the updater (which flushes the whole cache). See DD-0009.
	 * @param controller the DecompilerController
	 * @param updater the SwingUpdateManager kicked for changes that require a full cache flush
	 * @param localChangeHandler invoked with the changed address set for function-local edits, or
	 * {@code null} to always take the full-flush path
	 */
	public DecompilerProgramListener(DecompilerController controller, SwingUpdateManager updater,
			Consumer<AddressSetView> localChangeHandler) {
		this.controller = controller;
		this.updater = updater;
		this.localChangeHandler = localChangeHandler;
	}

	@Override
	public void domainObjectChanged(DomainObjectChangedEvent ev) {
		// Check for events that signal that a decompiler process' data is stale
		// and if so force a new process to be spawned
		if (ev.contains(MEMORY_BLOCK_ADDED, MEMORY_BLOCK_REMOVED, RESTORED)) {
			controller.resetDecompiler();
		}
		else if (ev.contains(DomainObjectEvent.PROPERTY_CHANGED)) {
			Iterator<DomainObjectChangeRecord> iter = ev.iterator();
			while (iter.hasNext()) {
				DomainObjectChangeRecord record = iter.next();
				if (record.getEventType() == DomainObjectEvent.PROPERTY_CHANGED) {
					if (record.getOldValue() instanceof String) {
						String value = (String) record.getOldValue();
						if (value.startsWith(SpecExtension.SPEC_EXTENSION)) {
							controller.resetDecompiler();
							break;
						}
					}
				}
			}
		}
		else {
			// Not a stale-process or property change: if the whole batch is function-local
			// edits (e.g. comments), invalidate only the affected functions rather than
			// flushing the entire cache. See DD-0009.
			AddressSetView localChanges = collectLocalChangeAddresses(ev);
			if (localChanges != null && localChangeHandler != null) {
				localChangeHandler.accept(localChanges);
				return;
			}
		}

		updater.update();
	}

	/**
	 * Returns the address set of a change batch iff <em>every</em> record in it can be mapped to the
	 * bodies of a bounded, provably-correct set of changed functions. Returns {@code null} the moment
	 * any record is not so mappable, so the caller falls back to a full cache flush (the conservative
	 * default: liveness can only shrink, never grow stale). See DD-0009 and its addenda 2 and 3.
	 *
	 * <p>Function-<em>local</em> record shapes (invalidate only the owning function):
	 * <ul>
	 * <li>{@link ProgramEvent#COMMENT_CHANGED} — its record carries the code address of the comment,
	 * which lies in the owning function's body, so its range maps directly.</li>
	 * <li>{@link ProgramEvent#SYMBOL_RENAMED} for a {@link SymbolType#LOCAL_VAR} or
	 * {@link SymbolType#PARAMETER} symbol — a local/parameter <em>name</em> change does not alter the
	 * signature callers render, so it is function-local. The symbol's address is in stack/register
	 * space (not the body), so it is mapped via its owning function's {@link Function#getBody() body}
	 * rather than by its own address.</li>
	 * <li>A companion {@link FunctionChangeRecord} with
	 * {@link FunctionChangeType#UNSPECIFIED} — admitted <em>only by correlation</em>, i.e. iff its
	 * function was already resolved from a sibling local/parameter rename in the same batch. A bare
	 * {@code UNSPECIFIED} function change (e.g. a stack-return-offset edit, which alters the callee
	 * purge that callers track, or a local retype) has no such sibling and forces the full flush.</li>
	 * </ul>
	 *
	 * <p>Cross-function record shape (invalidate the changed function <em>and its callers</em>),
	 * DD-0009 addendum 3 case A:
	 * <ul>
	 * <li>A {@link FunctionChangeRecord} that {@link FunctionChangeRecord#isFunctionSignatureChange()
	 * is a signature change} ({@code PARAMETERS_CHANGED}/{@code RETURN_TYPE_CHANGED}) or
	 * {@link FunctionChangeRecord#isFunctionModifierChange() is a modifier change}
	 * ({@code INLINE}/{@code NO_RETURN}/{@code CALL_FIXUP}/{@code PURGE}/{@code THUNK}) — every caller
	 * may render differently. The callers are resolved from the existing call-reference graph via
	 * {@link Function#getCallingFunctions(ghidra.util.task.TaskMonitor)} (no dependency bitmap), and
	 * the changed function's body plus each caller's body are added to the set.</li>
	 * </ul>
	 *
	 * <p>Any other record — a shared data type edit (no address; the {@code #36-3b-2} job) or symbol
	 * changes that are not local/parameter renames — returns {@code null}.
	 *
	 * @param ev the change event
	 * @return the changed addresses, or {@code null} if the batch is not entirely mappable
	 */
	private AddressSetView collectLocalChangeAddresses(DomainObjectChangedEvent ev) {
		AddressSet set = new AddressSet();
		Set<Function> localFunctions = new HashSet<>();
		Set<Function> callerAffectingFunctions = new HashSet<>();
		List<Function> companionFunctionChanges = new ArrayList<>();

		for (DomainObjectChangeRecord record : ev) {
			if (record.getEventType() == ProgramEvent.COMMENT_CHANGED) {
				if (!(record instanceof ProgramChangeRecord pcr) || pcr.getStart() == null) {
					return null;
				}
				Address start = pcr.getStart();
				Address end = pcr.getEnd() != null ? pcr.getEnd() : start;
				set.addRange(start, end);
			}
			else if (record.getEventType() == ProgramEvent.SYMBOL_RENAMED) {
				Function owner = localRenameOwner(record);
				if (owner == null) {
					return null;
				}
				localFunctions.add(owner);
			}
			else if (record.getEventType() == ProgramEvent.FUNCTION_CHANGED) {
				if (!(record instanceof FunctionChangeRecord fcr)) {
					return null;
				}
				if (fcr.isFunctionSignatureChange() || fcr.isFunctionModifierChange()) {
					Function changed = fcr.getFunction();
					if (changed == null) {
						return null;
					}
					callerAffectingFunctions.add(changed);
				}
				else if (fcr.getSpecificChangeType() == FunctionChangeType.UNSPECIFIED) {
					companionFunctionChanges.add(fcr.getFunction());
				}
				else {
					return null;
				}
			}
			else {
				return null;
			}
		}

		// A FUNCTION_CHANGED/UNSPECIFIED record is admitted only as the companion that
		// ProgramDB.symbolChanged fires alongside a local/parameter rename; one that arrives without
		// such a sibling (e.g. setStackReturnOffset, or a local retype) is not provably function-local
		// and forces the full flush. See DD-0009 addendum 2.
		for (Function function : companionFunctionChanges) {
			if (!localFunctions.contains(function)) {
				return null;
			}
		}

		// Map each resolved local/parameter rename to its owning function's body, reusing the same
		// address-intersection path as comment edits.
		for (Function function : localFunctions) {
			AddressSetView body = function.getBody();
			if (body == null) {
				return null;
			}
			set.add(body);
		}

		// A signature or caller-visible modifier change invalidates the changed function and every
		// function that calls it; the callers come from the existing call-reference graph, so no
		// per-function dependency bitmap is needed. See DD-0009 addendum 3 case A.
		for (Function function : callerAffectingFunctions) {
			AddressSetView body = function.getBody();
			if (body == null) {
				return null;
			}
			set.add(body);
			for (Function caller : function.getCallingFunctions(TaskMonitor.DUMMY)) {
				AddressSetView callerBody = caller.getBody();
				if (callerBody != null) {
					set.add(callerBody);
				}
			}
		}

		return set.isEmpty() ? null : set;
	}

	/**
	 * Resolves the function that owns a {@link ProgramEvent#SYMBOL_RENAMED} record iff it is a
	 * local-variable or parameter rename — the only symbol renames that are function-local. Returns
	 * {@code null} for any other symbol (labels, globals, the function's own name symbol, …), whose
	 * rename can affect other functions and must take the full-flush path.
	 *
	 * @param record the symbol-rename change record
	 * @return the owning function, or {@code null} if the rename is not a local/parameter rename
	 */
	private Function localRenameOwner(DomainObjectChangeRecord record) {
		if (!(record instanceof ProgramChangeRecord pcr) ||
			!(pcr.getObject() instanceof Symbol symbol)) {
			return null;
		}
		SymbolType symbolType = symbol.getSymbolType();
		if (symbolType != SymbolType.LOCAL_VAR && symbolType != SymbolType.PARAMETER) {
			return null;
		}
		return symbol.getParentNamespace() instanceof Function function ? function : null;
	}

	public void dispose() {
		updater.dispose();
	}
}
