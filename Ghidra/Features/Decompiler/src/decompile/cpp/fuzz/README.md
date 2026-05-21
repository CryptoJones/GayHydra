# Decompiler Fuzz Harnesses

Scaffold for Rec 13. See [`docs/security/OSS_FUZZ.md`](../../../../../../../docs/security/OSS_FUZZ.md)
for the full integration plan.

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

## Build (OSS-Fuzz)

OSS-Fuzz uses `.github/oss-fuzz/build.sh` and `Dockerfile`. See
[`docs/security/OSS_FUZZ.md`](../../../../../../../docs/security/OSS_FUZZ.md#oss-fuzz-submission-checklist)
for the submission checklist.

## Seeds

Place per-target seed corpora under `seeds/<target>/`. The OSS-Fuzz
`build.sh` packages them into `${OUT}/${target}_seed_corpus.zip`
automatically.

## Adding a new harness

1. Write `fuzz_<name>.cc` here with the libFuzzer contract.
2. Add a `<name>_OBJS` rule plus an `all:` dep in `Makefile.fuzz`.
3. Append it to the for-loop in `.github/oss-fuzz/build.sh`.
4. Add the target row to the table in `OSS_FUZZ.md`.
