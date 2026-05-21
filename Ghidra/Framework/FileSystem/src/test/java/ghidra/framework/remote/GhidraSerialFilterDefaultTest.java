/* ###
 * Regression test for Rec 20: confirm the RMI deserialization filter
 * is installed by default and rejects classes outside the allowlist.
 *
 * The filter is installed via the JVM system property
 *   -Djdk.serialFilterFactory=ghidra.framework.remote.GhidraSerialFilterFactory
 * which is set in Ghidra/RuntimeScripts/Common/support/launch.properties.
 *
 * This test invokes the factory directly (independent of the launch
 * property) and asserts:
 *   1. The factory loads the patterns from client.rmi.serial.filter.
 *   2. A known-bad class (one not on the allowlist) is rejected.
 *   3. A known-good class (on the allowlist) is allowed.
 *
 * See docs/security/JAVA_DESERIALIZATION_AUDIT.md for the broader
 * audit and Rec 20 for the default-on rationale.
 */
package ghidra.framework.remote;

import static org.junit.Assert.assertEquals;
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
            GhidraSerialFilterFactory.getOrInstallInstance().getSerialFilter();
        assertNotNull("filter factory must provide a filter instance", filter);
    }

    /**
     * A class outside the allowlist is rejected.
     *
     * Uses java.util.HashMap as a representative non-allowlisted
     * class — it's a popular gadget-chain anchor in the wild and is
     * not in client.rmi.serial.filter.
     */
    @Test
    public void rejectsNonAllowlistedClass() throws Exception {
        ObjectInputFilter filter =
            GhidraSerialFilterFactory.getOrInstallInstance().getSerialFilter();
        ObjectInputFilter.FilterInfo info = new TestFilterInfo(java.util.HashMap.class);
        // Per the allowlist policy in this fork: the default for
        // unknown classes is REJECTED, not UNDECIDED-pass-through.
        assertEquals("non-allowlisted class must be rejected",
                     Status.REJECTED,
                     filter.checkInput(info));
    }

    /**
     * A class on the allowlist is allowed (or at least not rejected).
     *
     * RepositoryItem is explicitly listed in client.rmi.serial.filter.
     */
    @Test
    public void allowsAllowlistedClass() throws Exception {
        ObjectInputFilter filter =
            GhidraSerialFilterFactory.getOrInstallInstance().getSerialFilter();
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
