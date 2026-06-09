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

import ghidra.app.cmd.data.rtti.CppMsvcRttiDecoder.DecodedClass;
import ghidra.app.util.cpp.CppClass;
import ghidra.app.util.cpp.CppRttiFeeder;
import ghidra.program.model.data.InvalidDataTypeException;

/**
 * The driver half of Rec 37 {@code #37-5}: bridges the pure {@link CppMsvcRttiDecoder} MSVC RTTI decode
 * to the ABI-neutral {@link CppRttiFeeder}, attaching one decoded class's inheritance into a {@link
 * ghidra.app.util.cpp.CppTypeSystem}. Given a single already-laid-down MSVC RTTI structure &mdash; either a
 * {@code RTTICompleteObjectLocator} ({@link Rtti4Model}, the form a vftable points at) or the
 * {@code RTTIClassHierarchyDescriptor} ({@link Rtti3Model}) it references &mdash; it decodes the class's
 * name and direct bases and feeds them.
 *
 * <p><b>This slice ({@code #37-5-3}) feeds one located structure.</b> It does <em>not</em> scan a program
 * to <em>discover</em> the RTTI structures; finding every complete object locator in a binary (the
 * byte-search/vftable walk Ghidra's {@link
 * ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin.RttiAnalyzer} performs) is the program-coupled
 * analyzer wrapper, a later item. This is the headless, grounded core that wrapper will call once per
 * located class.
 *
 * <p><b>Advisory, never wrong.</b> Like the decoder it drives, a null model, one that does not validate, or
 * one that yields no class contributes nothing ({@code null}), never an exception or a mis-fed class. A
 * null {@code feeder}, by contrast, is a programming error and is rejected (matching {@link
 * CppRttiFeeder}'s own null-argument contract).
 */
public final class CppMsvcRttiDriver {

	private CppMsvcRttiDriver() {
		// static driver utility
	}

	/**
	 * Decodes the class behind one MSVC complete object locator and feeds its inheritance.
	 *
	 * @param completeObjectLocator the {@code RTTICompleteObjectLocator} (RTTI4) model; may be null
	 * @param feeder the type-system feeder to attach the class into; must not be null
	 * @return the fed derived {@link CppClass}, or null if the locator is null, does not validate, or
	 *         yields no class
	 */
	public static CppClass feedClass(Rtti4Model completeObjectLocator, CppRttiFeeder feeder) {
		requireFeeder(feeder);
		if (completeObjectLocator == null) {
			return null;
		}
		try {
			completeObjectLocator.validate();
			return feedDecoded(CppMsvcRttiDecoder.decodeClass(completeObjectLocator.getRtti3Model()),
				feeder);
		}
		catch (InvalidDataTypeException e) {
			return null;
		}
	}

	/**
	 * Decodes the class behind one MSVC class hierarchy descriptor and feeds its inheritance.
	 *
	 * @param classHierarchy the {@code RTTIClassHierarchyDescriptor} (RTTI3) model; may be null
	 * @param feeder the type-system feeder to attach the class into; must not be null
	 * @return the fed derived {@link CppClass}, or null if the descriptor is null, does not validate, or
	 *         yields no class
	 */
	public static CppClass feedClass(Rtti3Model classHierarchy, CppRttiFeeder feeder) {
		requireFeeder(feeder);
		return feedDecoded(CppMsvcRttiDecoder.decodeClass(classHierarchy), feeder);
	}

	private static CppClass feedDecoded(DecodedClass decoded, CppRttiFeeder feeder) {
		if (decoded == null) {
			return null;
		}
		return feeder.feedClass(decoded.derivedName(), decoded.directBases());
	}

	private static void requireFeeder(CppRttiFeeder feeder) {
		if (feeder == null) {
			throw new IllegalArgumentException("feeder must not be null");
		}
	}
}
