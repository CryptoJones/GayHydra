# Decompiler Fuzz Harnesses

Rec 13. See [`docs/security/OSS_FUZZ.md`](../../../../../../../docs/security/OSS_FUZZ.md)
for the per-harness rationale. Note: the OSS-Fuzz upstream submission
was rejected 2026-05-26 ([google/oss-fuzz#15545](https://github.com/google/oss-fuzz/pull/15545));
the harnesses below stand on their own and run locally / via our own
CI.

## Harnesses

| File | Target |
|---|---|
| `fuzz_xml.cc` | `xml_tree()` — the decompiler's XML parser. |
| `fuzz_marshal.cc` | `PackedDecode` — the Java↔C++ binary IPC parser. |

## Build (local)

```
make -f Makefile.fuzz fuzz_xml
make -f Makefile.fuzz ASAN=1 UBSAN=1 fuzz_xml
```

Requires:
- Clang with `-fsanitize=fuzzer`.
- Decompiler object files (`make -C ..` produces them; see the
  parent `Makefile`).

## Seeds

Place per-target seed corpora under `seeds/<target>/`.

## Adding a new harness

1. Write `fuzz_<name>.cc` here with the libFuzzer contract.
2. Add a `<name>_OBJS` rule plus an `all:` dep in `Makefile.fuzz`.
3. Add the target row to the table in `OSS_FUZZ.md`.
