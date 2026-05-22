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
package ghidra.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputFilter.FilterInfo;
import java.io.ObjectInputFilter.Status;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The sanctioned entry point for Java object deserialization.
 *
 * <p>Every direct {@code new ObjectInputStream(...).readObject()}
 * call in the codebase is a potential gadget-chain attack surface.
 * Rec 19 of the 2026-05-21 audit (see
 * {@code docs/security/JAVA_DESERIALIZATION_AUDIT.md}) declares
 * that this class is the only sanctioned helper; new code must not
 * touch {@link ObjectInputStream} directly.
 *
 * <p>Rules enforced on every read:
 * <ul>
 *   <li>The caller declares the expected top-level type.</li>
 *   <li>The caller passes an explicit {@link ObjectInputFilter}
 *       allowlist of classes that may appear anywhere in the
 *       object graph. Classes not on the list are
 *       {@link Status#REJECTED}.</li>
 *   <li>Graph depth is capped at {@value #DEFAULT_DEPTH_LIMIT}.</li>
 *   <li>Stream bytes are capped at {@value #DEFAULT_BYTE_LIMIT}.</li>
 *   <li>Mismatched top-level type causes a hard failure with no
 *       partial object materialised.</li>
 * </ul>
 *
 * <p>This helper does not protect against poorly-chosen
 * allowlists — if the caller allows {@code java.util.HashMap}, the
 * standard CommonsCollections-style gadget chain is reachable.
 * The allowlist is the security boundary; this class enforces
 * the discipline of having one.
 *
 * @see <a href="../../../../../../../../../docs/security/JAVA_DESERIALIZATION_AUDIT.md">JAVA_DESERIALIZATION_AUDIT.md</a>
 */
public final class SafeObjectInput {

    /** Default maximum object-graph depth. */
    public static final int DEFAULT_DEPTH_LIMIT = 50;

    /** Default maximum stream bytes per deserialization call (64 MiB). */
    public static final long DEFAULT_BYTE_LIMIT = 64L * 1024 * 1024;

    private SafeObjectInput() {
        // not instantiable
    }

    /**
     * Read a single object from {@code in}, asserting the
     * top-level type is {@code expected} and the per-class
     * filter accepts every class touched.
     *
     * <p>Uses default depth and byte caps.
     */
    public static <T> T readObject(InputStream in, Class<T> expected,
            ObjectInputFilter classFilter) throws IOException, ClassNotFoundException {
        return readObject(in, expected, classFilter, DEFAULT_DEPTH_LIMIT, DEFAULT_BYTE_LIMIT);
    }

    /**
     * Read a single object with explicit depth and byte caps.
     */
    public static <T> T readObject(InputStream in, Class<T> expected,
            ObjectInputFilter classFilter, int depthLimit, long byteLimit)
            throws IOException, ClassNotFoundException {
        if (expected == null) {
            throw new IllegalArgumentException("expected type must not be null");
        }
        if (classFilter == null) {
            throw new IllegalArgumentException(
                "classFilter must not be null — default-reject allowlist is mandatory");
        }
        if (depthLimit <= 0 || byteLimit <= 0) {
            throw new IllegalArgumentException("depthLimit and byteLimit must be positive");
        }

        ObjectInputFilter combined = composeFilter(classFilter, depthLimit, byteLimit);
        try (ObjectInputStream ois = new ObjectInputStream(in)) {
            ois.setObjectInputFilter(combined);
            Object obj = ois.readObject();
            if (obj == null) {
                return null;
            }
            if (!expected.isInstance(obj)) {
                throw new IOException("expected top-level type " + expected.getName()
                    + " but read " + obj.getClass().getName());
            }
            return expected.cast(obj);
        }
    }

    /**
     * Open an {@link ObjectInputStream} on {@code in} with a
     * default-reject class filter installed. Intended for call
     * sites that read only primitives ({@code readLong},
     * {@code readInt}, {@code readUTF}, etc.) out of a header,
     * never {@code readObject()}.
     *
     * <p>If a future edit adds a {@code readObject()} call to one
     * of those sites, the filter will reject the class lookup and
     * the call will fail closed — preventing a silent widening of
     * the deserialization attack surface.
     *
     * <p>The returned stream is owned by the caller and must be
     * closed (typically via try-with-resources).
     */
    public static ObjectInputStream headerStream(InputStream in) throws IOException {
        ObjectInputStream ois = new ObjectInputStream(in);
        ois.setObjectInputFilter(info -> info.serialClass() == null
                ? Status.UNDECIDED
                : Status.REJECTED);
        return ois;
    }

    /**
     * Build an {@link ObjectInputFilter} that accepts exactly the
     * supplied classes (plus primitive-wrapper and {@code String}
     * which JVM serialization needs unconditionally). Anything
     * else is {@link Status#REJECTED}.
     */
    public static ObjectInputFilter allowlist(Class<?>... allowedClasses) {
        Set<Class<?>> set = new HashSet<>(Arrays.asList(allowedClasses));
        // JVM-internal types that always appear in any object graph.
        // We do NOT add collection types here; callers must declare
        // them explicitly if they expect them.
        set.add(String.class);
        set.add(Integer.class);
        set.add(Long.class);
        set.add(Short.class);
        set.add(Byte.class);
        set.add(Boolean.class);
        set.add(Character.class);
        set.add(Double.class);
        set.add(Float.class);
        Set<Class<?>> frozen = Collections.unmodifiableSet(set);
        return info -> {
            Class<?> cls = info.serialClass();
            if (cls == null) {
                // arrays / non-class types — fall through to allow;
                // the class-of-element check happens on a separate call.
                return Status.UNDECIDED;
            }
            if (frozen.contains(cls) || cls.isArray() && frozen.contains(cls.getComponentType())) {
                return Status.ALLOWED;
            }
            return Status.REJECTED;
        };
    }

    /**
     * Combine the caller's class allowlist with depth + byte limits.
     * Any one of {@code REJECTED}, depth-exceeded, or bytes-exceeded
     * stops deserialization.
     */
    private static ObjectInputFilter composeFilter(ObjectInputFilter classFilter,
            int depthLimit, long byteLimit) {
        return (FilterInfo info) -> {
            if (info.depth() > depthLimit) {
                return Status.REJECTED;
            }
            if (info.streamBytes() > byteLimit) {
                return Status.REJECTED;
            }
            // Delegate to the caller's class allowlist for the
            // class check; treat their UNDECIDED as REJECTED so the
            // default is closed.
            Status delegated = classFilter.checkInput(info);
            if (delegated == Status.UNDECIDED) {
                // Allow if the FilterInfo isn't about a class
                // (info.serialClass() is null for non-class checks).
                if (info.serialClass() == null) {
                    return Status.UNDECIDED;
                }
                return Status.REJECTED;
            }
            return delegated;
        };
    }
}
