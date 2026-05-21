# JUnit 5 Migration Plan

*Addresses Rec 29 of the 2026-05-21 principal-architect audit.*

## Why move

JUnit 4.13.2 is the current test framework. It is fine, it is
mature, and it works. JUnit 5 ("Jupiter") is also mature, has
been GA since 2017, and unlocks features that the integration
suite specifically needs:

- **Parameterized tests** without the `@RunWith(Parameterized.class)`
  ceremony. Useful for Sleigh assembler tests, ELF/PE/Mach-O
  loader fixtures, and decompiler XML datatest fixtures (every
  fixture file becomes a parameter, run in one JVM).
- **Parallel test execution** via `junit-platform.properties`.
  The audit identified the integration suite as slow; JUnit 5's
  parallel runner (with explicit `@Execution(CONCURRENT)`)
  meaningfully accelerates suites whose tests don't share state.
- **Conditional execution** (`@EnabledOnOs`, `@DisabledIfSystemProperty`).
  Cleaner than the current `Assume.assumeTrue(...)` calls.
- **Better extension model.** `@ExtendWith(...)` replaces the
  TestRule + RunWith mess; Mockito's `MockitoExtension`
  (Rec 27) is one cleaner.

## What does NOT move

- **JUnit 4 tests are not deleted.** Both runners coexist on the
  classpath via `junit-vintage-engine` (part of JUnit 5's
  platform). Existing tests continue to run unchanged.
- **No mass migration.** Translating 1,000+ JUnit 4 tests to
  JUnit 5 in one PR would be a months-long churn with no test-
  semantics improvement. The migration is *opportunistic*: new
  tests are JUnit 5; existing tests stay JUnit 4 until someone
  has reason to touch them, at which point they migrate as a
  side-effect.

## Sequencing

| PR | Scope |
|---|---|
| #29-1 (this PR) | This migration plan |
| #29-2 | Add JUnit 5 platform + vintage to `gradle/javaProject.gradle`; configure `useJUnitPlatform()` in `gradle/javaTestProject.gradle` |
| #29-3 | Pilot migration: one small subproject (`SoftwareModeling/util/`) fully translated, as a worked example |
| #29-4 | Migrate the audit's `@Ignore` debt sweep (Rec 28) to JUnit 5 in the same PRs that fix the tests |
| #29-5+ | Opportunistic: any PR that touches a JUnit 4 test for other reasons converts it |

## Style for new tests (JUnit 5)

```java
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MyAnalyzerTest {
    @Mock private Program program;

    @Test
    void analyzesEmptyProgramReturnsNoFindings() {
        MyAnalyzer subject = new MyAnalyzer();
        assertEquals(0, subject.analyze(program).size());
    }

    @ParameterizedTest
    @ValueSource(strings = { "elf", "pe", "macho" })
    void supportsAllMajorFormats(String format) {
        assertTrue(MyAnalyzer.supports(format));
    }
}
```

Notable:
- Imports are `org.junit.jupiter.api.*`, not `org.junit.*`.
- Test classes/methods can be package-private (`class`, not
  `public class`).
- `@BeforeEach` / `@AfterEach` replace `@Before` / `@After`.
- Assertions are in `org.junit.jupiter.api.Assertions` (or use
  Hamcrest as before; Mockito hookup uses `MockitoExtension`).

## Parallelism opt-in

Per the JUnit 5 docs, parallel execution is opt-in. Place this
in `junit-platform.properties` for the subproject:

```
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
```

The default is per-test parallelism with the
`@Execution(SAME_THREAD)` annotation available to opt back into
serial execution for tests that share state (e.g., anything
touching the `Application` singleton). For Ghidra's heavy
integration suite, the right policy is `concurrent` per class
but `same-thread` per method within a class — that's the JUnit
default for parallelism and matches how the existing tests
were written.

## Gradle wiring

In `gradle/javaTestProject.gradle`:

```groovy
dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.vintage:junit-vintage-engine:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.2'
}

test {
    useJUnitPlatform()
}
```

`junit-vintage-engine` runs the existing JUnit 4 tests through
the JUnit 5 platform. Both run side-by-side.

## What's *not* in scope

- **Bytecode-level test rewrite tools.** OpenRewrite has recipes
  for JUnit 4 → 5 mechanical conversions; whether to apply them
  is its own decision, after the pilot migration shows what
  manual quality looks like.
- **Test categorization rewrite.** JUnit 4 `@Category(...)` and
  JUnit 5 `@Tag(...)` are not equivalent in semantics; the
  per-subproject test filtering currently relying on `@Category`
  needs explicit migration design (separate sub-issue).
- **AssertJ adoption.** Hamcrest is fine and stays. AssertJ is
  a popular JUnit 5 companion but its addition is a separate
  decision.

## Coordination with Rec 27 (Mockito) and Rec 30 (Swing decoupling)

JUnit 5 unlocks two cleaner integrations:

- **Mockito (Rec 27):** `@ExtendWith(MockitoExtension.class)`
  replaces `MockitoAnnotations.openMocks(this)` in `@BeforeEach`.
- **Headless test layer (Rec 30):** JUnit 5's
  `@ExtendWith(...)` allows a `GhidraHeadlessExtension` to
  provision the headless `Program` cleanly without inheritance
  ceremony.

Both are easier in JUnit 5 than in JUnit 4. The migration
unblocks Rec 30's design space.

## Maintenance

- Pin the JUnit 5 version (`5.10.2`) and platform-launcher
  version (`1.10.2`) in the gradle script.
- Vintage engine stays for the indefinite future; we are not
  going to delete JUnit 4 tests *en masse*.
- New tests should default to JUnit 5; PRs adding JUnit 4
  tests should explain why.
