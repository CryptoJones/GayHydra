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

import static org.junit.Assert.*;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.junit.*;

import com.google.common.cache.*;

import docking.ComponentProvider;
import generic.test.TestUtils;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.plugin.core.codebrowser.CodeBrowserPlugin;
import ghidra.app.plugin.core.decompile.DecompilePlugin;
import ghidra.app.plugin.core.decompile.DecompilerProvider;
import ghidra.app.services.ProgramManager;
import ghidra.framework.options.ToolOptions;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginException;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.ByteDataType;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.util.ProgramLocation;
import ghidra.test.*;

public class DecompilerCachingTest extends AbstractGhidraHeadedIntegrationTest {

	private TestEnv env;
	private PluginTool tool;
	private CodeBrowserPlugin codeBrowser;
	private ProgramDB program;
	private List<Address> functionAddrs = new ArrayList<>();
	private DecompilerProvider decompilerProvider;
	public Cache<Function, DecompileResults> cache;
	private ToyProgramBuilder builder;

	@Before
	public void setUp() throws Exception {
		setErrorGUIEnabled(false);

		env = new TestEnv();
		tool = env.getTool();

		initializeTool();

		//@formatter:off
		cache = CacheBuilder
			.newBuilder()
			.maximumSize(3)
			.recordStats()
			.build()
			;
		//@formatter:on

		DecompilerController controller = decompilerProvider.getController();
		controller.setCache(cache);
	}

	@After
	public void tearDown() {
		env.dispose();
	}

	@Test
	public void testNewFunctionIsCacheMiss() {
		goTo(functionAddrs.get(0));
		CacheStats stats1 = cache.stats();

		goTo(functionAddrs.get(1));
		CacheStats stats2 = cache.stats();

		assertEquals("Expected hitCount to stay the same", stats1.hitCount(), stats2.hitCount());
		assertEquals("Expected missCount to increment", stats1.missCount() + 1, stats2.missCount());
	}

	@Test
	public void testReturnToPreviousFunctionIsCacheHit() {
		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		CacheStats stats1 = cache.stats();

		goTo(functionAddrs.get(0));
		CacheStats stats2 = cache.stats();

		assertEquals("Expected hitCount to increment", stats1.hitCount() + 1, stats2.hitCount());
		assertEquals("Expected missCount to stay the same", stats1.missCount(), stats2.missCount());
	}

	@Test
	public void testCacheRemovesEntryWhenSizeIsExceeded() {
		// cache size was set to 3 for this test
		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));
		goTo(functionAddrs.get(3));
		CacheStats stats1 = cache.stats();

		goTo(functionAddrs.get(2));
		goTo(functionAddrs.get(1));

		CacheStats stats2 = cache.stats();

		assertEquals("Expected hitCount to increment by 2", stats1.hitCount() + 2,
			stats2.hitCount());
		assertEquals("Expected missCount to stay the same", stats1.missCount(), stats2.missCount());

		goTo(functionAddrs.get(0));
		CacheStats stats3 = cache.stats();

		assertEquals("Expected hitCount to stay the same", stats2.hitCount(), stats3.hitCount());
		assertEquals("Expected missCount to stay to increment by 1", stats2.missCount() + 1,
			stats3.missCount());

	}

	@Test
	public void testLocalCommentChangeInvalidatesOnlyAffectedFunction() {
		// A function-local edit (here, a comment on the first function) must invalidate ONLY that
		// function's cache entry, leaving unrelated functions cached. See DD-0009.
		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));

		CacheStats stats1 = cache.stats();

		// comment on the first function only
		generateDomainObjectChange();

		goTo(functionAddrs.get(0)); // commented function: invalidated -> cache miss
		goTo(functionAddrs.get(1)); // untouched function: stayed cached -> cache hit

		CacheStats stats2 = cache.stats();

		assertEquals("Expected one miss (only the commented function was invalidated)",
			stats1.missCount() + 1, stats2.missCount());
		assertEquals("Expected one hit (the untouched function stayed cached)",
			stats1.hitCount() + 1, stats2.hitCount());
	}

	@Test
	public void testNonLocalChangeStillClearsTheCache() {
		// A change that is not provably function-local (here, adding a symbol) must fall back to the
		// conservative full cache flush, so revisiting any function is a miss. See DD-0009.
		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));

		CacheStats stats1 = cache.stats();

		builder.createLabel("0x1004000", "extraLabel"); // SYMBOL_ADDED, not on the local allow-list

		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));

		CacheStats stats2 = cache.stats();

		assertEquals("Expected hitCount to not change", stats1.hitCount(), stats2.hitCount());
		assertEquals("Expected missCount to increment by 2 (full flush)", stats1.missCount() + 2,
			stats2.missCount());
	}

	@Test
	public void testLocalVariableRenameInvalidatesOnlyAffectedFunction() throws Exception {
		// A local-variable rename is function-local, but its symbol address is in stack/register
		// space, not the code body, so address intersection alone cannot scope it. The listener must
		// resolve the owning function from the renamed symbol's SymbolType and invalidate ONLY that
		// function's body, leaving unrelated functions cached. See DD-0009 addendum 2.
		createLocalVarOnFirstFunction();

		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));

		CacheStats stats1 = cache.stats();

		renameLocalVarOnFirstFunction();

		goTo(functionAddrs.get(0)); // renamed function: invalidated -> cache miss
		goTo(functionAddrs.get(1)); // untouched function: stayed cached -> cache hit

		CacheStats stats2 = cache.stats();

		assertEquals("Expected one miss (only the renamed function was invalidated)",
			stats1.missCount() + 1, stats2.missCount());
		assertEquals("Expected one hit (the untouched function stayed cached)",
			stats1.hitCount() + 1, stats2.hitCount());
	}

	@Test
	public void testCalleeModifierChangeInvalidatesCalleeAndCallers() throws Exception {
		// A signature or caller-visible modifier change (here, marking the first function no-return,
		// which makes code after calls to it unreachable in CALLERS) invalidates the changed function
		// AND every function that calls it -- resolved from the call-reference graph, no dependency
		// bitmap -- while leaving unrelated functions cached. See DD-0009 addendum 3 case A.
		//
		// Asserted on cache contents rather than post-navigation hit/miss stats: selective
		// invalidation is what we are verifying, and a contents check is immune to the
		// revisiting-the-current-display no-op that a navigation-based check would trip over.
		makeSecondFunctionCallFirst();
		Function callee = functionAt(0); // fun1: the callee being changed
		Function caller = functionAt(1); // fun2: a caller of fun1
		Function unrelated = functionAt(2); // fun3: neither callee nor caller

		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));
		waitForCondition(() -> isCached(callee) && isCached(caller) && isCached(unrelated));

		markFirstFunctionNoReturn(); // NO_RETURN_CHANGED: caller-affecting

		// the changed callee and its caller are dropped; the unrelated function stays cached
		waitForCondition(() -> !isCached(callee) && !isCached(caller) && isCached(unrelated));
	}

	@Test
	public void testBareUnspecifiedFunctionChangeStillClearsTheCache() throws Exception {
		// A bare FUNCTION_CHANGED/UNSPECIFIED with no sibling local rename (here, retyping a local
		// variable) is not provably function-local -- UNSPECIFIED is overloaded across function-local
		// and caller-affecting edits -- so it must fall back to the conservative full cache flush,
		// dropping even functions unrelated to the edit. See DD-0009 addenda 2 and 3.
		//
		// The full flush is buffered through a SwingUpdateManager, so this asserts on cache contents
		// via waitForCondition (which waits out the async flush) rather than on post-navigation stats.
		createLocalVarOnFirstFunction();
		Function unrelated = functionAt(1); // not the edited function and not the current display

		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));
		waitForCondition(() -> isCached(functionAt(0)) && isCached(unrelated));

		retypeLocalVarOnFirstFunction();

		// full flush: a function unrelated to the edit is dropped, which a selective invalidation
		// (scoped to the edited function alone) would have kept cached
		waitForCondition(() -> !isCached(unrelated));
	}

	@Test
	public void testSharedDataTypeEditInvalidatesOnlyFunctionsReferencingItThroughAPointer()
			throws Exception {
		// An in-place shared-datatype edit (here, adding a field to MyStruct) must invalidate only
		// the functions that reference that type -- INCLUDING ones that reference it only through a
		// derived type such as MyStruct* -- while leaving unrelated functions cached. A pointer has a
		// fixed size, so editing the struct does not change the referencing function's signature; the
		// batch stays a pure DATA_TYPE_CHANGED that the selective path handles. Pinning the
		// derived-type unwrap (a struct's id differs from its pointer's id) is the whole point of this
		// test: without the unwrap the referencing function would wrongly stay cached. See DD-0009
		// addendum 4.
		Structure sharedStruct = createSharedStruct("MyStruct");
		setFirstFunctionReturnsPointerTo(sharedStruct);
		Function referencing = functionAt(0); // fun1: returns MyStruct*
		Function unrelated = functionAt(1); // fun2: references nothing shared

		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));
		waitForCondition(() -> isCached(referencing) && isCached(unrelated));

		addFieldToStruct(sharedStruct); // in-place DATA_TYPE_CHANGED on MyStruct

		// the function that uses MyStruct only via MyStruct* is dropped; unrelated stays cached
		waitForCondition(() -> !isCached(referencing) && isCached(unrelated));
	}

	@Test
	public void testDataTypeRenameInvalidatesOnlyFunctionsReferencingItThroughAPointer()
			throws Exception {
		// Renaming a shared type must invalidate only the functions that reference it -- INCLUDING via
		// a derived type such as MyStruct* -- while leaving unrelated functions cached. The decompiler
		// renders the type *name* into the cached output, so a rename leaves it stale until the
		// referencing functions re-decompile; DATA_TYPE_RENAMED carries the same program-managed
		// instance with the same DataTypeManager id, so it folds into the same recompute-from-cache
		// path as an in-place DATA_TYPE_CHANGED. See DD-0009 addendum 6.
		Structure sharedStruct = createSharedStruct("MyStruct");
		setFirstFunctionReturnsPointerTo(sharedStruct);
		Function referencing = functionAt(0); // fun1: returns MyStruct*
		Function unrelated = functionAt(1); // fun2: references nothing shared

		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));
		waitForCondition(() -> isCached(referencing) && isCached(unrelated));

		renameStruct(sharedStruct, "MyRenamedStruct"); // DATA_TYPE_RENAMED, same instance/id

		// the function that uses MyStruct only via MyStruct* is dropped; unrelated stays cached
		waitForCondition(() -> !isCached(referencing) && isCached(unrelated));
	}

	@Test
	public void testCacheIsClearedWhenProgramIsClosed() {
		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));
		assertCacheSize(3);

		ProgramManager pm = tool.getService(ProgramManager.class);
		pm.closeProgram(program, true);
		waitForSwing();
		assertCacheSize(0);
	}

	@Test
	public void testCacheIsClearedWhenOptionsChange() {
		goTo(functionAddrs.get(0));
		goTo(functionAddrs.get(1));
		goTo(functionAddrs.get(2));
		assertCacheSize(3);

		decompilerProvider.optionsChanged(new ToolOptions("Decompiler"), "Anything", null, null);
		assertCacheSize(0);
	}

	private void initializeTool() throws Exception {
		installPlugins();

		openProgram();
		ProgramManager pm = tool.getService(ProgramManager.class);
		pm.openProgram(program.getDomainFile());

		showTool(tool);
		showDecompilerProvider();
	}

	private void showDecompilerProvider() {
		ComponentProvider decompiler = tool.getComponentProvider("Decompiler");
		tool.showComponentProvider(decompiler, true);
		decompilerProvider = waitForComponentProvider(DecompilerProvider.class);
	}

	private void openProgram() throws Exception {

		builder = new ToyProgramBuilder("notepad", true);
		builder.createMemory(".text", "0x1004000", 0x1000);
		buildDummyFunction(builder, "fun1", "0x1004000");
		buildDummyFunction(builder, "fun2", "0x1004002");
		buildDummyFunction(builder, "fun3", "0x1004004");
		buildDummyFunction(builder, "fun4", "0x1004006");
		buildDummyFunction(builder, "fun5", "0x1004008");
		buildDummyFunction(builder, "fun6", "0x100400a");
		buildDummyFunction(builder, "fun7", "0x100400c");
		buildDummyFunction(builder, "fun8", "0x100400e");
		program = builder.getProgram();

		ProgramManager pm = tool.getService(ProgramManager.class);
		pm.openProgram(program.getDomainFile());
	}

	private void assertCacheSize(int expected) {
		Supplier<String> supplier = () -> getCacheSizeFailureMessage(expected);
		waitForCondition(() -> cache.size() == expected, supplier);
	}

	private String getCacheSizeFailureMessage(int expected) {
		StringBuilder buffy = new StringBuilder("Cache size is not as expected - expected " +
			expected + "; found " + cache.size() + "\nEntries in cache:\n");
		ConcurrentMap<Function, DecompileResults> map = cache.asMap();
		Set<Entry<Function, DecompileResults>> entries = map.entrySet();
		for (Entry<Function, DecompileResults> entry : entries) {
			Function key = entry.getKey();
			buffy.append('\t').append(key.getName()).append('\n');
		}
		return buffy.toString();
	}

	private void buildDummyFunction(ToyProgramBuilder programBuilder, String functionName,
			String address) throws MemoryAccessException {
		programBuilder.addBytesReturn(address);
		programBuilder.disassemble(address, 2, true);
		programBuilder.createFunction(address);
		programBuilder.createLabel(address, functionName);// function label
		functionAddrs.add(programBuilder.addr(address));

	}

	private void generateDomainObjectChange() {
		builder.createFunctionComment("0x1004000", "Hey There");
	}

	private Function firstFunction() {
		return functionAt(0);
	}

	private Function functionAt(int index) {
		return program.getFunctionManager().getFunctionAt(functionAddrs.get(index));
	}

	// asMap() does not record a hit/miss against the cache stats, so probing contents is side-effect
	// free and will not perturb the stat-based assertions in the sibling tests.
	private boolean isCached(Function function) {
		return cache.asMap().containsKey(function);
	}

	private void createLocalVarOnFirstFunction() throws Exception {
		builder.createLocalVariable(firstFunction(), "myLocal", new IntegerDataType(), -0x8);
	}

	private void renameLocalVarOnFirstFunction() throws Exception {
		Variable local = firstFunction().getLocalVariables()[0];
		builder.tx(() -> local.setName("renamedLocal", SourceType.USER_DEFINED));
	}

	private void markFirstFunctionNoReturn() throws Exception {
		Function function = firstFunction();
		builder.tx(() -> function.setNoReturn(true));
	}

	private void retypeLocalVarOnFirstFunction() throws Exception {
		Variable local = firstFunction().getLocalVariables()[0];
		builder.tx(() -> local.setDataType(new ByteDataType(), SourceType.USER_DEFINED));
	}

	private Structure createSharedStruct(String name) throws Exception {
		return builder.tx(() -> {
			StructureDataType proto = new StructureDataType(name, 0);
			proto.add(new IntegerDataType(), "a", null);
			return (Structure) program.getDataTypeManager()
					.resolve(proto, DataTypeConflictHandler.DEFAULT_HANDLER);
		});
	}

	private void setFirstFunctionReturnsPointerTo(DataType dt) throws Exception {
		builder.tx(() -> {
			DataType ptr = program.getDataTypeManager().getPointer(dt);
			firstFunction().setReturnType(ptr, SourceType.USER_DEFINED);
		});
	}

	private void addFieldToStruct(Structure struct) throws Exception {
		// Adding a field of a not-yet-present type (byte) is the realistic edit: it fires
		// DATA_TYPE_CHANGED on the struct alongside the benign DATA_TYPE_ADDED / SOURCE_ARCHIVE_CHANGED
		// companions the selective path must tolerate. See DD-0009 addendum 5.
		builder.tx(() -> struct.add(new ByteDataType(), "extra", null));
	}

	private void renameStruct(Structure struct, String newName) throws Exception {
		// A rename fires DATA_TYPE_RENAMED on the same struct instance, keeping its DataTypeManager id;
		// the selective path treats it like an in-place edit. See DD-0009 addendum 6.
		builder.tx(() -> struct.setName(newName));
	}

	private void makeSecondFunctionCallFirst() {
		// A call from inside fun2's body (0x1004002) to fun1's entry (0x1004000) makes fun2 a caller
		// of fun1 in the reference graph that Function.getCallingFunctions walks.
		builder.createMemoryCallReference("0x1004002", "0x1004000");
	}

	private void installPlugins() throws PluginException {
		tool.addPlugin(CodeBrowserPlugin.class.getName());
		tool.addPlugin(DecompilePlugin.class.getName());
		codeBrowser = env.getPlugin(CodeBrowserPlugin.class);
	}

	private void goTo(Address addr) {
		ProgramLocation location = new ProgramLocation(program, addr);
		assertTrue(codeBrowser.goTo(location, true));

		waitForSwing();
		waitForBusyDecompile();
	}

	private void waitForBusyDecompile() {
		DecompilerController controller =
			(DecompilerController) TestUtils.getInstanceField("controller", decompilerProvider);
		waitForCondition(() -> !controller.isDecompiling());
	}

}
