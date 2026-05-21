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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;

import org.junit.Test;

/**
 * Tests for {@link SafeObjectInput}.
 */
public class SafeObjectInputTest {

    /** Serializable payload used by several tests. */
    public static final class Allowed implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String value;
        public Allowed(String value) {
            this.value = value;
        }
    }

    /** A different serializable type the tests will use to assert
     * the top-level-type guard. */
    public static final class Other implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    @Test
    public void roundTripsAllowlistedType() throws Exception {
        Allowed input = new Allowed("hello");
        byte[] bytes = serialize(input);
        Allowed output = SafeObjectInput.readObject(
            new ByteArrayInputStream(bytes), Allowed.class,
            SafeObjectInput.allowlist(Allowed.class));
        assertEquals("hello", output.value);
    }

    @Test
    public void rejectsTypeOutsideAllowlist() throws Exception {
        byte[] bytes = serialize(new Allowed("hello"));
        try {
            SafeObjectInput.readObject(new ByteArrayInputStream(bytes),
                Allowed.class,
                SafeObjectInput.allowlist(Other.class));  // wrong class allowed
            fail("expected rejection of non-allowlisted class");
        }
        catch (IOException e) {
            // ObjectInputFilter REJECT manifests as InvalidClassException -> IOException
        }
    }

    @Test
    public void rejectsWrongTopLevelType() throws Exception {
        byte[] bytes = serialize(new Allowed("hello"));
        try {
            // Allow the class, but ask for a different expected type.
            SafeObjectInput.readObject(new ByteArrayInputStream(bytes),
                Other.class,
                SafeObjectInput.allowlist(Allowed.class));
            fail("expected top-level-type mismatch");
        }
        catch (IOException e) {
            // expected
        }
    }

    @Test
    public void rejectsByteBudgetExceeded() throws Exception {
        byte[] bytes = serialize(new Allowed("hello"));
        try {
            SafeObjectInput.readObject(new ByteArrayInputStream(bytes),
                Allowed.class,
                SafeObjectInput.allowlist(Allowed.class),
                SafeObjectInput.DEFAULT_DEPTH_LIMIT,
                /* byteLimit */ 1L);  // unreasonably tiny — must reject
            fail("expected byte-budget rejection");
        }
        catch (IOException e) {
            // expected
        }
    }

    @Test
    public void rejectsClassFilterNull() {
        try {
            SafeObjectInput.readObject(new ByteArrayInputStream(new byte[0]),
                Allowed.class, null);
            fail("expected IllegalArgumentException for null filter");
        }
        catch (IllegalArgumentException e) {
            // expected
        }
        catch (Exception e) {
            fail("expected IllegalArgumentException but got " + e);
        }
    }

    @Test
    public void rejectsExpectedTypeNull() {
        try {
            SafeObjectInput.readObject(new ByteArrayInputStream(new byte[0]),
                null, SafeObjectInput.allowlist(Allowed.class));
            fail("expected IllegalArgumentException for null expected type");
        }
        catch (IllegalArgumentException e) {
            // expected
        }
        catch (Exception e) {
            fail("expected IllegalArgumentException but got " + e);
        }
    }

    @Test
    public void readsNullCorrectly() throws Exception {
        // A serialized `null` is a single TC_NULL byte (followed by
        // the streamHeader). SafeObjectInput should pass through null.
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(null);
            oos.flush();
            bytes = baos.toByteArray();
        }
        Allowed result = SafeObjectInput.readObject(
            new ByteArrayInputStream(bytes), Allowed.class,
            SafeObjectInput.allowlist(Allowed.class));
        assertNull(result);
    }

    @Test
    public void rejectsCommonGadgetClassNotInAllowlist() throws Exception {
        // ArrayList isn't in the default allowlist; even a benign
        // ArrayList payload must be rejected when not explicitly
        // allowed. (Real-world gadget chains start from a deserial
        // entry point that's unsuspecting like this.)
        ArrayList<String> input = new ArrayList<>();
        input.add("x");
        byte[] bytes = serialize(input);
        try {
            SafeObjectInput.readObject(new ByteArrayInputStream(bytes),
                Object.class,
                SafeObjectInput.allowlist(/* nothing — strict */));
            fail("expected rejection of non-allowlisted ArrayList");
        }
        catch (IOException e) {
            // expected
        }
    }

    private static byte[] serialize(Object obj) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
            oos.flush();
            return baos.toByteArray();
        }
    }
}
