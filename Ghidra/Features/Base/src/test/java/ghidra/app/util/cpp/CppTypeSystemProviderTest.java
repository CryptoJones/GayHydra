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

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.app.plugin.core.analysis.TransientProgramProperties;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;

/**
 * Headless tests for the Rec 37 {@code #37-11a} {@link CppTypeSystemProvider} (DD-0062): one
 * {@link CppTypeSystem} per open program, shared between contributors and consumers, bound to the
 * program's {@code DataTypeManager}, released when the program closes.
 */
public class CppTypeSystemProviderTest extends AbstractGenericTest {

	@Test
	public void testSameProgramYieldsSameInstance() throws Exception {
		ProgramBuilder builder = new ProgramBuilder("provider", ProgramBuilder._X64);
		try {
			ProgramDB program = builder.getProgram();
			CppTypeSystem first = CppTypeSystemProvider.get(program);
			CppTypeSystem second = CppTypeSystemProvider.get(program);
			assertSame("every request for one program must share one type system", first, second);
		}
		finally {
			builder.dispose();
		}
	}

	@Test
	public void testBoundToProgramDataTypeManager() throws Exception {
		ProgramBuilder builder = new ProgramBuilder("provider", ProgramBuilder._X64);
		try {
			ProgramDB program = builder.getProgram();
			assertSame("the created type system must bind the program's DataTypeManager",
				program.getDataTypeManager(),
				CppTypeSystemProvider.get(program).getDataTypeManager());
		}
		finally {
			builder.dispose();
		}
	}

	@Test
	public void testDistinctProgramsYieldDistinctInstances() throws Exception {
		ProgramBuilder builderA = new ProgramBuilder("providerA", ProgramBuilder._X64);
		ProgramBuilder builderB = new ProgramBuilder("providerB", ProgramBuilder._X64);
		try {
			assertNotSame("two programs must not share a type system",
				CppTypeSystemProvider.get(builderA.getProgram()),
				CppTypeSystemProvider.get(builderB.getProgram()));
		}
		finally {
			builderA.dispose();
			builderB.dispose();
		}
	}

	@Test
	public void testContributionVisibleToLaterConsumers() throws Exception {
		// The contributor/consumer flow the provider exists for: a feeder writes through one get()
		// handle, a consumer reads the same graph through another.
		ProgramBuilder builder = new ProgramBuilder("provider", ProgramBuilder._X64);
		try {
			ProgramDB program = builder.getProgram();
			new CppRttiFeeder(CppTypeSystemProvider.get(program)).feedClass("Circle", List.of());

			assertNotNull("a class fed by a contributor must be visible to a later consumer",
				CppTypeSystemProvider.get(program).getCppClass("Circle"));
		}
		finally {
			builder.dispose();
		}
	}

	@Test
	public void testReleasedWhenProgramCloses() throws Exception {
		ProgramBuilder builder = new ProgramBuilder("provider", ProgramBuilder._X64);
		ProgramDB program = builder.getProgram();
		CppTypeSystemProvider.get(program);
		assertTrue("the property must be cached while the program is open",
			TransientProgramProperties.hasProperty(program, CppTypeSystem.class));

		builder.dispose();

		assertFalse("closing the program must release the cached type system",
			TransientProgramProperties.hasProperty(program, CppTypeSystem.class));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNullProgram() {
		CppTypeSystemProvider.get(null);
	}
}
