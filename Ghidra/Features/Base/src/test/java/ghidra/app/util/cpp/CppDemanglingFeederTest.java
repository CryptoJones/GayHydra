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

import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.app.util.demangler.DemangledDataType;
import ghidra.app.util.demangler.DemangledFunction;
import ghidra.app.util.demangler.DemangledNamespaceNode;
import ghidra.app.util.demangler.DemangledParameter;
import ghidra.app.util.demangler.DemangledVariable;
import ghidra.program.model.data.FunctionDefinition;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.StandAloneDataTypeManager;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;

/**
 * Headless unit tests for the {@code CppDemanglingFeeder} (Rec 37 {@code #37-3}, grounded by
 * DD-0012). These build {@link DemangledFunction} fixtures directly — no demangler is invoked, so
 * the suite runs in {@code Features/Base} with no native {@code c++filt} process and no dependency
 * on the {@code GnuDemangler}/{@code MicrosoftDemangler} modules.
 */
public class CppDemanglingFeederTest extends AbstractGenericTest {

	public CppDemanglingFeederTest() {
		super();
	}

	/**
	 * Builds a chain of {@link DemangledNamespaceNode}s so the deepest node's
	 * {@link DemangledNamespaceNode#getNamespaceString()} is the {@code ::}-joined path. Passing
	 * {@code "Bar", "Fred"} yields a node whose namespace string is {@code "Bar::Fred"}.
	 */
	private static DemangledNamespaceNode namespace(String... parts) {
		DemangledNamespaceNode node = null;
		for (String part : parts) {
			DemangledNamespaceNode next = new DemangledNamespaceNode(part, part, part);
			next.setNamespace(node);
			node = next;
		}
		return node;
	}

	// ----- demangled signatures (#37-12a) -----

	@Test
	public void testSignaturePopulatesFromDemangledTypes() {
		CppTypeSystem ts = new CppTypeSystem(new StandAloneDataTypeManager("test"));
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);
		DemangledFunction f = function("area", "Shape");
		f.setReturnType(new DemangledDataType("int", "int", "int"));
		f.addParameter(new DemangledParameter(new DemangledDataType("int", "int", "int")));

		CppMethod method = feeder.feed(f);

		FunctionDefinition signature = method.getSignature();
		assertNotNull("the demangled signature must populate the method", signature);
		assertEquals("int", signature.getReturnType().getName());
		assertEquals(1, signature.getArguments().length);
		assertEquals("int", signature.getArguments()[0].getDataType().getName());
	}

	@Test
	public void testNoReturnTypeKeepsDefinitionDefaultReturn() {
		// A constructor form records no return type; the signature still populates with the
		// definition's default return rather than declining.
		CppTypeSystem ts = new CppTypeSystem(new StandAloneDataTypeManager("test"));
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);
		DemangledFunction f = function("Shape", "Shape");
		f.addParameter(new DemangledParameter(new DemangledDataType("int", "int", "int")));

		CppMethod method = feeder.feed(f);

		assertNotNull(method.getSignature());
		assertEquals(1, method.getSignature().getArguments().length);
	}

	@Test
	public void testSignatureStaysNullWithoutDataTypeManager() {
		// The bare model-only type system has no DataTypeManager to resolve types against; the
		// method still feeds (name, qualifiers, convention), carrying no signature -- never-wrong.
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);
		DemangledFunction f = function("area", "Shape");
		f.setReturnType(new DemangledDataType("int", "int", "int"));

		CppMethod method = feeder.feed(f);

		assertNotNull("the method must still feed", method);
		assertNull("no DTM means no signature", method.getSignature());
	}

	/**
	 * Builds a {@link DemangledFunction} named {@code name} enclosed in the given namespace path.
	 * A null/empty path leaves the function namespace-less (a free function).
	 */
	private static DemangledFunction function(String name, String... namespaceParts) {
		DemangledFunction f = new DemangledFunction(name, name, name);
		if (namespaceParts.length > 0) {
			f.setNamespace(namespace(namespaceParts));
		}
		return f;
	}

	private static Structure struct(String name, int fieldCount) {
		StructureDataType s = new StructureDataType(name, 0);
		for (int i = 0; i < fieldCount; i++) {
			s.add(IntegerDataType.dataType, 4, "f" + i, null);
		}
		return s;
	}

	@Test(expected = IllegalArgumentException.class)
	public void testFeederRejectsNullTypeSystem() {
		new CppDemanglingFeeder(null);
	}

	@Test
	public void testNamespacedFunctionCreatesPlaceholderClassAndMethod() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		CppMethod method = feeder.feed(function("Close", "ATL", "CRegKey"));

		assertNotNull("a namespaced member function must be fed", method);
		assertEquals("Close", method.getName());

		CppClass cls = ts.getCppClass("ATL::CRegKey");
		assertNotNull("the enclosing class must be resolved by its fully-qualified name", cls);
		assertEquals(1, cls.getMethods().size());
		assertSame(method, cls.getMethods().get(0));
	}

	@Test
	public void testPlaceholderBackingIsEmptyStructureNamedByFqn() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		feeder.feed(function("Fred", "Bar", "Fred"));

		CppClass cls = ts.getCppClass("Bar::Fred");
		assertNotNull(cls);
		assertEquals("Bar::Fred", cls.getName());
		assertEquals("a layout-less placeholder must have no recovered components",
			0, cls.getBackingStructure().getNumDefinedComponents());
	}

	@Test
	public void testTrailingConstMapsToIsConst() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		DemangledFunction f = function("size", "std", "vector");
		f.setTrailingConst();

		CppMethod method = feeder.feed(f);
		assertTrue("a trailing-const member must map to CppMethod.isConst", method.isConst());
		assertFalse(method.isStatic());
	}

	@Test
	public void testMemberFunctionHasImplicitThisAtOrdinalZero() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		DemangledFunction f = function("draw", "Widget");
		f.setCallingConvention("__thiscall");

		CppMethod method = feeder.feed(f);
		assertFalse(method.isStatic());
		CppCallingConvention conv = method.getCallingConvention();
		assertNotNull(conv);
		assertEquals("__thiscall", conv.getName());
		assertTrue("a non-static member carries an implicit this", conv.hasImplicitThis());
		assertEquals(0, conv.getThisParameterOrdinal());
	}

	@Test
	public void testStaticMemberHasNoImplicitThis() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		DemangledFunction f = function("instance", "Singleton");
		f.setStatic(true);
		f.setCallingConvention("__cdecl");

		CppMethod method = feeder.feed(f);
		assertTrue(method.isStatic());
		CppCallingConvention conv = method.getCallingConvention();
		assertEquals("__cdecl", conv.getName());
		assertFalse("a static member has no implicit this", conv.hasImplicitThis());
		assertEquals(CppCallingConvention.NO_THIS, conv.getThisParameterOrdinal());
	}

	@Test
	public void testCallingConventionNameTakenVerbatimAndNullPreserved() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		// demangler recorded no convention -> name stays null, ordinal still reflects member-ness
		CppMethod method = feeder.feed(function("ping", "Net"));
		CppCallingConvention conv = method.getCallingConvention();
		assertNotNull(conv);
		assertNull(conv.getName());
		assertTrue(conv.hasImplicitThis());
	}

	@Test
	public void testVirtualIsNotInferredFromAName() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		// a demangled name alone cannot reveal vtable membership (deferred to #37-6)
		CppMethod method = feeder.feed(function("~Widget", "Widget"));
		assertFalse(method.isVirtual());
		assertFalse(method.isPureVirtual());
	}

	@Test
	public void testFeedingIntoAnAlreadyDefinedClassReusesIt() {
		CppTypeSystem ts = new CppTypeSystem();
		Structure recovered = struct("Widget", 3);
		CppClass existing = ts.defineClass(recovered);
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		CppMethod method = feeder.feed(function("draw", "Widget"));

		CppClass cls = ts.getCppClass("Widget");
		assertSame("the feeder must reuse the pre-defined class, not replace it", existing, cls);
		assertSame("the recovered backing must not be clobbered by a placeholder",
			recovered, cls.getBackingStructure());
		assertEquals(3, cls.getBackingStructure().getNumDefinedComponents());
		assertEquals(1, cls.getMethods().size());
		assertSame(method, cls.getMethods().get(0));
	}

	@Test
	public void testRepeatedFeedsAccumulateMethodsOnOneClass() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		feeder.feed(function("open", "File"));
		feeder.feed(function("close", "File"));

		assertEquals("repeated feeds must not mint duplicate classes", 1, ts.getCppClasses().size());
		CppClass cls = ts.getCppClass("File");
		assertEquals(2, cls.getMethods().size());
	}

	@Test
	public void testFreeFunctionIsANoOp() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		assertNull("a free function (no namespace) must not be fed", feeder.feed(function("malloc")));
		assertTrue(ts.getCppClasses().isEmpty());
	}

	@Test
	public void testNonFunctionObjectIsANoOp() {
		CppTypeSystem ts = new CppTypeSystem();
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(ts);

		DemangledVariable var = new DemangledVariable("count", "count", "count");
		var.setNamespace(namespace("Widget"));
		assertNull("a non-function demangled object must not be fed", feeder.feed(var));
		assertTrue(ts.getCppClasses().isEmpty());
	}

	@Test
	public void testNullInputIsANoOp() {
		CppDemanglingFeeder feeder = new CppDemanglingFeeder(new CppTypeSystem());
		assertNull(feeder.feed(null));
	}
}
