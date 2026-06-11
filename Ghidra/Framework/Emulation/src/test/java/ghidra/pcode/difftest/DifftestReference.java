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
import java.util.List;
import java.util.Map;

import ghidra.program.model.lang.LanguageID;

/**
 * The Rec 40 difftest's <em>reference seam</em> (DD-0079): one engine's answer to "execute this
 * single instruction from this register state and tell me the registers I care about". The
 * Ghidra-side {@link SleighInstructionExecutor} implements it; future reference adapters
 * (Unicorn, QEMU TCG, Spike, vendor test vectors — each behind its own vendoring decision)
 * implement the same contract, and the differential loop is then
 * {@code executor.step(…) equals reference.step(…)} over the sampled set.
 *
 * <p>The argument tuple is deliberately the plan doc's <em>reproducer record</em>: a mismatch is
 * fully reproduced by {@code (language id, instruction bytes, initial registers)} plus the two
 * engines' sampled outputs.
 */
public interface DifftestReference {

	/**
	 * Executes exactly one instruction and samples registers.
	 *
	 * @param languageId the Sleigh language to execute under (e.g. {@code x86:LE:64:default})
	 * @param instruction the instruction's encoded bytes
	 * @param initialRegisters register name &rarr; value to seed before the step
	 * @param sampleRegisters the register names to read back after the step
	 * @return sampled register name &rarr; value after the single step, in iteration order of
	 *         {@code sampleRegisters}
	 * @throws Exception engine-specific failure (undecodable instruction, unknown register, …)
	 */
	Map<String, BigInteger> step(LanguageID languageId, byte[] instruction,
			Map<String, BigInteger> initialRegisters, List<String> sampleRegisters)
			throws Exception;
}
