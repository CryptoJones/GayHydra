/*
 * Decompiler smoke-test target.
 *
 * Intentionally trivial — the gate this drives asserts only that the
 * decompiler can produce *some* non-empty C output for a known-good
 * binary, not that it produces any specific text. Constant-matching
 * is what made the prior dropper / crackme gates brittle; this gate
 * deliberately stays at the "did the decompile pipeline even survive"
 * level so it catches the catastrophic regressions (analyzer crash,
 * headless launch broken, decompiler exception on a trivial input)
 * without flapping on small output-drift between Ghidra versions.
 */

#include <stdio.h>

static int add_one(int x) {
    return x + 1;
}

int main(int argc, char **argv) {
    (void)argv;
    int n = argc;
    n = add_one(n);
    printf("%d\n", n);
    return n;
}
