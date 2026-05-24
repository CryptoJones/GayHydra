// rot13-secret: reverse-engineering training sample with a non-XOR cipher.
//
// Behavior: at startup, decodes an embedded ROT13-obfuscated message
// and prints it. No input is read; no filesystem writes; deterministic
// output.
//
// RE puzzle for students: recover the plaintext from the binary without
// running it. Unlike gayhydra-dropper (single-byte XOR) and crackme-
// arrayxor (16-byte key array XOR), this sample uses ROT13 — a
// case-preserving alphabet rotation. The decoded function isn't a
// straight XOR; it's a conditional arithmetic chain (subtract base,
// add 13, modulo 26, add base) with separate paths for uppercase /
// lowercase / non-letter.
//
// Teaches: recognizing non-XOR obfuscation, the conditional-arithmetic
// pattern of alphabetic rotations, and that "the obfuscated bytes look
// like text in a different language" (Qrpbzcvyre vf sha!) is itself a
// hint that ROT13 was used.
//
// SAFE: prints stdout, exits 0. No filesystem writes, no input.

package main

import "fmt"

// secret is the ROT13-obfuscated form of the message. Round-trip
// decodable: rot13(secret) → original plaintext.
const secret = "Qrpbzcvyre vf sha!"

// rot13 applies a ROT13 transformation to s. Standard implementation:
// for ASCII letters, subtract the base ('A' or 'a'), add 13, modulo 26,
// add the base back; non-letters pass through unmodified.
func rot13(s string) string {
	out := make([]byte, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case 'A' <= c && c <= 'Z':
			out[i] = (c-'A'+13)%26 + 'A'
		case 'a' <= c && c <= 'z':
			out[i] = (c-'a'+13)%26 + 'a'
		default:
			out[i] = c
		}
	}
	return string(out)
}

func main() {
	fmt.Println(rot13(secret))
}
