---
number: 0069
title: Rec 37 #37-10t — thread recovered arguments into the virtual-call driver; a decompiled CALLIND carries its explicit arguments as inputs[2..] exactly like the ctor CALL (probe-grounded), but typed undefinedN since an unresolved indirect call has no prototype, so the shared renderer gains a sign-bit-clear-only undefined-constant decimal policy and an unrenderable argument declines the whole hint rather than misrepresent the call's arity
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0069: virtual calls render their arguments, or decline whole

## Context

DD-0025 shipped the virtual-call driver rendering `param_1->draw()` with argument threading scoped
out — "an unresolved indirect `CALLIND` has no recovered prototype." The `#37-10a`–`s` band then
built the full argument-expression machinery for the construction drivers, and DD-0026's rule of
three was met when this slice arrived as the third user (the `CppOperandRenderer` extraction,
PR #392). What remained was the grounding question DD-0025 left open: does a decompiled `CALLIND`
carry recovered argument varnodes at all?

**Probe (2026-06-10):** `mov rax,[rcx]; mov edx,5; call qword [rax+8]; ret` decompiles to a
`CALLIND` with `numInputs=3` — target, receiver (`param_1`, typed `C *`), and the constant `0x5` as
`input[2]`. Arguments are recovered and sit exactly where the ctor `CALL`'s do. But the constant is
typed `undefined8`, not an `AbstractIntegerDataType`: with no prototype on the indirect call, the
decompiler knows the bits, not the signedness — every typed-literal gate in the shared renderer
would decline it.

## Decision

- **Recovery reuses the shared machinery verbatim**: `CppOperandRenderer.callArguments(callSite,
  program)` over the `CALLIND` — inputs after the target (0) and receiver (1), each rendered by the
  same leaf/literal/compound grammar the construction drivers use.
- **A new `undefinedConstantLiteral` branch** (after the typed branches in `leafExpr`): a
  prototype-less constant renders as its decimal value **iff the sign bit at the varnode width is
  clear** — such a pattern reads the same under signed and unsigned interpretation, so the decimal
  is faithful by construction. A sign-bit-set pattern (`-1` vs `18446744073709551615`) is ambiguous
  and declines. This branch also applies to the construction drivers (an undefined-typed ctor
  argument previously declined outright; now the unambiguous subset renders) — never-wrong is
  preserved in both directions.
- **An unrenderable argument declines the whole hint.** The pre-slice behaviour — rendering
  `param_1->draw()` for a call that *has* arguments — silently misrepresented the call's arity;
  now a virtual call renders with all its arguments or not at all. A zero-argument call renders
  exactly as before.

## Consequences

- `param_1->draw(5)` renders end-to-end from a real decompiled x86-64 virtual call; the all-ones
  ambiguous pattern declines. Verified in `CppVirtualCallDriverTest` (two new harness cases over
  the probe fixtures) with the original no-argument and unmodelled-class cases unchanged;
  `CppHintsCollectorTest` and the construction-driver suites unchanged.
- Remaining `#37-10` work: named/compound `CALLIND` arguments ride the shared grammar already (a
  named local renders by name today); the different-in-kind signature/template/operator tail
  remains.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest` over the virtual-call, collector, and construction suites,
  and `gradle :Base:ip`, Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
