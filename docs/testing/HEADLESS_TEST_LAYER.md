# Headless Test Layer

*Addresses Rec 30 of the 2026-05-21 principal-architect audit.*

## The problem

`AbstractDecompilerTest` and its peers drive `JFrame` / `FieldPanel`
/ `ListingPanel` directly. The result:

- Tests need a display. On a headless CI container without a Java
  `Robot`-friendly virtual frame buffer, they hang or fail in
  ways unrelated to what they assert.
- Tests are slow. Every test boots an AWT event queue, paints
  components, and waits for layout passes that the assertions
  don't actually care about.
- Tests are flaky. Swing's event ordering is not deterministic
  under load; CI runners under load reorder events differently
  than developer machines.

The audit named this as the reason the integration suite is the
"slow" half of the run and as one of the reasons CI doesn't run
tests at all today (Rec 22).

## The decision

Introduce a **headless view layer**: a parallel set of interfaces
that the test code drives, with two implementations:

- **Swing impl** — the existing production behaviour, used by
  the real UI.
- **Headless impl** — a deterministic, in-memory record of what
  *would* have been drawn, used by tests.

The test code talks to the abstract interface. The decompiler,
the listing, the function-graph view, and the symbol-tree view
each get a headless analogue.

## Architecture

```
                ┌─────────────────────────────────┐
                │     Test code                   │
                │     (asserts against the view)  │
                └────────────────┬────────────────┘
                                 │
                                 ▼
        ┌────────────────────────────────────────────────┐
        │     DecompilerView (interface)                 │
        │       methods: getLines(), getSelection(),     │
        │       click(int x, int y), getCaret(), ...     │
        └─────────────┬─────────────────────┬─────────────┘
                      │                     │
                      ▼                     ▼
        ┌─────────────────────┐   ┌─────────────────────────┐
        │ SwingDecompilerView │   │ HeadlessDecompilerView  │
        │ (production)        │   │ (tests)                 │
        │ wraps DecompilerPanel│  │ in-memory line buffer + │
        │                     │   │ caret + selection state │
        └─────────────────────┘   └─────────────────────────┘
```

The same shape applies to the listing, the function-graph, and
the symbol tree. Each "view" interface is small (≤20 methods);
each headless impl is a simple POJO with the state the test
needs to assert against.

## Why interfaces, not test-only subclasses

A subclass-based approach (test extends real Swing class,
overrides a few methods) keeps Swing in the dependency graph and
recreates the headlessness problem at every new subclass. An
interface-based approach severs the dependency completely: the
headless impl has no AWT/Swing import in it at all.

## What changes for existing tests

The existing `AbstractDecompilerTest` and peers become **thin
adapters** over the new abstract view. The pattern:

```java
public abstract class AbstractDecompilerTest {
    protected DecompilerView decompilerView;

    @BeforeEach
    void setUp() {
        // Default to headless. Real-UI tests opt-in.
        this.decompilerView = createView();
    }

    protected DecompilerView createView() {
        return new HeadlessDecompilerView();
    }
}
```

A test that genuinely needs Swing (e.g., visual regression of a
custom renderer) overrides `createView()` to return
`new SwingDecompilerView(...)`. The default is headless. The
test author opts into Swing only for the cases where Swing's
behaviour is *the thing being asserted*.

## What gets headless impls (Stage 1)

- `DecompilerView` — text content, line attributes, caret,
  selection.
- `ListingView` — instructions, addresses, comments, labels.
- `FunctionGraphView` — vertices, edges, layout (only the
  graph shape, not its pixel coordinates).
- `SymbolTreeView` — tree state, expand/collapse, search.

Stage 2 adds:
- `DataTypeManagerView` — open archives, edits, undo stack.
- `MemoryMapView`.
- `BookmarkView`.

These cover the four most-tested UI surfaces. Other surfaces
get headless impls as the migration touches them.

## Performance expectation

A test that boots a `JFrame` + paints + asserts on Swing event
ordering today takes ~3–10 seconds. The same assertion against
a headless view runs in ~5–50 ms. The integration suite running
~1,500 tests at the current cost is the reason the suite is
slow; converting half of those tests to headless removes the
bulk of the time.

This is not a guess. Equivalent projects (IDEA's
`EditorTestFixture`, Eclipse JDT's headless test harness) report
≥10x speedups on the integration suite after a similar
introduction.

## Risks

- **Headless and Swing impls drift apart.** Mitigation: a small
  contract-test suite asserts both impls obey the same
  interface contract (e.g., "after click(x,y), getCaret()
  returns the expected position"). Run nightly.
- **Headless impl masks Swing bugs.** Mitigation: the existing
  Swing tests are not deleted; they continue to run but are
  moved off the PR-blocking critical path into a nightly visual-
  regression workflow.
- **Migration cost.** Mitigation: opportunistic, not mass.
  Existing tests stay JUnit 4 + Swing until someone has reason
  to touch them; new tests default to headless.

## Coordination with Rec 29 (JUnit 5)

JUnit 5's `@ExtendWith(...)` makes the headless view
provisioning much cleaner than JUnit 4's `@Rule` chains:

```java
@ExtendWith(GhidraHeadlessExtension.class)
class MyAnalyzerTest {
    @InjectHeadlessView DecompilerView decompiler;

    @Test
    void analyzerProducesExpectedDecompilation() {
        // decompiler is a HeadlessDecompilerView; assertions
        // run in milliseconds.
    }
}
```

The extension lives at `ghidra.test.junit.GhidraHeadlessExtension`;
its existence is contingent on Rec 29 landing first.

## Sequencing

| PR | Scope |
|---|---|
| #30-1 (this PR) | This design doc |
| #30-2 | `DecompilerView` interface + `HeadlessDecompilerView` impl + 3-test pilot |
| #30-3 | `ListingView` + `HeadlessListingView` |
| #30-4 | `FunctionGraphView` + `HeadlessFunctionGraphView` |
| #30-5 | `SymbolTreeView` + `HeadlessSymbolTreeView` |
| #30-6 | `GhidraHeadlessExtension` (depends on Rec 29) |
| #30-7+ | Stage 2 surfaces and opportunistic migrations |

## What this doesn't fix

- Tests that genuinely need to assert pixel-level rendering. They
  stay Swing-based. The point is to let the bulk of the suite
  not need pixels.
- The Swing layer itself. Eventually Ghidra may want a non-Swing
  UI (web, JavaFX, Compose); the headless layer is a step in
  that direction but not a commitment to it.

## Maintenance

- Contract-test suite is the most important piece. If it lags,
  the two impls drift.
- The headless impls are deliberately small. Resist the urge
  to grow them past what tests need.
- New UI features add their own view interface in the same PR;
  not "we'll headless-ify it later."
