---
number: 0001
title: First-class C++ analysis frontend
status: draft
author: @CryptoJones
created: 2026-05-21
audit_rec: 37
---

# RFC 0001: First-class C++ analysis frontend

## Summary

Ghidra's current treatment of C++ binaries is a collection of point
tools (vtable recovery hints, name demangling, struct inference) that
do not compose into a coherent "this is the C++ object model"
understanding. IDA / Hex-Rays has a coherent C++ analysis layer and
the community sees the gap (upstream #516 with 49 👍, #992, related
issues). This RFC proposes a coordinated C++ frontend: a single
subsystem that owns the C++-specific analysis story.

## Motivation

The current state, concretely:

- **Vtable recovery is heuristic and uncomposable.** The user gets a
  table of function pointers; figuring out which class it belongs to
  is manual.
- **Name demangling exists** (Itanium and MSVC mangling supported)
  **but does not feed the type system.** A demangled name like
  `Foo::bar(int) const` does not produce a `Foo` type, a `bar`
  member function on it, a `const` qualifier, or a `this` parameter.
- **RTTI is parsed but not surfaced as analysis output.** The
  `class_id` records sit in symbols; the decompiler does not use
  them to constrain types in calling functions.
- **Inheritance is invisible.** A function that takes a `Derived *`
  and passes it to a function expecting `Base *` is not analysed as
  upcast; the decompiler shows raw pointer math.

The community has been asking for first-class C++ for years. The
audit identified this as the "strategic, not tactical" C++ gap.

## Detailed design

The proposed frontend is structured as five cooperating components.

### 1. `CppTypeSystem` — the type-aware overlay

A separate type-system layer sits over the existing
`DataTypeManager`. It owns:

- **`CppClass`** — class types with inheritance edges, member
  layout (including padding/alignment), and a list of
  `CppMethod`s.
- **`CppMethod`** — name + signature + qualifiers (const,
  volatile, ref-qualifier, virtual override status, pure-virtual
  status).
- **`CppVTable`** — vtable shape: list of `(slot, CppMethod)`,
  RTTI pointer slot if applicable.
- **`CppCallingConvention`** — argument passing rules including
  `this` pointer placement (calling-convention-dependent).

The C++ type system is a *projection* of the underlying type
system, not a replacement. A `CppClass` is implemented as a
`Structure` in the underlying type system with extra
annotations. This means existing tools that read structures
continue to work; C++-aware tools see the richer view.

### 2. `CppDemanglingFeeder` — demangled-name → type-system pipeline

Reads demangled names from the symbol table and fills the
`CppTypeSystem` with class declarations and method signatures.
This is a one-way feed: the demangler is the source of truth
for what classes exist; the user can override but the auto-fill
is the baseline.

Itanium ABI and MSVC ABI demangling are already implemented in
Ghidra; the new code is the *consumer* of their output, not a
new parser.

### 3. `CppRttiAnalyzer` — RTTI → class hierarchy

Walks RTTI records (Itanium `__class_type_info` /
`__si_class_type_info` / `__vmi_class_type_info`; MSVC
`RTTITypeDescriptor` / `RTTIClassHierarchyDescriptor`) and adds
inheritance edges to `CppTypeSystem`.

This produces the inheritance graph the decompiler needs to
recognise upcasts and downcasts.

### 4. `CppVTableAnalyzer` — vtable → CppMethod table

The current vtable recovery output (a list of function pointers
at a known address) is the input; the output is a
`CppVTable` with each slot mapped to a `CppMethod`, using:

- Demangled names of the pointed-to functions (Stage 2 of
  demangling-feeder above).
- RTTI descriptor (which class this vtable belongs to).
- Heuristic matching when neither is present.

### 5. `CppDecompilerHints` — analysis-aware pseudocode

The decompiler's pseudocode output today is C-style. The C++
frontend produces C++-style hints that the decompiler can
choose to render:

- `obj->method(args)` instead of `(*(*obj))[3](obj, args)`.
- `static_cast<Base*>(d)` instead of `d + offsetof(Derived, base)`.
- `new Foo(args)` instead of `malloc(...) + Foo_ctor(...)`.

These are *hints*, not enforcement: the user can fall back to
the raw view at any time. The hints are produced from the
`CppTypeSystem` state by a new analysis pass between the
existing type-inference pass and the output-emission pass.

## Drawbacks

- **Scope.** This is a months-to-years undertaking. The RFC is
  written; the implementation is a sequence of sub-PRs, each
  reviewable independently.
- **Maintenance surface.** The C++ ABI is a moving target across
  toolchains; supporting Itanium + MSVC is just the start. We
  commit only to those two.
- **Risk of forking the decompiler.** The C++ frontend is an
  *overlay*, not a fork. If it grows into a separate
  analysis pipeline that diverges from the main one, we have
  introduced two ways to do the same thing. The RFC explicitly
  forbids this: the C++ frontend never replaces a non-C++ analysis
  pass; it only adds annotations the existing passes consume.

## Alternatives

- **Do nothing.** Status quo. The community keeps asking; the
  gap to IDA grows; users with C++ targets reach for other
  tools.
- **Adopt an external project (LLVM-objdump, demangle-explorer,
  hyrise/cpp-demangle).** None offers a coherent C++ analysis
  layer; they offer pieces. We would still have to write the
  composition.
- **Write a thin C++ analysis script (Python).** Doesn't scale
  to large binaries; not user-friendly; not maintained as part
  of core. Tested as a stop-gap; abandoned because of the
  scaling cliff.

## Migration

The frontend is **additive**. Users who don't enable C++
analysis see the current behaviour. Users who do enable it see
richer output. There is no migration of existing project files;
existing `.gpr` files continue to load with whatever inheritance
data they already have.

A "Detect C++" auto-analyzer runs by default for any binary that
demangles a sufficient density of C++ names (>5% of named
functions). The threshold is configurable.

## Unresolved questions

1. **MSVC RTTI is encrypted/obfuscated in some packed binaries.**
   How aggressive should the frontend be at falling back to
   demangling-only when RTTI is unreadable?
2. **Templates.** Demangled template names like
   `std::vector<int>::push_back` should produce a `vector<int>`
   `CppClass`. How aggressively do we materialise template
   instances?
3. **Operator overloading.** `operator+` etc. in the decompiler
   pseudocode is more readable than the mangled symbol but
   requires the type system to know about operators.

## Future possibilities

- **C++ patterns in Sleigh.** Sleigh today knows nothing about
  C++ ABI; the C++ frontend produces hints downstream. A future
  version could push some C++ knowledge into Sleigh
  (calling-convention-aware lifting).
- **Rust front-end.** Many of the C++ pipeline's components
  apply directly to Rust binaries (vtable-like trait objects,
  name mangling). A Rust frontend would be a sibling project.
- **Game engine ABI quirks.** Unreal, Unity (native), and other
  game engines have their own C++ ABI bending; future RFCs.

## Sequencing

| PR | Scope |
|---|---|
| #37-1 (this RFC) | The RFC document |
| #37-2 | `CppTypeSystem` skeleton + tests |
| #37-3 | `CppDemanglingFeeder` |
| #37-4 | `CppRttiAnalyzer` (Itanium) |
| #37-5 | `CppRttiAnalyzer` (MSVC) |
| #37-6 | `CppVTableAnalyzer` |
| #37-7 | `CppDecompilerHints` for upcasts + downcasts |
| #37-8 | `CppDecompilerHints` for vmethod calls |
| #37-9 | `CppDecompilerHints` for ctor/dtor recognition |
| #37-10+ | Polish, templates, operator overloading |

Reviewers: see [MAINTAINERS.md](../../MAINTAINERS.md); this RFC
needs sign-off from a decompiler maintainer and a type-system
maintainer before #37-2 lands.
