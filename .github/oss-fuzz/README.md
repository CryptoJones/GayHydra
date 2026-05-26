# `.github/oss-fuzz/` — staging directory for OSS-Fuzz upstream submission

This directory holds the three files that get copied verbatim to
`projects/ghidra-decompiler/` in [google/oss-fuzz](https://github.com/google/oss-fuzz):

- [`project.yaml`](project.yaml) — OSS-Fuzz manifest (contacts, sanitizers, engines)
- [`Dockerfile`](Dockerfile) — build container definition
- [`build.sh`](build.sh) — harness build script

See [`docs/security/OSS_FUZZ.md`](../../docs/security/OSS_FUZZ.md) for the
broader integration plan and the in-tree harness sources at
`Ghidra/Features/Decompiler/src/decompile/cpp/fuzz/`.

## Sync workflow

Files here are kept byte-identical with their upstream counterparts at
[`google/oss-fuzz/projects/ghidra-decompiler/`](https://github.com/google/oss-fuzz/tree/master/projects/ghidra-decompiler).
Editing in either location requires copying to the other in the same
sweep so the two don't drift.

The Google-style `Copyright YYYY Google LLC` Apache 2.0 header on
`Dockerfile` and `build.sh` is the OSS-Fuzz convention for everything
under `projects/` — `dpebot`'s `header-check` bot enforces it on every
PR to google/oss-fuzz. Carrying the same header in-tree keeps the
`cp`-to-upstream operation a one-step sync rather than a diff-and-fix.
