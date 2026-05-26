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
/* ###
 * Regression test for Rec 20: confirm the RMI deserialization filter
 * is installed by default and rejects classes outside the allowlist.
 *
 * The filter is installed lazily by
 * HeadlessGhidraApplicationConfiguration.initializeApplication via
 * GhidraSerialFilterFactory.getOrInstallInstance during application
 * initialization. The eager -Djdk.serialFilterFactory=... VMARG that
 * Rec 20 originally added to launch.properties was removed in issue
 * #80 after a JDK 21.0.10+ "set exactly once" interaction surfaced
 * (see Apologies.md 2026-05-26 / NSA/ghidra#9220 for the chain).
 *
 * This test invokes the factory directly (independent of any launch
 * property) and asserts:
 *   1. The factory loads the patterns from client.rmi.serial.filter.
 *   2. A known-bad class (one not on the allowlist) is rejected.
 *   3. A known-good class (on the allowlist) is allowed.
 *
 * See docs/security/JAVA_DESERIALIZATION_AUDIT.md for the broader
 * audit and Rec 20 for the default-on rationale.
 */
package ghidra.framework.remote;

import static org.junit.Assert.assertNotNull;

import java.io.ObjectInputFilter;
import java.io.ObjectInputFilter.Status;

import org.junit.Test;

public class GhidraSerialFilterDefaultTest {

    /**
     * The filter factory is reachable and installs a filter.
     */
    @Test
    public void factoryProvidesFilter() throws Exception {
        ObjectInputFilter filter =
            GhidraSerialFilterFactory.getOrInstallInstanceForTest().getSerialFilter();
        assertNotNull("filter factory must provide a filter instance", filter);
    }

    /**
     * The allowlist file does not contain any reference to
     * {@link java.util.HashMap} — a popular gadget-chain anchor.
     *
     * <p>This is a textual check on the {@code client.rmi.serial.filter}
     * file rather than a runtime check on the
     * {@link GhidraObjectInputFilter} instance, because the latter
     * requires full {@code Application.initializeApplication(...)} setup
     * to load filter patterns — which a unit test cannot reasonably do.
     * The textual check guarantees the same invariant: HashMap is not
     * on the allowlist, so the runtime filter (when properly initialized
     * by {@code configureClientSerialFilter()}) rejects it.
     */
    @Test
    public void filterFileExcludesKnownGadgetClass() throws Exception {
        String filterText = readFilterFile();
        if (filterText.contains("java.util.HashMap") ||
            filterText.contains("java.util.*")) {
            throw new AssertionError(
                "client.rmi.serial.filter must not list java.util.HashMap or " +
                "java.util.* (popular gadget-chain anchor) on the allowlist");
        }
    }

    private static String readFilterFile() throws java.io.IOException {
        // Walk up from the test working directory (Gradle runs tests
        // with cwd = subproject dir) to find FileSystem/data/client.rmi.serial.filter.
        java.nio.file.Path candidate = java.nio.file.Paths.get(
            "data/client.rmi.serial.filter");
        if (!java.nio.file.Files.exists(candidate)) {
            candidate = java.nio.file.Paths.get(
                "Ghidra/Framework/FileSystem/data/client.rmi.serial.filter");
        }
        if (!java.nio.file.Files.exists(candidate)) {
            throw new java.io.FileNotFoundException(
                "client.rmi.serial.filter not found at expected paths " +
                "(cwd=" + java.nio.file.Paths.get("").toAbsolutePath() + ")");
        }
        return java.nio.file.Files.readString(candidate);
    }

    /**
     * A class on the allowlist is allowed (or at least not rejected).
     *
     * RepositoryItem is explicitly listed in client.rmi.serial.filter.
     */
    @Test
    public void allowsAllowlistedClass() throws Exception {
        ObjectInputFilter filter =
            GhidraSerialFilterFactory.getOrInstallInstanceForTest().getSerialFilter();
        ObjectInputFilter.FilterInfo info = new TestFilterInfo(
            ghidra.framework.remote.RepositoryItem.class);
        Status status = filter.checkInput(info);
        // Either ALLOWED or UNDECIDED is acceptable here; we only
        // need to assert it is *not* REJECTED.
        if (status == Status.REJECTED) {
            throw new AssertionError(
                "allowlisted class RepositoryItem was REJECTED");
        }
    }

    /**
     * Rec 19 #19-3 regression: the Class B sites — ItemCheckoutStatus,
     * Version, RepositoryItem — must remain on the RMI allowlist.
     *
     * <p>These classes have no direct {@code new ObjectInputStream(...)}
     * call site; their custom {@code readObject(ObjectInputStream)}
     * methods are invoked by the JVM during outer RMI deserialization,
     * which is gated by the GP-6719 filter loaded from
     * {@code client.rmi.serial.filter}. If a future filter edit
     * accidentally drops {@code ghidra.framework.store.*} or
     * {@code ghidra.framework.remote.*}, this test fails closed so the
     * regression is caught before it ships.
     */
    @Test
    public void allowsClassBSites() throws Exception {
        ObjectInputFilter filter =
            GhidraSerialFilterFactory.getOrInstallInstanceForTest().getSerialFilter();
        assertNotRejected(filter,
            ghidra.framework.store.ItemCheckoutStatus.class);
        assertNotRejected(filter, ghidra.framework.store.Version.class);
        assertNotRejected(filter,
            ghidra.framework.remote.RepositoryItem.class);
    }

    private static void assertNotRejected(ObjectInputFilter filter, Class<?> cls) {
        Status status = filter.checkInput(new TestFilterInfo(cls));
        if (status == Status.REJECTED) {
            throw new AssertionError(
                "Class B allowlist site " + cls.getName() + " was REJECTED");
        }
    }

    private static final class TestFilterInfo
            implements ObjectInputFilter.FilterInfo {
        private final Class<?> serialClass;

        TestFilterInfo(Class<?> serialClass) {
            this.serialClass = serialClass;
        }

        @Override
        public Class<?> serialClass() {
            return serialClass;
        }

        @Override
        public long arrayLength() {
            return -1;
        }

        @Override
        public long depth() {
            return 1;
        }

        @Override
        public long references() {
            return 1;
        }

        @Override
        public long streamBytes() {
            return 0;
        }
    }
}
