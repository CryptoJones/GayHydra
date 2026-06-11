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
package ghidra.pcode.difftest;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ghidra.pcode.exec.PcodeArithmetic;
import ghidra.pcode.exec.PcodeArithmetic.Purpose;
import ghidra.pcode.emu.BytesPcodeThread;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeExecutorState;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Language;
import ghidra.program.model.lang.LanguageID;
import ghidra.program.model.lang.Register;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.util.DefaultLanguageService;

/**
 * The Ghidra half of the Rec 40 difftest (DD-0079 slice 1): executes one instruction through the
 * in-tree {@link PcodeEmulator} — the Sleigh-spec-under-test path — and samples registers. Rides
 * the proven {@code AbstractEmulationEquivalenceTest} pattern verbatim: inject the instruction
 * bytes into shared state, seed registers through the thread's arithmetic, set the counter,
 * default the context, step once, read back.
 *
 * <p>Until a real {@link DifftestReference} adapter exists (vendoring-gated), the golden corpus in
 * {@code SleighInstructionExecutorTest} is the reference this executor is validated against.
 */
public class SleighInstructionExecutor implements DifftestReference {

	/**
	 * Where the instruction bytes are placed; arbitrary but fixed so reproducers are stable.
	 */
	public static final long ENTRY_OFFSET = 0x00400000L;

	@Override
	public Map<String, BigInteger> step(LanguageID languageId, byte[] instruction,
			Map<String, BigInteger> initialRegisters, List<String> sampleRegisters)
			throws Exception {
		if (languageId == null || instruction == null || instruction.length == 0 ||
			initialRegisters == null || sampleRegisters == null) {
			throw new IllegalArgumentException("all arguments must be non-null (and at least one instruction byte)");
		}
		Language language =
			DefaultLanguageService.getLanguageService().getLanguage(languageId);
		Address entry = language.getDefaultSpace().getAddress(ENTRY_OFFSET);

		PcodeEmulator emulator = new PcodeEmulator(language) {
			@Override
			protected BytesPcodeThread createThread(String name) {
				return new BytesPcodeThread(name, this) {
					@Override
					protected boolean onMissingUseropDef(PcodeOp op, String opName) {
						// A spec invoking an undefined userop is a finding, not a crash.
						return false;
					}
				};
			}
		};
		emulator.getSharedState().setVar(entry, instruction.length, false, instruction);

		PcodeThread<byte[]> thread = emulator.newThread();
		PcodeExecutorState<byte[]> state = thread.getState();
		PcodeArithmetic<byte[]> arithmetic = thread.getArithmetic();

		for (Map.Entry<String, BigInteger> seed : initialRegisters.entrySet()) {
			Register register = requireRegister(language, seed.getKey());
			state.setVar(register,
				arithmetic.fromConst(seed.getValue(), register.getNumBytes()));
		}

		thread.setCounter(entry);
		thread.overrideContextWithDefault();
		thread.stepInstruction(1);

		Map<String, BigInteger> sampled = new LinkedHashMap<>();
		for (String name : sampleRegisters) {
			Register register = requireRegister(language, name);
			sampled.put(name, arithmetic.toBigInteger(
				state.getVar(register, Reason.INSPECT), Purpose.INSPECT));
		}
		return sampled;
	}

	private static Register requireRegister(Language language, String name) {
		Register register = language.getRegister(name);
		if (register == null) {
			throw new IllegalArgumentException(
				"unknown register for " + language.getLanguageID() + ": " + name);
		}
		return register;
	}
}
