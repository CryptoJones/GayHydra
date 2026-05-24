# rot13-secret

A small Go program that prints a ROT13-decoded message at startup. Third RE training target after [`gayhydra-dropper`](../gayhydra-dropper/) (single-byte XOR) and [`crackme-arrayxor`](../crackme-arrayxor/) (array XOR). Uses ROT13 — a **non-XOR cipher** — to teach a different deobfuscation skill.

## Behavior

```text
$ go run .
Decompiler is fun!
```

No input is read; no filesystem writes; deterministic output.

## RE puzzle (don't peek at `main.go`)

The stripped binary contains:

- A string literal `"Qrpbzcvyre vf sha!"` in `.rodata`. Looks like text-in-a-foreign-language at first glance — that's the cipher hint.
- A `main.rot13` function (inlined into `main.main`) with the characteristic ROT13 compiled pattern:
  - `LEAL -0x41(REG), REG2` — subtract `'A'`
  - `LEAL -0x61(REG), REG2` — subtract `'a'`
  - `ADDL $0xd, REG` — add 13
  - `SHRL $0xd, REG` — the constant-modulo trick the Go compiler uses for `% 26`
- Conditional branches that pick the uppercase / lowercase / non-letter path.

To recover the plaintext without running the binary:

1. Find the suspicious-looking string `"Qrpbzcvyre vf sha!"` in the data segment.
2. Notice the cipher pattern in `main.main`: subtract a letter base, add 13, modulo 26. That's ROT13.
3. Apply ROT13 to the string by hand (or in Python): `Qrpbzcvyre vf sha!` → `Decompiler is fun!`.

What this exercises *over* the XOR samples:

- **Non-XOR cipher recognition.** No `XOR` instruction with a recognizable key. The transformation lives in `LEA` + `SHR` + `ADD` arithmetic.
- **"That string looks like ROT13"** as a meta-skill. The encoded form has the same letter-frequency distribution as the plaintext (because ROT13 is a permutation), so a glance at the .rodata strings can hint at the cipher type before any disassembly.
- **Conditional-arithmetic patterns** — three code paths (uppercase / lowercase / non-letter) merged at the end via register liveness.

## Build

```bash
cd samples/re-targets/rot13-secret
go build -ldflags="-s -w" -o rot13-stripped .
```

## Smoke test

```bash
mkdir -p /tmp/gh-project-rot13
<gayhydra-install>/support/analyzeHeadless /tmp/gh-project-rot13 Rot13RE \
  -import ./rot13-stripped \
  -postScript DumpRot13.java \
  -scriptPath ./scripts \
  -deleteProject
```

Expected output:

```text
DumpRot13.java> encoded string "Qrpbzcvyre vf sha!" raw occurrences in initialized memory: 1
DumpRot13.java> disassembled instructions with immediate operand 0x0D: 49
DumpRot13.java> rot13 round-trip of the encoded string: "Decompiler is fun!"
DumpRot13.java> RESULT: PASS — encoded secret present, ROT13 transform constant present, round-trip decodes to expected plaintext
```

The `0x0D` immediate count is much higher than just the rot13 sites (49) because the constant `13` appears in many other Go runtime contexts. The conjunctive check (string + immediate + round-trip) is what makes the assertion meaningful — any one of the three could falsely match in isolation.

Not yet wired into `.github/workflows/release.yml` — the dropper and crackme cover the decompiler and data-segment signals respectively. Adding rot13 as a third gate would catch regressions in Go's arithmetic-lowering of small-divisor modulo (the `SHRL $0xd` trick), but the existing two samples already provide strong coverage. Wiring is a one-block addition if anyone wants the third signal.
