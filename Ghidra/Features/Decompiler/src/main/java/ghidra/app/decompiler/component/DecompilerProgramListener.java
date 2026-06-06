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

import java.util.Iterator;
import java.util.function.Consumer;

import ghidra.framework.model.*;
import ghidra.program.database.SpecExtension;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramChangeRecord;
import ghidra.program.util.ProgramEvent;
import ghidra.util.task.SwingUpdateManager;

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
	 * Returns the address set of a change batch iff <em>every</em> record in it is a function-local
	 * edit whose effect is bounded by an address range inside the changed function's body. Returns
	 * {@code null} the moment any record is not provably function-local, so the caller falls back to
	 * a full cache flush (the conservative default: liveness can only shrink, never grow stale).
	 *
	 * <p>Only {@link ProgramEvent#COMMENT_CHANGED} qualifies today: its record carries the code
	 * address of the comment, which lies in the owning function's body, so intersecting it against
	 * the cached function bodies invalidates exactly the right entries. Symbol renames are
	 * deliberately excluded — a local variable's symbol address is in stack/register space, not the
	 * function's code body, so it cannot be scoped by address intersection alone (see the DD-0009
	 * addendum); they remain on the full-flush path until a symbol-to-function mapping is added.
	 *
	 * @param ev the change event
	 * @return the changed addresses, or {@code null} if the batch is not entirely function-local
	 */
	private AddressSetView collectLocalChangeAddresses(DomainObjectChangedEvent ev) {
		AddressSet set = new AddressSet();
		Iterator<DomainObjectChangeRecord> iter = ev.iterator();
		while (iter.hasNext()) {
			DomainObjectChangeRecord record = iter.next();
			if (record.getEventType() != ProgramEvent.COMMENT_CHANGED) {
				return null;
			}
			if (!(record instanceof ProgramChangeRecord pcr)) {
				return null;
			}
			Address start = pcr.getStart();
			if (start == null) {
				return null;
			}
			Address end = pcr.getEnd() != null ? pcr.getEnd() : start;
			set.addRange(start, end);
		}
		return set.isEmpty() ? null : set;
	}

	public void dispose() {
		updater.dispose();
	}
}
