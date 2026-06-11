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

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.program.model.lang.LanguageID;
import ghidra.util.NumericUtilities;

/**
 * Golden-case validation of the Rec 40 difftest's Ghidra half (DD-0079 slice 1): hand-computed
 * x86-64 single-instruction deltas. Until a vendoring-approved {@link DifftestReference} adapter
 * exists, this corpus <em>is</em> the reference the executor is validated against — every value
 * below is computable from the Intel SDM by hand.
 */
public class SleighInstructionExecutorTest extends AbstractGenericTest {

	private static final LanguageID X64 = new LanguageID("x86:LE:64:default");

	private final SleighInstructionExecutor executor = new SleighInstructionExecutor();

	private static byte[] insn(String hex) {
		return NumericUtilities.convertStringToBytes(hex);
	}

	@Test
	public void testGoldenAddRaxRbx() throws Exception {
		// 48 01 D8 = ADD RAX, RBX ; 2 + 3 = 5
		Map<String, BigInteger> result = executor.step(X64, insn("4801d8"),
			Map.of("RAX", BigInteger.valueOf(2), "RBX", BigInteger.valueOf(3)),
			List.of("RAX", "RBX"));

		assertEquals(BigInteger.valueOf(5), result.get("RAX"));
		assertEquals("the source operand must be untouched", BigInteger.valueOf(3),
			result.get("RBX"));
	}

	@Test
	public void testGoldenSubWrapsToAllOnes() throws Exception {
		// 48 29 D8 = SUB RAX, RBX ; 2 - 3 = -1 = 0xFFFF_FFFF_FFFF_FFFF (64-bit two's complement)
		Map<String, BigInteger> result = executor.step(X64, insn("4829d8"),
			Map.of("RAX", BigInteger.valueOf(2), "RBX", BigInteger.valueOf(3)),
			List.of("RAX"));

		assertEquals(new BigInteger("ffffffffffffffff", 16), result.get("RAX"));
	}

	@Test
	public void testGoldenMovAndCarryFlag() throws Exception {
		// 48 89 D8 = MOV RAX, RBX ; also assert CF cleared by a prior-state ADD carry-out:
		// first the MOV golden alone.
		Map<String, BigInteger> result = executor.step(X64, insn("4889d8"),
			Map.of("RAX", BigInteger.ZERO, "RBX", BigInteger.valueOf(0x1234)),
			List.of("RAX"));
		assertEquals(BigInteger.valueOf(0x1234), result.get("RAX"));

		// ADD with unsigned carry-out: RAX = ~0, RBX = 1 -> RAX = 0, CF = 1.
		Map<String, BigInteger> carry = executor.step(X64, insn("4801d8"),
			Map.of("RAX", new BigInteger("ffffffffffffffff", 16), "RBX", BigInteger.ONE),
			List.of("RAX", "CF"));
		assertEquals(BigInteger.ZERO, carry.get("RAX"));
		assertEquals("the unsigned overflow must set CF", BigInteger.ONE, carry.get("CF"));
	}

	@Test
	public void testRejectsUnknownRegisterAndBadArguments() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> executor.step(X64, insn("4801d8"),
			Map.of("NO_SUCH_REG", BigInteger.ONE), List.of("RAX")));
		assertThrows(IllegalArgumentException.class,
			() -> executor.step(X64, new byte[0], Map.of(), List.of()));
		assertThrows(IllegalArgumentException.class,
			() -> executor.step(null, insn("90"), Map.of(), List.of()));
	}
}
