package main

import "testing"

func TestRot13_RoundTrip(t *testing.T) {
	// Round-trip property: rot13(rot13(s)) == s for any input.
	cases := []string{
		"",
		"A",
		"hello world",
		"Decompiler is fun!",
		"Qrpbzcvyre vf sha!",
		"MixedCASE123!@#",
	}
	for _, c := range cases {
		if got := rot13(rot13(c)); got != c {
			t.Errorf("round-trip failed for %q: got %q", c, got)
		}
	}
}

func TestRot13_KnownPairs(t *testing.T) {
	cases := []struct{ in, want string }{
		{"", ""},
		{"a", "n"},
		{"n", "a"},
		{"A", "N"},
		{"Z", "M"},
		{"Hello, World!", "Uryyb, Jbeyq!"},
		{"Decompiler is fun!", "Qrpbzcvyre vf sha!"},
		// Non-letters pass through:
		{"0123456789", "0123456789"},
		{"!@#$%^&*()", "!@#$%^&*()"},
	}
	for _, c := range cases {
		if got := rot13(c.in); got != c.want {
			t.Errorf("rot13(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestRot13_EmbeddedSecretDecodes(t *testing.T) {
	// The embedded `secret` constant is supposed to round-trip to a
	// known plaintext. If anyone re-encodes the secret with a different
	// key (or accidentally uses ROT11/ROT5), this catches it.
	want := "Decompiler is fun!"
	if got := rot13(secret); got != want {
		t.Errorf("rot13(secret) = %q, want %q", got, want)
	}
}
