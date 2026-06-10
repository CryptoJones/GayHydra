---
number: 0073
title: Rec 37 #37-12b — vftable slot signatures; CppVTableFeeder.SlotSpec gains an optional FunctionDefinition (two-argument form unchanged) and the MSVC vftable driver fills it from the Function defined at the slot's address — the signature the demangler analyzer already applied — so vtable slot methods carry resolved signatures where the program has them, and feed by name alone where it does not
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0073: slot signatures ride the SlotSpec, sourced from the slot's Function

## Context

[DD-0072](0072-rec37-demangled-signatures.md) opened the `#37-12` band on the demangling-feeder
side. The vtable side has the same gap with a different source: the vftable driver (DD-0064)
resolves each slot's *name* from its function's primary symbol and feeds a `SlotSpec(name, isPure)`
— but the `Function` defined at that address carries the full signature the demangler analyzer
already applied, and the fed `CppMethod.signature` stayed null.

## Decision

- **The fact rides the `SlotSpec`.** The record gains a third component,
  `FunctionDefinition signature` (null = unrecovered), with a two-argument convenience constructor
  so every existing caller and the pre-`#37-12b` recoverer shape compile unchanged. The feeder sets
  it on the slot's `CppMethod` — the feeder remains the one translation point, no post-feed
  patching of fed models.
- **The driver sources it from the slot's `Function`**:
  `new FunctionDefinitionDataType(function.getSignature())` when
  `getFunctionAt(slotFunctionAddress)` is defined — the signature the listing shows, because the
  demangler analyzer applied it there. No `Function` at the address (a label-only slot, the
  fixture's default) or any failure feeds a null signature and the slot still feeds by name —
  never-wrong over complete, per-slot rather than per-table (unlike the *name* gates, a missing
  signature misleads nobody).

## Consequences

- Vtable slot methods now carry resolved signatures wherever the program defines the slot function;
  the two `#37-12` feed paths (demangling feeder, vftable driver) populate the same model field
  from their respective canonical sources. Feeder suite +1 (signature carries / two-argument form
  signatureless), vftable driver suite +1 (defined-function slot carries `int` return, label-only
  slot stays null); `CppVTableAnalyzer` suite unchanged.
- Remaining `#37-12` work: the hint consumers (`#37-12c+`) — parameter-typed renderings and
  overload-aware naming once a consumer needs them.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Base:test --tests '…CppVTableFeederTest'`,
  `gradle :MicrosoftCodeAnalyzer:test --tests '…CppMsvcVftableDriverTest' --tests
  '…CppVTableAnalyzerTest'`, and both `ip` tasks, Gradle 8.5 / Temurin 21 (the CI-matching
  toolchain).
