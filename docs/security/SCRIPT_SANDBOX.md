# Script Sandbox Mode

*Addresses Rec 16 of the 2026-05-21 principal-architect audit.*

## Problem

`GhidraScript` (Java) and `PyGhidraScriptProvider` (Python) run with
full JVM privileges: arbitrary classloading, reflection,
`Runtime.exec`, filesystem read/write, network. There is no opt-in
sandbox. The threat shape is concrete:

- **Headless mode + malicious script in a shared directory.**
  `analyzeHeadless` picks up any `*.java`/`*.py` from configured
  script paths. A malicious script dropped into `~/ghidra_scripts/`
  by an attacker with write access there (or by a malicious tarball
  the user extracted) executes the moment the user runs an automation
  job.
- **Untrusted analysis pipeline.** A SOC running Ghidra headless
  against suspicious uploads cannot pin scripts; an injected script
  path equals code execution.

The current posture treats scripts as fully trusted code, the same as
the JVM itself. That trust is appropriate for hand-written scripts
the user is iterating on. It is not appropriate for everything the
script search path can pick up.

## Decision

Add an opt-in **trusted-scripts-only** mode (`ghidra.script.sandbox`)
that:

1. Refuses to load any script outside a configured allowlist.
2. Logs every script load with absolute path + sha256.
3. Defaults to **off** to preserve current behaviour. The user
   opts in with `-Dghidra.script.sandbox=on` (UI) or
   `-scriptSandbox` (headless).

"Sandbox" in this rec means **gating which scripts may run**, not
"sandbox the JVM at runtime." JVM-level sandboxing of a running
script is an open research problem; gating is a tractable, useful
first line of defence.

## Modes

| Mode | Default | Behaviour |
|---|---|---|
| `off` | yes | Current behaviour. Any script on the search path is loaded. |
| `allowlist` | no | Only scripts whose absolute path + sha256 appear in `script-allowlist.txt` are loaded. Any other script is refused with a logged error. |
| `signed` | no (future) | Only scripts signed by a configured trust root are loaded. Out of scope for the initial PR; design lives below. |

Switching to `allowlist` is a one-line change on the command line
or in `support/launch.properties`; there is no migration cost for
users who do not opt in.

## Allowlist file format

Plain text, one entry per line, `<sha256> <abs-path>`:

```
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 /home/user/ghidra_scripts/my_analyzer.py
6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d /opt/ghidra/scripts/golden/disassemble_all.java
```

The file lives at `~/.config/ghidra/script-allowlist.txt` by default;
the path is configurable via `-Dghidra.script.allowlist`.

Path is matched exactly (no globs, no symlink following). sha256 is
matched exactly. A user who edits a script must re-add its new
hash to the allowlist — that is the point.

## Where the gate sits

The check happens in the script provider's `loadClass` /
`loadFromFile` path, before the script's bytecode/source is handed
to the JVM. The check is:

```java
if (ScriptSandbox.isEnabled() && !ScriptSandbox.allow(file)) {
    throw new ScriptSandboxRefusal(file, expectedSha256);
}
```

`ScriptSandbox` is a singleton initialised from system properties at
JVM start. It is immutable after init — no API for code to
weaken the gate at runtime.

## Headless flag

`analyzeHeadless` gets a `-scriptSandbox <allowlist-path>` flag that
sets the system property and points at the allowlist. The flag also
forces the log destination so a SOC running this in automation can
assert "yes, this run was sandboxed" from the audit trail.

## What this does *not* do

- It does not stop a script with full trust (allowlisted) from doing
  anything. Once a script is allowed, it has the same JVM
  privileges as the current default. The intent is to gate *which*
  scripts run, not to restrict *what* a running script can do.
- It does not stop a user who is rooted on the box. An attacker
  with `/home/user/.config/` write access can edit the allowlist
  itself; this gate is meaningful only against an attacker who can
  drop scripts but cannot modify the allowlist.
- It does not stop the user from disabling the gate. That is
  intentional — the gate is opt-in and opt-out, not policy-enforced.

## Signed-script mode (future)

`signed` mode is sketched here so the allowlist format does not
foreclose it:

- Each script ships alongside a `<script>.sig` file (Cosign-style
  signature).
- The trust root is one or more public keys configured at
  `~/.config/ghidra/script-trust.d/*.pub`.
- Load-time check: verify the signature; if valid and trust root
  configured, load.

`signed` mode is its own RFC ([RFC_PROCESS.md](../governance/RFC_PROCESS.md))
once `allowlist` mode is shipped and we have feedback on the
ergonomics.

## Risk: false sense of security

The biggest risk of this feature is users opting into
`allowlist` mode and assuming it means "scripts can't do
anything bad." It does not. The doc strings on
`-scriptSandbox` and the README copy will both explicitly say:
**this gates *which* scripts run; it does not restrict *what* an
allowed script can do.**

## Implementation plan

The initial PR ships:

1. The `ScriptSandbox` Java class with the singleton + allowlist
   parser.
2. The `ScriptSandboxRefusal` exception.
3. Wiring into `GhidraScriptUtil.findScriptByName(...)` and
   `JavaScriptProvider.getScriptInstance(...)`.
4. Wiring into `PyGhidraScriptProvider.getScriptInstance(...)`.
5. The `-scriptSandbox` headless flag.
6. Unit tests covering: gate-off (default), gate-on with allow,
   gate-on with refuse, headless flag setting the property,
   refusal on absent allowlist file.

These are tracked as sub-issues of #16 and land in successive PRs
under [`lane:framework`](../governance/lanes/PROCESSOR_LANE.md).
The Java class and the allowlist parser are the first PR (≤500 LOC);
the rest follow.

## Threat model coverage

After landing, [SECURITY.md](../../SECURITY.md) is updated to add a
new in-scope item: "A `ScriptSandbox.allowlist` mode that fails to
gate a non-allowlisted script is a security bug."
