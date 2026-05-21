# Mockito Adoption

*Addresses Rec 27 of the 2026-05-21 principal-architect audit.*

## The problem

The audit identified Ghidra's test setup as heavyweight to the
point of being a barrier: tests rely entirely on JUnit 4 +
Hamcrest with no mocking framework. The consequence is that
modules with hard dependencies (`Project`: 404 sources / 21 tests,
`SoftwareModeling`: 1631 sources / 128 tests) are under-tested
because spinning up real Swing components, real `Program`
instances, and real database contexts to test a small unit is
too painful.

A real `Program` test takes seconds. A mocked-dependency unit
test takes milliseconds. When the difference is a factor of 1000x,
you get the test coverage that matches the cost.

## Decision

Adopt **Mockito** (`org.mockito:mockito-core:5.12.0`) as the
project's mocking framework, added to the testImplementation
classpath of every Gradle subproject.

Mockito wins on:

- Industry standard for JVM mocking.
- Compatible with both JUnit 4 (current) and JUnit 5 (Rec 29).
- Apache 2.0 license.
- Static-method mocking via `mockito-inline` (available as a
  follow-up when Rec 29 lands JUnit 5).

## What does NOT happen in this PR

- **No mass migration of existing tests.** Migrating 152 existing
  `SoftwareModeling` tests to Mockito would be its own
  multi-month project. This PR adds the dependency; new tests
  can use it; existing tests are unaffected.
- **No removal of integration tests.** The heavyweight test path
  is still the right call for testing things that genuinely need
  Swing + Program + database state. Rec 30 (decouple Swing from
  integration tests) addresses *those* specifically; this rec is
  about the unit-test layer.
- **No global mocking patterns or fixtures.** A useful mocking
  utility class lives behind a real test that needs it.

## Style guide for new tests using Mockito

```java
import static org.mockito.Mockito.*;

import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class MyAnalyzerTest {
    @Mock private Program program;
    @Mock private Listing listing;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void analyzerReportsSymbolsWhenListingIsNonEmpty() {
        when(program.getListing()).thenReturn(listing);
        when(listing.getNumInstructions()).thenReturn(42L);

        MyAnalyzer subject = new MyAnalyzer();
        subject.analyze(program);

        verify(listing).getNumInstructions();
    }
}
```

Rules:

- **Mock dependencies, not the system under test.** If the test
  needs to mock the class it's testing, the design is wrong;
  fix the design.
- **No `mockStatic` in Stage 1.** Static mocking is powerful but
  encourages bad design; available later if there's a real need.
- **Prefer `@Mock` over `mock(...)`** for readability.
- **One `verify(...)` per concern.** A test that verifies five
  things is five tests pretending to be one.

## Coordination with JUnit 5 (Rec 29)

Mockito 5.x supports both JUnit 4 and JUnit 5. When Rec 29
lands, the `mockito-junit-jupiter` extension already on the
classpath provides JUnit 5 integration (`@ExtendWith(MockitoExtension.class)`).

No additional dependency change needed for the JUnit 5 cutover.

## Coordination with Rec 30 (decouple Swing from tests)

Rec 30 creates a headless view layer for integration tests. Mock
objects via Mockito are the unit-test side of the same problem:
the integration tests get a headless view, the unit tests get
mocks. The two together cover the test-pain surface the audit
called out.

## Maintenance

- Pin Mockito version (`5.12.0`) in `gradle/javaProject.gradle`.
- Bump in its own PR.
- New tests using Mockito do not need a separate `apply` — the
  dependency is already on every subproject's testImplementation
  classpath.
