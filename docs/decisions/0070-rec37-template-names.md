---
number: 0070
title: Rec 37 #37-10u — template class names need no code; the demangler hands the pipeline MyVec<int> and every layer keys/renders by name verbatim, so the slice is two guard tests (decoder and renderer) pinning the property rather than a feature
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0070: templates are already handled — pin it, don't build it

## Context

The `#37-10` different-in-kind tail names "templates" as remaining work (DD-0016's deferral table).
Before designing anything, the question was grounded: what does the existing pipeline actually
produce for an MSVC template class?

**Probe (2026-06-10):** a type descriptor carrying `.?AV?$MyVec@H@@` (class `MyVec<int>`) yields
`getDescriptorName() == "MyVec<int>"` — the demangler resolves the template arguments into the
unqualified name the whole Rec 37 stack keys by. `CppRttiFeeder.feedClass("MyVec<int>", …)` feeds
it, `CppClassResolution.resolveOrPlaceholder` happily builds the placeholder `StructureDataType`
with the angle-bracket name, and `CppTypeSystem.getCppClass("MyVec<int>")` resolves it.

## Decision

No feature code. The "template rendering" item dissolves the same way Rec 39's `for`-loop item did
(upstream/existing machinery already provides it); what a dissolved item needs is a **guard**, so
the property is pinned against regression rather than merely observed once:

- `CppMsvcRttiDecoderTest.testDecodesTemplateClassName` — the complete-flow fixture with Base's
  descriptor name overwritten to the template mangling decodes to `derivedName == "MyVec<int>"`.
- `CppDecompilerHintsTest.testTemplateClassConstructionRendersAngleBrackets` — a class fed under
  its demangled template name renders `new MyVec<int>(n)` verbatim.

What templates may still need later, and deliberately not now: *qualified* template scoping
(`std::vector<int>` arrives as unqualified `vector<int>` — the same namespace-flattening the whole
name model currently has, not a template-specific gap), and template *method* signatures (the
signature/`DataType`-resolution tail, still open).

## Consequences

- Template classes flow through harvest, type system, and renderers with zero new code; two guard
  tests pin it. Decoder suite 9/9; renderer suite +1.
- The remaining `#37-10` tail narrows to signature/`DataType` resolution and operator-overload
  rendering.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests '…CppMsvcRttiDecoderTest'` and
  `gradle :Base:test --tests '…CppDecompilerHintsTest'`, plus both `ip` tasks, Gradle 8.5 /
  Temurin 21 (the CI-matching toolchain).
