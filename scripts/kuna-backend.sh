#!/usr/bin/env bash
# kuna-backend.sh — point a GayHydra headless run at kuna's decompiler core.
#
# kuna (https://github.com/Noelo-Lab/kuna) ships `kuna_ghidra`, a drop-in for
# the native `decompile` process that speaks Ghidra's decompiler wire
# protocol. GayHydra with that core is the third REA parity lane (stock
# Ghidra, GayHydra, GayHydra + kuna core). See docs/decompiler/KUNA_BACKEND.md.
#
# Nothing under the Ghidra install is touched. The swap uses the
# DecompileProcessFactory seam (#478): the GHIDRA_DECOMPILER_EXE environment
# variable names the executable to spawn. kuna_ghidra speaks the stock (v0)
# IPC framing, so the script also adds -Ddecompiler.framing=v0 to
# JAVA_TOOL_OPTIONS. Without it the fork's "auto" framing opens with a v1
# greeting that kuna never answers, and the first decompile hangs.
#
# A child process cannot change its parent shell's environment, so install
# and revert PRINT shell code on stdout and write an env file; every message
# goes to stderr. Typical use:
#
#   eval "$(scripts/kuna-backend.sh install)"   # this shell now uses kuna
#   scripts/kuna-backend.sh status              # which core a headless run spawns
#   scripts/kuna-backend.sh verify              # analyzeHeadless + /* Kuna v... */ check
#   eval "$(scripts/kuna-backend.sh revert)"    # back to the stock core
#
# For a process started elsewhere (REA's Ghidra launch, a service), source
# the env file that install writes:  . "$(scripts/kuna-backend.sh env-file)"
#
# Usage:
#   scripts/kuna-backend.sh [options] install
#   scripts/kuna-backend.sh [options] revert [--purge]
#   scripts/kuna-backend.sh [options] status
#   scripts/kuna-backend.sh [options] verify [binary|- [function]]
#                   (no binary, or "-": build a C sample; its default function
#                   is kuna_sample, and kuna_loop shows the edgelabel gap)
#   scripts/kuna-backend.sh [options] env-file
#
# Options:
#   --version <v>   kuna release tag, e.g. v1.980 (default: latest release;
#                   also KUNA_VERSION)
#   --platform <p>  linux_x86_64 | linux_arm_64 | mac_x86_64 | mac_arm_64 |
#                   win_x86_64 (default: this host)
#   --purge         with revert: also delete the downloaded releases
#
# Environment:
#   KUNA_CACHE_DIR       download cache (default ${XDG_CACHE_HOME:-~/.cache}/gayhydra/kuna)
#   GHIDRA_INSTALL_DIR   GayHydra install; needed by verify, used by status
#   KUNA_VERIFY_TIMEOUT  seconds before verify kills a hung run (default 300)
#
# Exit codes:
#   0  success
#   1  operation failed (download, unpack, or verify found no kuna marker)
#   2  usage or precondition error (unknown platform, missing tool)

set -euo pipefail

REPO="Noelo-Lab/kuna"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE_ROOT="${KUNA_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/gayhydra/kuna}"
ENV_FILE="$CACHE_ROOT/kuna-backend.env"
FRAMING_OPT="-Ddecompiler.framing=v0"

VERSION="${KUNA_VERSION:-}"
PLATFORM=""
PURGE=0

log() { echo "kuna-backend: $*" >&2; }
die() { log "$*"; exit 1; }
usage_die() { log "$*"; exit 2; }

detect_platform() {
	local os arch
	os="$(uname -s)"
	arch="$(uname -m)"
	case "$arch" in
		x86_64 | amd64) arch="x86_64" ;;
		arm64 | aarch64) arch="arm_64" ;;
		*) usage_die "unsupported CPU '$arch'; pass --platform" ;;
	esac
	case "$os" in
		Linux) echo "linux_$arch" ;;
		Darwin) echo "mac_$arch" ;;
		MINGW* | MSYS* | CYGWIN*) echo "win_$arch" ;;
		*) usage_die "unsupported OS '$os'; pass --platform" ;;
	esac
}

exe_name() {
	case "$1" in
		win_*) echo "kuna_ghidra.exe" ;;
		*) echo "kuna_ghidra" ;;
	esac
}

# Drop every -Ddecompiler.framing=... token from a JAVA_TOOL_OPTIONS value.
strip_framing() {
	local out="" tok
	for tok in $1; do
		case "$tok" in
			-Ddecompiler.framing=*) ;;
			*) out="${out:+$out }$tok" ;;
		esac
	done
	echo "$out"
}

# Single-quote a value for the printed shell lines.
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

http_get() {
	# $1 = url, $2 = output file (omit for stdout)
	if command -v curl >/dev/null 2>&1; then
		if [[ -n "${2:-}" ]]; then curl -fsSL --retry 3 -o "$2" "$1"; else curl -fsSL --retry 3 "$1"; fi
	elif command -v wget >/dev/null 2>&1; then
		if [[ -n "${2:-}" ]]; then wget -q -O "$2" "$1"; else wget -q -O - "$1"; fi
	else
		usage_die "need curl or wget"
	fi
}

# Unpack a zip with unzip, or with python3 where unzip is missing.
unpack_zip() {
	# $1 = zip, $2 = destination
	if command -v unzip >/dev/null 2>&1; then
		unzip -oq "$1" -d "$2"
	elif command -v python3 >/dev/null 2>&1; then
		python3 -m zipfile -e "$1" "$2"
	else
		usage_die "need unzip or python3"
	fi
}

# Print one member of a zip to stdout (same fallback as unpack_zip).
zip_member() {
	# $1 = zip, $2 = member
	if command -v unzip >/dev/null 2>&1; then
		unzip -p "$1" "$2"
	elif command -v python3 >/dev/null 2>&1; then
		python3 -c 'import sys, zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' "$1" "$2"
	else
		return 2
	fi
}

# Print "<tag> <asset url>" for the requested (or latest) release. The asset
# carries the stock Ghidra version kuna built the extension for
# (kuna-v1.980-KunaDecompiler-ghidra_12.1.2.zip); the headless core does not
# depend on it, so the first such asset in the release is used.
resolve_release() {
	local api json tag url
	if [[ -n "$VERSION" ]]; then
		[[ "$VERSION" == v* ]] || VERSION="v$VERSION"
		api="https://api.github.com/repos/$REPO/releases/tags/$VERSION"
	else
		api="https://api.github.com/repos/$REPO/releases/latest"
	fi
	json="$(http_get "$api")" || die "cannot read release metadata from $api"
	tag="$(printf '%s' "$json" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
	[[ -n "$tag" ]] || die "no tag_name in $api"
	url="$(printf '%s' "$json" |
		grep -o '"browser_download_url": *"[^"]*/kuna-'"$tag"'-KunaDecompiler-ghidra_[^"]*\.zip"' |
		head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
	[[ -n "$url" ]] || die "release $tag has no kuna-$tag-KunaDecompiler-ghidra_<ver>.zip asset"
	echo "$tag $url"
}

write_env_file() {
	# $1 = exe path. Sourcing the file makes the same edits as the printed lines.
	mkdir -p "$CACHE_ROOT"
	cat >"$ENV_FILE" <<EOF
# Written by GayHydra scripts/kuna-backend.sh install. Source it to make
# Ghidra spawn kuna's decompiler core; scripts/kuna-backend.sh revert deletes it.
export GHIDRA_DECOMPILER_EXE=$(q "$1")
_kuna_jto=""
for _kuna_tok in \${JAVA_TOOL_OPTIONS:-}; do
	case "\$_kuna_tok" in -Ddecompiler.framing=*) ;; *) _kuna_jto="\${_kuna_jto:+\$_kuna_jto }\$_kuna_tok" ;; esac
done
export JAVA_TOOL_OPTIONS="\${_kuna_jto:+\$_kuna_jto }$FRAMING_OPT"
unset _kuna_jto _kuna_tok
EOF
}

cmd_install() {
	local rel tag url zip dest exe jto
	rel="$(resolve_release)" || exit 1
	tag="${rel%% *}"
	url="${rel#* }"
	dest="$CACHE_ROOT/$tag"
	exe="$dest/KunaDecompiler/os/$PLATFORM/$(exe_name "$PLATFORM")"
	if [[ -f "$exe" ]]; then
		log "kuna $tag already cached at $dest"
	else
		mkdir -p "$dest"
		zip="$dest/$(basename "$url")"
		log "downloading $url"
		http_get "$url" "$zip.part" || die "download failed: $url"
		mv "$zip.part" "$zip"
		unpack_zip "$zip" "$dest" || die "cannot unpack $zip"
		[[ -f "$exe" ]] || die "$(basename "$zip") has no os/$PLATFORM/$(exe_name "$PLATFORM")"
	fi
	chmod +x "$exe"
	if [[ "$PLATFORM" == mac_* ]] && command -v xattr >/dev/null 2>&1; then
		xattr -d com.apple.quarantine "$exe" 2>/dev/null || true
	fi
	write_env_file "$exe"
	jto="$(strip_framing "${JAVA_TOOL_OPTIONS:-}")"
	log "kuna $tag core: $exe"
	log "env file for other launchers (REA, services): $ENV_FILE"
	echo "export GHIDRA_DECOMPILER_EXE=$(q "$exe")"
	echo "export JAVA_TOOL_OPTIONS=$(q "${jto:+$jto }$FRAMING_OPT")"
}

cmd_revert() {
	local jto
	rm -f "$ENV_FILE"
	if [[ "$PURGE" == 1 && -d "$CACHE_ROOT" ]]; then
		find "$CACHE_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'v*' -exec rm -rf {} +
		log "purged cached kuna releases under $CACHE_ROOT"
	fi
	jto="$(strip_framing "${JAVA_TOOL_OPTIONS:-}")"
	log "reverted: Ghidra will spawn its stock decompiler"
	echo "unset GHIDRA_DECOMPILER_EXE"
	if [[ -n "$jto" ]]; then
		echo "export JAVA_TOOL_OPTIONS=$(q "$jto")"
	else
		echo "unset JAVA_TOOL_OPTIONS"
	fi
}

# True when the install's DecompileProcessFactory has the #478 seam. The class
# file's constant pool holds the variable name as plain text.
install_has_seam() {
	local jar="$1/Ghidra/Features/Decompiler/lib/Decompiler.jar"
	local cls
	[[ -f "$jar" ]] || return 2
	cls="$(zip_member "$jar" ghidra/app/decompiler/DecompileProcessFactory.class 2>/dev/null |
		LC_ALL=C tr -c '[:print:]' '\n')" || return 2
	[[ -n "$cls" ]] || return 2
	grep -q GHIDRA_DECOMPILER_EXE <<<"$cls"
}

# Mirror DecompileProcessFactory.resolve(): system property, then environment
# variable, then the stock binary. From a shell, a system property reaches a
# headless run through JAVA_TOOL_OPTIONS.
cmd_status() {
	local prop="" tok src="" path="" framing="auto (fork default)" core seam=0
	for tok in ${JAVA_TOOL_OPTIONS:-}; do
		case "$tok" in
			-Dghidra.decompiler.exe=*) prop="${tok#*=}" ;;
			-Ddecompiler.framing=*) framing="${tok#*=}" ;;
		esac
	done
	if [[ -n "$prop" && -f "$prop" && -x "$prop" ]]; then
		src="system property ghidra.decompiler.exe (via JAVA_TOOL_OPTIONS)"
		path="$prop"
	elif [[ -n "$prop" ]]; then
		log "warning: -Dghidra.decompiler.exe=$prop is not an executable file; Ghidra ignores it"
	fi
	if [[ -z "$path" && -n "${GHIDRA_DECOMPILER_EXE:-}" ]]; then
		if [[ -f "$GHIDRA_DECOMPILER_EXE" && -x "$GHIDRA_DECOMPILER_EXE" ]]; then
			src="environment variable GHIDRA_DECOMPILER_EXE"
			path="$GHIDRA_DECOMPILER_EXE"
		else
			log "warning: GHIDRA_DECOMPILER_EXE=$GHIDRA_DECOMPILER_EXE is not an executable file; Ghidra ignores it"
		fi
	fi
	if [[ -n "${GHIDRA_INSTALL_DIR:-}" ]]; then
		# seam: 0 = has it, 1 = predates it, 2 = could not tell
		if install_has_seam "$GHIDRA_INSTALL_DIR"; then seam=0; else seam=$?; fi
		if [[ "$seam" == 1 && -n "$path" ]]; then
			log "warning: $GHIDRA_INSTALL_DIR predates the DecompileProcessFactory seam (#478) and ignores the setting above"
			path=""
		fi
	fi
	if [[ -n "$path" ]]; then
		case "$(basename "$path")" in
			kuna_ghidra | kuna_ghidra.exe) core="KUNA" ;;
			decompile | decompile.exe) core="STOCK (named explicitly)" ;;
			*) core="CUSTOM" ;;
		esac
	else
		core="STOCK"
		src="stock lookup"
		if [[ -n "${GHIDRA_INSTALL_DIR:-}" ]]; then
			path="$GHIDRA_INSTALL_DIR/Ghidra/Features/Decompiler/os/$PLATFORM/decompile"
			[[ "$PLATFORM" == win_* ]] && path="$path.exe"
		else
			path="Ghidra/Features/Decompiler/os/$PLATFORM/decompile (set GHIDRA_INSTALL_DIR for the full path)"
		fi
	fi
	echo "core:      $core"
	echo "source:    $src"
	echo "path:      $path"
	echo "framing:   $framing"
	echo "platform:  $PLATFORM"
	if [[ -f "$ENV_FILE" ]]; then
		echo "env file:  $ENV_FILE (installed; source it to apply)"
	else
		echo "env file:  none (not installed)"
	fi
	if [[ "$core" == KUNA && "$framing" != v0 ]]; then
		log "warning: kuna core with framing '$framing' hangs at the first decompile; add $FRAMING_OPT to JAVA_TOOL_OPTIONS"
	fi
}

# Run a command with a watchdog, so a framing hang fails instead of blocking.
run_with_timeout() {
	local secs="$1" pid dog rc=0
	shift
	# Job control gives the command its own process group, so the watchdog can
	# stop analyzeHeadless's launch.sh, the JVM and the decompiler core together.
	set -m
	"$@" &
	pid=$!
	set +m
	(
		sleep "$secs"
		if kill -TERM -- "-$pid" 2>/dev/null; then
			log "verify: killed after ${secs}s (a framing hang looks like this)"
			sleep 5
			kill -KILL -- "-$pid" 2>/dev/null || true
		fi
	) &
	dog=$!
	wait "$pid" || rc=$?
	kill "$dog" 2>/dev/null || true
	wait "$dog" 2>/dev/null || true
	return "$rc"
}

cmd_verify() {
	local target="${1:-}" work headless out rc=0
	[[ -n "${GHIDRA_INSTALL_DIR:-}" ]] || usage_die "verify needs GHIDRA_INSTALL_DIR"
	headless="$GHIDRA_INSTALL_DIR/support/analyzeHeadless"
	[[ -x "$headless" ]] || usage_die "no analyzeHeadless under $GHIDRA_INSTALL_DIR/support"
	work="$(mktemp -d "${TMPDIR:-/tmp}/kuna-verify.XXXXXX")"
	# shellcheck disable=SC2064 # expand now: $work is local and gone at EXIT
	trap "rm -rf '$work'" EXIT
	if [[ -z "$target" || "$target" == - ]]; then
		command -v cc >/dev/null 2>&1 || usage_die "no cc to build a sample; pass a binary to verify"
		cat >"$work/sample.c" <<'EOF'
/* kuna_sample is straight-line, so the check exercises the swap alone.
   kuna_loop has control flow: decompiling it shows the edgelabel gap
   documented in docs/decompiler/KUNA_BACKEND.md. */
int kuna_sample(int a, int b) { return a * 3 + (b ^ 0x5a); }
int kuna_loop(int a, int b) { int s = 0; for (int i = 0; i < a; i++) s += i * b; return s; }
int main(int argc, char **argv) { (void)argv; return kuna_sample(argc, 3) + kuna_loop(argc, 2); }
EOF
		cc -O1 -o "$work/sample" "$work/sample.c" || die "cannot build the sample binary"
		set -- "$work/sample" "${2:-kuna_sample}"
	fi
	target="$1"
	shift
	out="$work/headless.log"
	log "verify: analyzeHeadless on $target"
	run_with_timeout "${KUNA_VERIFY_TIMEOUT:-300}" \
		"$headless" "$work" kuna-verify -import "$target" -deleteProject \
		-scriptPath "$SCRIPT_DIR/ghidra_scripts" -postScript KunaMarkerCheck.java "$@" \
		>"$out" 2>&1 || rc=$?
	grep -E 'Decompiler executable taken from|KUNA_' "$out" | sed 's/^.*\(Decompiler executable\)/\1/; s/^.*\(KUNA_\)/\1/' || true
	if grep -q 'KUNA_MARKER_FOUND' "$out"; then
		log "verify: PASS, the kuna core produced the decompilation"
		return 0
	fi
	log "verify: FAIL (analyzeHeadless exit $rc); last lines of its log:"
	tail -20 "$out" >&2
	return 1
}

ACTION=""
ARGS=()
while [[ $# -gt 0 ]]; do
	case "$1" in
		--version) VERSION="${2:?--version needs a value}"; shift 2 ;;
		--platform) PLATFORM="${2:?--platform needs a value}"; shift 2 ;;
		--purge) PURGE=1; shift ;;
		-h | --help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//' >&2; exit 0 ;;
		*)
			if [[ -z "$ACTION" ]]; then ACTION="$1"; else ARGS+=("$1"); fi
			shift ;;
	esac
done
[[ -n "$PLATFORM" ]] || PLATFORM="$(detect_platform)"
case "$PLATFORM" in
	linux_x86_64 | linux_arm_64 | mac_x86_64 | mac_arm_64 | win_x86_64) ;;
	*) usage_die "unknown platform '$PLATFORM'" ;;
esac

case "$ACTION" in
	install) cmd_install ;;
	revert) cmd_revert ;;
	status) cmd_status ;;
	verify) cmd_verify "${ARGS[@]+"${ARGS[@]}"}" ;;
	env-file) echo "$ENV_FILE" ;;
	*) usage_die "usage: $0 [--version v] [--platform p] install | revert [--purge] | status | verify [binary|- [function]] | env-file" ;;
esac
