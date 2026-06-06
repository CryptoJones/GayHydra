---
number: 0015
title: Rec 37 #37-6c — reconcile recovered vtable slots with demangling-fed declared methods in a pure in-model pass; conservative unique-name matching, signature/DataType resolution deferred
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0015: vtable↔declared-method reconciliation ships as a pure in-model pass (unique-name unification, propagating isVirtual onto the canonical method), not a demangled-type/DataType resolver

## Context

The three Rec 37 *producer* feeders are now shipped, all pure in-model mappers in
`Ghidra/Features/Base` package `ghidra.app.util.cpp`:

- [DD-0012](0012-rec37-demangling-feeder.md) #37-3 `CppDemanglingFeeder` —
  attaches declared {@code CppMethod}s (name + `const`/`static`/calling
  convention) to a `CppClass` via `addMethod`, but **does not** set
  `CppMethod.isVirtual` (a demangled name alone does not reveal vtable
  membership).
- [DD-0013](0013-rec37-rtti-inheritance-feeder.md) #37-4 `CppRttiFeeder` —
  inheritance edges.
- [DD-0014](0014-rec37-vtable-feeder.md) #37-6 `CppVTableFeeder` — builds a
  `CppVTable` of one `CppMethod` per slot, every slot marked `setVirtual(true)`,
  and explicitly **defers reconciling those slot methods with the
  demangling-fed declared methods** ("[the model] has no method-identity key …
  inventing a slot↔declared-method matching heuristic is its own concern —
  deferred to … #37-7+. This slice appends slot methods to the `CppVTable` and
  does not attempt to merge them into `CppClass.getMethods()`").

This leaves a class that has been through both #37-3 and #37-6 holding **two
disjoint `CppMethod` lists for the same functions**: the declared methods in
`CppClass.getMethods()` (rich qualifiers, `isVirtual() == false`) and the fresh
vtable slot methods in `CppClass.getVtable().getSlots()` (`isVirtual() == true`,
no qualifiers). A consumer asking "which of this class's methods are virtual,
and which vtable slot does each occupy?" must cross-reference the two lists by
hand. This DD grounds the slice that closes that gap — the slot↔declared-method
reconciliation DD-0014 deferred — and, as with every Rec 37 slice so far, fixes
a shape that is *headlessly unit-testable with no `Program` and no cross-module
dependency* ([[always-test-before-push]]).

### Why this, and not "DataType signature resolution", is the next headless slice

DD-0014's deferred-work list bundled two different #37-7+ items: this
reconciliation **and** "parameter/return `DataType` resolution". The latter is
**not** cleanly headless: resolving a demangled parameter/return type to a Ghidra
`DataType` goes through `DemangledDataType.getDataType(DataTypeManager)`
(`DemangledDataType.java:135`), and the in-tree signature-building path
(`DemangledFunction.convertMangledToParamDef`, `:705`, and `:497` building the
`FunctionDefinitionDataType`) resolves every type against
`program.getDataTypeManager()` — a `Program`-bound `DataTypeManager`. A signature
resolver therefore inherently needs a `DataTypeManager` (named-type lookups in
particular), which pulls the DTM/Program coupling the headless cores keep out;
the RFC itself files DataType resolution under late polish. Reconciliation, by
contrast, touches only already-built model objects and needs neither a
`Program`, a `DataTypeManager`, nor any demangled-name parsing — so it is the
clean next headless step. (Signature/DataType resolution stays deferred; see
Sequencing.)

## What the model already provides (grounding)

All accessors this slice needs exist from #37-2 (DD-0011), #37-3, and #37-6;
nothing new is required except one small `CppVTable` mutator (decision 3):

- `CppTypeSystem.getCppClasses()` (`CppTypeSystem.java:91`) — an unmodifiable,
  definition-ordered `Map<String, CppClass>` for a whole-model pass;
  `getCppClass(name)` for one class.
- `CppClass.getMethods()` (`:122`, declared methods from #37-3),
  `getVtable()` (`:129`, the #37-6 table).
- `CppVTable.getSlots()` / `getSlot(int)` / `getSlotCount()` (`:74`/`:60`/`:67`)
  — the slot methods in layout order. **There is no `setSlot`** today
  (only `addSlot` appends), so unifying a slot to point at the canonical declared
  method needs a new bounded `setSlot(int, CppMethod)` mutator.
- `CppMethod.getName()` (`:57`, the unqualified name both feeders key on — #37-3
  stores `function.getName()`, #37-6 stores the slot's `methodName`),
  `setVirtual` (`:105`), `setPureVirtual` (`:123`), `isVirtual` / `isPureVirtual`.

## The decision

1. **#37-6c ships a pure in-model reconciler: `CppVtableReconciler`, matching a
   class's vtable slot methods to its declared methods and unifying each unique
   match onto one canonical `CppMethod`.** It walks the model (one class, or all
   classes via `getCppClasses()`), and for each class that has *both* a vtable and
   declared methods, reconciles slots against declared methods. It does not scan a
   `Program`, touch a `DataTypeManager`, or parse any name. It lands in
   `Ghidra/Features/Base` package `ghidra.app.util.cpp`, beside the feeders.

2. **The match key is the unqualified method name, and matching is conservative:
   only a name that is unique among the declared methods *and* among the vtable
   slots (a 1:1 pairing) is reconciled.** A name shared by overloads on either
   side is **left unreconciled** in this slice — no guessing which overload owns
   which slot, so no false virtuality is ever stamped onto the wrong method.
   Disambiguating overloaded names needs method *signatures*, which needs the
   deferred DataType resolution; this slice deliberately does the safe subset.

3. **For a unique 1:1 match, the declared method is canonical: the reconciler
   sets its `isVirtual(true)` and copies the slot's `isPureVirtual`, then replaces
   the vtable slot to reference that declared method** (via a new
   `CppVTable.setSlot(int index, CppMethod method)`, bounds- and null-checked,
   mirroring `addSlot`'s guard), discarding the throwaway #37-6 slot method. The
   declared method is chosen as canonical because it carries the richer demangled
   qualifiers (`const`/`static`/calling convention) the slot method lacks; the
   slot only contributed virtuality. The result is **one `CppMethod` per function,
   reachable from both `getMethods()` and the vtable slot**, with both virtuality
   and qualifiers.

4. **Unmatched slots and unmatched declared methods are left exactly as they
   are.** A slot whose name has no unique declared counterpart keeps its #37-6
   fresh method (already `isVirtual() == true`); a declared method with no vtable
   slot keeps `isVirtual() == false`. The pass never *adds* methods, never *removes*
   a declared method, and never mutates a backing `Structure` — it only flips
   virtuality on a matched declared method and rewrites a matched vtable slot
   reference (DD-0011 decision 3 guardrail holds).

5. **The pass is idempotent and order-independent.** Re-running it after a match
   has been unified is a no-op (the slot already references the declared method,
   whose flags are already set); reconciling a class with no vtable, or a vtable
   with no declared methods, does nothing. This keeps it safe to run after any
   combination of feeder passes, in any order.

6. **No signature/DataType resolution, no name parsing, no hints here.** Building
   `CppMethod.signature` (`FunctionDefinition`) from demangled parameter/return
   types (DTM-coupled), materialising template-instance classes, operator naming,
   and the decompiler-facing `CppDecompilerHints` all remain later/deferred slices
   (see Sequencing). This slice is purely the model-coherence step that those
   consumers depend on.

## Validation

#37-6c ships fast **headless** JUnit in
`Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/`
(`CppVtableReconcilerTest`, `AbstractGenericTest`) that builds the pre-reconciled
state directly through the existing feeders / model setters — no `Program`, no
`DataTypeManager`, no demangler. Tests assert:

- a unique 1:1 name match flips the **declared** method to `isVirtual() == true`
  (and copies `isPureVirtual`), and the vtable slot at that index now references
  the *same* `CppMethod` object as the one in `getMethods()` (object identity);
- the canonical method retains its demangled qualifiers
  (`const`/`static`/calling convention) — i.e. the declared method, not the slot
  method, survives;
- an overloaded name (two declared methods or two slots sharing a name) is left
  unreconciled: no declared method is wrongly marked virtual and the slot keeps
  its own method;
- a slot with no declared counterpart keeps its #37-6 method (still virtual); a
  declared method with no slot stays `isVirtual() == false`;
- a class with no vtable, and a class with an empty vtable, are no-ops;
- the whole-model pass (`getCppClasses()`) reconciles each class independently and
  is idempotent (second run changes nothing);
- the new `CppVTable.setSlot` rejects null and out-of-range index, and replaces in
  place without changing `getSlotCount()`.

Gating reminders specific to #37-6c (each a hard local gate before push):

- The reconciler and the `CppVTable.setSlot` addition are Java-only and headlessly
  testable, so `gradle :Base:test` is the validating gate (run the new test plus
  the sibling `Cpp*Test`s to confirm no regression); no `--full` C++ precheck is
  required ([[always-test-before-push]]).
- The new `CppVtableReconciler.java` carries the inline `IP: GHIDRA` Apache header,
  so it passes `gradle :Base:ip` via the header with **no `certification.manifest`
  entry** (as for #37-2/#37-3/#37-4/#37-6). Run `gradle :Base:ip` to confirm; a
  manifest entry is only needed for header-less / generated tracked files
  ([[new-source-file-ip-manifest]]). The `cppRaiiAudit` gate is C++-only and does
  not apply ([[new-cpp-file-raii-audit]]).
- The impl slice should also correct the remaining stale `#37-5` "vtable analyzer"
  reference in `CppTypeSystem.java` javadoc (the #37-6 PR fixed `CppVTable`/
  `CppClass`; `CppTypeSystem` still says `#37-5`) as in-scope cleanup.

## Sequencing (refines DD-0014's table)

| PR | Scope |
|---|---|
| #37-2 … #37-4 | *(shipped)* model skeleton; demangling feeder; Itanium RTTI feeder |
| #37-6 | *(shipped, DD-0014)* `CppVTableFeeder` — recovered-slot-fact → `CppVTable` + `CppMethod.isVirtual` mapper |
| #37-6c | **(this DD's subject)** `CppVtableReconciler` — pure in-model pass unifying vtable slots with demangling-fed declared methods by unique name; propagates `isVirtual`/`isPureVirtual` onto the canonical declared method; adds `CppVTable.setSlot` |
| #37-4b | `CppRttiAnalyzer` (Itanium) program-scan wrapper — deferred, not headless |
| #37-5 | MSVC RTTI program-scan wrapper — deferred, not headless |
| #37-6b | `CppVTableAnalyzer` program-scan wrapper (+ dedicated-`Features/Cpp`-module decision) — deferred, not headless |
| #37-7+ | `CppMethod` signature / parameter+return `DataType` resolution (DTM-coupled); signature-based overload reconciliation; `CppDecompilerHints`; templates + operators |

## Rejected alternatives

- **Ship "parameter/return `DataType` resolution" as the next headless slice
  instead.** Rejected for now: resolving demangled types goes through
  `DemangledDataType.getDataType(DataTypeManager)` and the in-tree path binds to
  `program.getDataTypeManager()`, so it needs a `DataTypeManager` (named-type
  lookups especially) — DTM/Program coupling that is not cleanly headless. It is
  also a prerequisite *consumer* of method identity, not the identity step itself.
- **Match overloaded names positionally / heuristically.** Rejected: without
  signatures there is no sound way to assign which overload owns which slot;
  guessing risks stamping `virtual` onto the wrong overload. The conservative
  unique-name subset is sound now; overload disambiguation waits for signatures.
- **Propagate `isVirtual` onto the declared method but leave the vtable holding
  its own fresh slot method (no unification).** Rejected: that leaves two
  `CppMethod` objects per function and makes "walk the vtable, get the method with
  its qualifiers" impossible. Unifying onto one canonical method (via the small
  `setSlot` addition) is the correct end state and barely larger.
- **Keep the slot method as canonical and copy the declared qualifiers onto it.**
  Rejected: the declared method is already attached to `CppClass.getMethods()` and
  is the object every non-vtable consumer already references; making the slot
  canonical would orphan the declared method or require rewriting `getMethods()`.
- **Add the reconciliation into `CppVTableFeeder` (#37-6) rather than a separate
  pass.** Rejected (already, by DD-0014): the feeder runs before the demangling
  feeder may have populated declared methods, and a feeder should map its own
  input, not depend on another feeder's output having already run. A standalone
  pass run after both feeders is order-robust.

## References

- [DD-0014](0014-rec37-vtable-feeder.md) — the #37-6 vtable feeder that produces
  the slot methods and explicitly deferred this reconciliation; [DD-0012](0012-rec37-demangling-feeder.md)
  — the #37-3 feeder that produces the declared methods (and does not set
  `isVirtual`); [DD-0011](0011-rec37-cpptypesystem-skeleton.md) — the model and
  the projection guardrail.
- [RFC-0001](../rfcs/0001-cpp-frontend.md) — parent proposal; this slice supplies
  the cross-feeder model coherence the `CppDecompilerHints` (§5) consume.
- Model under change (Base, shipped #37-2):
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppVTable.java`
  (`addSlot :46`, `getSlot :60`, `getSlots :74`; `setSlot` to be added),
  `CppMethod.java` (`getName :57`, `setVirtual :105`, `setPureVirtual :123`),
  `CppClass.java` (`getMethods :122`, `getVtable :129`),
  `CppTypeSystem.java` (`getCppClasses :91`, `getCppClass :84`).
- Why DataType resolution is not headless (shape, not a dependency):
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/demangler/DemangledDataType.java`
  (`getDataType(DataTypeManager) :135`),
  `DemangledFunction.java` (`convertMangledToParamDef :705`, `FunctionDefinitionDataType :497`).

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
