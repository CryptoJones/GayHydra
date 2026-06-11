# C++ Decompiler Hints — user guide

GayHydra recovers C++ idioms that the stock decompiler output leaves as raw
pointer arithmetic and opaque calls, and surfaces each as a `C++:` comment
at its site — in both the Listing and the Decompiler view.

## What you get

Seven idiom forms, each rendered as the C++ expression the original source
plausibly contained:

| Idiom | Example hint |
|---|---|
| Virtual call | `C++: param_1->draw(5)` |
| Heap construction | `C++: new C(arg)` |
| Array construction | `C++: new C[5]` |
| Placement construction | `C++: new (buf) C()` |
| `delete` | `C++: delete param_1` / `delete[] param_1` |
| Explicit destructor call | `C++: param_1->~C()` |
| Base up/down-cast | `C++: static_cast<Base*>(d)` |

Every hint is **advisory and never-wrong by policy**: a form renders only
when the recovered evidence is unambiguous (typed RTTI/vtable data, named
callees, renderable arguments). Anything uncertain renders nothing rather
than something misleading. An empty result on a C binary — or on a binary
whose type system could not be fed — is normal, not a failure.

## How to use it

1. **Import and auto-analyze** your binary as usual. For Visual Studio /
   Clang PE binaries, the C++ Type System analyzers (`CppRttiAnalyzer`,
   `CppVTableAnalyzer`, default-on) feed a per-program type system from the
   binary's RTTI and vftables during analysis — no action needed.
2. **Run the script**: Window → Script Manager → category **C++** →
   `RecoverCppHintsScript.java`. It decompiles each function, collects the
   recognizable idioms, and writes the `C++:` PRE comments. Re-running
   never duplicates or clobbers a comment.
3. Headless:

   ```bash
   support/analyzeHeadless <project-dir> <project> \
     -import target.exe \
     -postScript RecoverCppHintsScript.java
   ```

To count rather than annotate (e.g. for corpus/recall work), use
`CountCppHintRecallScript.java` — same category.

## Current coverage honestly stated

- The type-system **feeders run automatically for MSVC/Clang PE binaries**.
  ELF/Mach-O binaries currently get no automatic RTTI feed, so the
  type-resolving forms decline there (the `delete` form, which needs only
  callee names, still fires). The measured per-form numbers live in
  [`samples/hint-recall-corpus/baseline.json`](../samples/hint-recall-corpus/baseline.json);
  widening coverage (Itanium/ELF analyzer leg) is tracked sprint work.
- Recognition idioms are grounded against x86-64 decompiler output;
  other architectures may recall less. Same corpus tracks it.
- Hints are comments, deliberately: they survive re-decompilation, show in
  both views, and never alter the decompiler's own output.

## For developers

Pipeline design and per-slice history: Rec 37 in
[`SprintPlanning.md`](../SprintPlanning.md) / DD-0011..DD-0073. Model code
in `ghidra.app.util.cpp` (Base); recognition tests in the Decompiler
module's `test.slow`.
