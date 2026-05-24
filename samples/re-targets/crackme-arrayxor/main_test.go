package main

import "testing"

func TestCheck_CorrectPassword(t *testing.T) {
	// The embedded key[] XOR expected[] is supposed to decode to a
	// specific known plaintext. If anyone regenerates either array
	// with a different password (or transposes some bytes), this
	// catches the mismatch immediately.
	if !check("ghidrarocks!2026") {
		t.Error("check(correct password) returned false — key[] / expected[] arrays are out of sync")
	}
}

func TestCheck_WrongPassword(t *testing.T) {
	cases := []string{
		"",
		"a",
		"ghidrarocks!202",   // one short
		"ghidrarocks!20266", // one too long
		"ghidrarocks!2027",  // last char differs
		"shidrarocks!2026",  // first char differs
		"GHIDRAROCKS!2026",  // case differs
	}
	for _, c := range cases {
		if check(c) {
			t.Errorf("check(%q) returned true — should reject", c)
		}
	}
}

func TestArrayShapes(t *testing.T) {
	if len(key) != 16 {
		t.Errorf("key has %d bytes, expected 16", len(key))
	}
	if len(expected) != 16 {
		t.Errorf("expected has %d bytes, expected 16", len(expected))
	}
}

func TestDecodedPasswordIsAscii(t *testing.T) {
	// XOR-decoded result should be printable ASCII. If a byte decodes
	// to something non-printable, either the arrays drifted or the
	// password was changed without re-encoding properly.
	for i := 0; i < len(expected); i++ {
		decoded := expected[i] ^ key[i]
		if decoded < 0x20 || decoded > 0x7E {
			t.Errorf("expected[%d] ^ key[%d] = 0x%02X (non-printable; arrays out of sync?)",
				i, i, decoded)
		}
	}
}
