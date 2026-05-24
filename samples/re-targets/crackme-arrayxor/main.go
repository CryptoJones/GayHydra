// crackme-arrayxor: reverse-engineering training sample, harder cousin
// of gayhydra-dropper.
//
// Behavior: reads a single line from stdin, XOR-deobfuscates an embedded
// expected-password using an embedded 16-byte key array, and prints
// "Correct!" iff the user's input matches the deobfuscated password.
//
// RE puzzle for students: recover both arrays from the stripped binary
// and compute expected[i] ^ key[i] to retrieve the password. Unlike
// gayhydra-dropper (which uses a single XOR byte 0x5A), this sample
// uses a 16-byte key array — a per-position XOR pad. Teaches array-
// scoped deobfuscation patterns.
//
// SAFE: reads stdin, prints stdout, exits 0 or 1. No filesystem writes,
// no network, no privileged ops.

package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

// key is the per-byte XOR pad. Recoverable from the binary as a 16-byte
// contiguous region read inside check().
var key = [16]byte{
	0x3F, 0xA1, 0x5C, 0xE2, 0x07, 0x91, 0x44, 0xB8,
	0xD3, 0x6E, 0x25, 0x7A, 0xC9, 0x10, 0x88, 0xF5,
}

// expected is the obfuscated form of the password. The decoded value is
// password = expected[i] ^ key[i] for i in 0..15.
// Encoded password is "ghidrarocks!2026". (Don't peek.)
var expected = [16]byte{
	0x58, 0xC9, 0x35, 0x86, 0x75, 0xF0, 0x36, 0xD7,
	0xB0, 0x05, 0x56, 0x5B, 0xFB, 0x20, 0xBA, 0xC3,
}

// check decodes the expected password byte-by-byte and compares to the
// user's input. The XOR loop is the central recovery target for an RE
// student — it's the only place key[] and expected[] are read together.
func check(input string) bool {
	if len(input) != len(expected) {
		return false
	}
	for i := 0; i < len(expected); i++ {
		if input[i] != (expected[i] ^ key[i]) {
			return false
		}
	}
	return true
}

func main() {
	fmt.Fprint(os.Stderr, "password: ")
	reader := bufio.NewReader(os.Stdin)
	line, err := reader.ReadString('\n')
	if err != nil && line == "" {
		fmt.Fprintln(os.Stderr, "read error:", err)
		os.Exit(2)
	}
	line = strings.TrimRight(line, "\r\n")

	if check(line) {
		fmt.Println("Correct!")
		os.Exit(0)
	}
	fmt.Println("Try again.")
	os.Exit(1)
}
