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
package ghidra.app.cmd.data.rtti;

import ghidra.app.cmd.data.TypeDescriptorModel;
import ghidra.app.util.cpp.CppRttiFeeder.BaseSpec;
import ghidra.program.model.data.InvalidDataTypeException;

/**
 * The MSVC half of Rec 37 {@code #37-5}: decodes Microsoft Visual C++ RTTI descriptors into the
 * ABI-neutral {@link BaseSpec} inheritance facts that {@link ghidra.app.util.cpp.CppRttiFeeder} consumes.
 * This is the pure-decode core, the counterpart to the Itanium {@code #37-4b} work; it reads only the
 * already-laid-down {@link Rtti1Model RTTIBaseClassDescriptor} models and never scans a program (that is
 * the {@code #37-5} analyzer/driver slice that walks the binary and calls the feeder).
 *
 * <p><b>This slice ({@code #37-5-1}) decodes one descriptor.</b> {@link #decodeBase(Rtti1Model)} turns a
 * single {@code RTTIBaseClassDescriptor} into one direct-base fact:
 * <ul>
 * <li>the base class's name from its {@link TypeDescriptorModel#getDescriptorName() type descriptor}
 * (the demangled unqualified name, e.g. {@code Base} &mdash; the same form {@link
 * ghidra.app.util.cpp.CppTypeSystem} keys classes by),</li>
 * <li>its byte offset from the descriptor's {@code mdisp} (member displacement),</li>
 * <li>its access from the descriptor's {@code attributes}: {@code public} unless the
 * {@code BCD_PRIVORPROTBASE} bit is set.</li>
 * </ul>
 *
 * <p><b>Non-virtual bases only.</b> A descriptor whose {@code pdisp} is not {@code -1} names a
 * <em>virtual</em> base, whose true subobject offset is {@code *(vbtable + vdisp) + pdisp} &mdash; it
 * depends on the runtime {@code vbtable} contents, program data this pure descriptor decode cannot reach.
 * Such a descriptor is declined here (yields {@code null}); recovering virtual-base offsets is deferred
 * to the program-scanning analyzer slice that has the {@code vbtable}. For a non-virtual base
 * ({@code pdisp == -1}) the offset is exactly {@code mdisp}.
 *
 * <p><b>Advisory, never wrong.</b> Like the rest of the Rec 37 recognition pipeline, the decoder is
 * total-failure-safe: a null descriptor, a descriptor that does not validate, a virtual base, or one
 * whose type descriptor yields no printable name contributes no fact ({@code null}), never an exception
 * or a mis-decode.
 */
public final class CppMsvcRttiDecoder {

	/**
	 * The {@code BCD_PRIVORPROTBASE} bit of an {@code RTTIBaseClassDescriptor}'s {@code attributes}
	 * field: when set, the base is inherited {@code private} or {@code protected} rather than
	 * {@code public}. From the MSVC {@code <rttidata.h>} base-class-descriptor attribute flags (Ghidra's
	 * {@link Rtti1Model} exposes the raw {@code attributes} word but does not interpret it).
	 */
	private static final int BCD_PRIVORPROTBASE = 0x04;

	/** Sentinel {@code pdisp} marking a non-virtual base (a virtual base carries a vbtable offset). */
	private static final int PDISP_NONVIRTUAL = -1;

	private CppMsvcRttiDecoder() {
		// static decode utility
	}

	/**
	 * Decodes one MSVC {@code RTTIBaseClassDescriptor} into a direct-base fact.
	 *
	 * @param descriptor the base class descriptor model to decode; may be null
	 * @return the recovered {@link BaseSpec}, or null if the descriptor is null, does not validate,
	 *         names a virtual base (offset needs the runtime vbtable), or yields no printable base name
	 */
	public static BaseSpec decodeBase(Rtti1Model descriptor) {
		if (descriptor == null) {
			return null;
		}
		try {
			descriptor.validate();
			// A virtual base's offset is *(vbtable + vdisp) + pdisp — runtime data this pure decode
			// cannot reach; defer it to the program-scanning analyzer that has the vbtable.
			if (descriptor.getPDisp() != PDISP_NONVIRTUAL) {
				return null;
			}
			TypeDescriptorModel rtti0 = descriptor.getRtti0Model();
			if (rtti0 == null) {
				return null;
			}
			String baseName = rtti0.getDescriptorName();
			if (baseName == null || baseName.isBlank()) {
				return null;
			}
			long offset = descriptor.getMDisp();
			boolean isPublic = (descriptor.getAttributes() & BCD_PRIVORPROTBASE) == 0;
			return new BaseSpec(baseName, offset, false, isPublic);
		}
		catch (InvalidDataTypeException e) {
			return null;
		}
	}
}
