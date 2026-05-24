# crackme-arrayxor

A small Go crackme: reads a password from stdin and accepts it iff it matches an embedded password decoded byte-by-byte from two 16-byte arrays via XOR. Harder cousin of [`gayhydra-dropper`](../gayhydra-dropper/) — that one uses XOR with a single constant `0x5A`; this one uses a per-position XOR pad.

## Behavior

```text
$ go build -o crackme .
$ echo "wrongpw" | ./crackme
password: Try again.    (exit 1)
```

The "Correct!" path requires a 16-character password whose byte-wise XOR against the embedded `key[]` array equals the embedded `expected[]` array.

## RE puzzle (don't peek at `main.go`)

The stripped binary contains:

- A 16-byte `key[]` array (`.noptrdata` / `.rodata`)
- A 16-byte `expected[]` array (same)
- An inlined `check()` loop with one register-to-register `XOR` instruction that pairs them

To recover the password without running the binary, a student must:

1. Locate the two 16-byte arrays in initialized memory. (Hint: they're adjacent in the data segment and don't move with code-layout drift.)
2. Identify the XOR loop in `main.main` (one `XOR <reg>, <reg>` with non-zero operands; the `xor %eax,%eax` style register-zeroings are common Go-compiler boilerplate and don't count).
3. Compute `key[i] XOR expected[i]` for `i = 0..15`. The result is the password.

What this exercises *over* `gayhydra-dropper`:

- **Array-scoped deobfuscation** rather than constant-immediate XOR. The XOR key isn't a literal `0x5A` you spot in the disassembly — it's a 16-byte data structure you have to identify and dump.
- **Reasoning about data layout**. The arrays live in Go's `.noptrdata` segment; recognizing that section vs `.text` vs `.rodata` is part of the exercise.
- **Cross-array correlation**. Recovering one array isn't enough — you need both, and you need to know which is which (key vs expected) to decode in the right direction.

## Build

```bash
cd samples/re-targets/crackme-arrayxor
go build -ldflags="-s -w" -o crackme-stripped .
```

`-s -w` strips ELF symbols and DWARF, matching the real-world handed-a-binary scenario.

## Smoke test (Ghidra headless)

```bash
mkdir -p /tmp/gh-project-crackme
<gayhydra-install>/support/analyzeHeadless /tmp/gh-project-crackme CrackmeRE \
  -import ./crackme-stripped \
  -postScript DumpCrackme.java \
  -scriptPath ./scripts \
  -deleteProject
```

Expected output:

```text
DumpCrackme.java> key[] (16 bytes) raw occurrences in initialized memory: 1
DumpCrackme.java> expected[] (16 bytes) raw occurrences in initialized memory: 1
DumpCrackme.java> XOR-decoded password: "ghidrarocks!2026"
DumpCrackme.java> RESULT: PASS — both arrays present in binary; XOR-decode reconstructs the expected password
```

The script scans raw initialized-memory bytes for the two 16-byte sequences. It's compilation-independent — works regardless of Go version, Ghidra Go-analyzer state, or function-bounds recognition (none of which are required, unlike the dropper's decompiler-level check).

Wired into `.github/workflows/release.yml` as a secondary smoke test step (after the dropper's primary decompiler-sanity gate). Complementary signal — the dropper asserts the **decompiler** still recovers the XOR loop, this asserts Ghidra's **data-segment recognition** still finds the constant arrays. Either failing fails the release.
