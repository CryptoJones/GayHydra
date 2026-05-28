#!/usr/bin/env bash
# local-precheck.sh — fast pre-push gate for decompiler C++ changes.
#
# Mirrors the build step in .github/workflows/decompiler-cpp-tests.yml so
# header-touching PRs are caught before they reach master. The recent
# hotfix wave (#127 .get() on unique_ptr arg, #129 missing using-decl,
# #130 missing <memory> include) were all build-only failures that
# `make decomp_test_dbg` would have surfaced in under two minutes.
#
# Usage:
#   scripts/local-precheck.sh           # build-only (default, fast)
#   scripts/local-precheck.sh --full    # also run unittests + datatests
#   scripts/local-precheck.sh --force   # build even with no decomp diff
#   scripts/local-precheck.sh --clean   # `make clean` first (use after a
#                                       # sanitizer build leaves stale .o
#                                       # files that fail to link)
#
# Exit codes:
#   0  pass (or skipped — no decompiler changes in range)
#   1  build or test failure
#   2  precondition failure (missing toolchain, wrong cwd)
#
# Diff range: origin/master..HEAD if the upstream commit is reachable,
# otherwise the working tree vs HEAD (so the script is useful before
# the first commit too).

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$REPO_ROOT" ]]; then
	echo "precheck: not inside a git repo" >&2
	exit 2
fi
cd "$REPO_ROOT"

CPP_DIR="Ghidra/Features/Decompiler/src/decompile/cpp"
DECOMP_PATH_PREFIX="Ghidra/Features/Decompiler/src/decompile/"

MODE_FULL=0
FORCE=0
CLEAN=0
for arg in "$@"; do
	case "$arg" in
		--full)  MODE_FULL=1 ;;
		--force) FORCE=1 ;;
		--clean) CLEAN=1 ;;
		-h|--help)
			sed -n '2,25p' "$0"
			exit 0
			;;
		*)
			echo "precheck: unknown arg: $arg" >&2
			exit 2
			;;
	esac
done

log() { printf '[precheck %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# --- scope detection -------------------------------------------------------
diff_range=""
if git rev-parse --verify --quiet origin/master >/dev/null; then
	diff_range="origin/master..HEAD"
fi

changed=""
if [[ -n "$diff_range" ]]; then
	changed=$(git diff --name-only "$diff_range" -- "$DECOMP_PATH_PREFIX" || true)
fi
# Also pick up uncommitted edits so the script is useful pre-commit.
changed+=$'\n'"$(git diff --name-only HEAD -- "$DECOMP_PATH_PREFIX" 2>/dev/null || true)"
changed=$(printf '%s\n' "$changed" | sed '/^$/d' | sort -u)

if [[ -z "$changed" && $FORCE -eq 0 ]]; then
	log "no decompiler C++ changes in ${diff_range:-HEAD} — skip"
	exit 0
fi

if [[ -n "$changed" ]]; then
	log "decompiler files in range:"
	printf '  %s\n' $changed
else
	log "--force: building regardless of diff scope"
fi

# --- toolchain checks ------------------------------------------------------
for tool in g++ make bison flex; do
	if ! command -v "$tool" >/dev/null 2>&1; then
		echo "precheck: missing required tool: $tool" >&2
		echo "  install: sudo apt-get install -y bison flex g++ make binutils-dev libiberty-dev" >&2
		exit 2
	fi
done

if [[ ! -d "$CPP_DIR" ]]; then
	echo "precheck: $CPP_DIR not found — wrong repo root?" >&2
	exit 2
fi

# --- build ------------------------------------------------------------------
jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
if [[ $CLEAN -eq 1 ]]; then
	log "make clean (--clean requested)"
	make -C "$CPP_DIR" clean
fi
log "make decomp_test_dbg -j${jobs}"
build_start=$SECONDS
if ! make -C "$CPP_DIR" -j"$jobs" decomp_test_dbg; then
	log "BUILD FAILED after $((SECONDS - build_start))s"
	exit 1
fi
log "build OK in $((SECONDS - build_start))s"

# --- optional test runs ----------------------------------------------------
if [[ $MODE_FULL -eq 1 ]]; then
	# decomp_test_dbg's startDecompilerLibrary() loads real .sla files at
	# startup. If none exist, tests fail with "Could not find .sla file
	# for x86:LE:64:...". Compile them once via gradle if missing.
	sla_count=$(find Ghidra/Processors -name '*.sla' 2>/dev/null | wc -l)
	if [[ "$sla_count" -eq 0 ]]; then
		log ".sla files missing — running gradle allSleighCompile (one-time, slow)"
		if ! ./gradlew allSleighCompile --parallel; then
			log "SLEIGH compile failed"
			exit 1
		fi
	fi

	log "running unittests"
	if ! (cd "$CPP_DIR" && ./decomp_test_dbg unittests); then
		log "UNITTESTS FAILED"
		exit 1
	fi

	log "running datatests"
	if ! (cd "$CPP_DIR" && ./decomp_test_dbg datatests); then
		log "DATATESTS FAILED"
		exit 1
	fi
	log "all tests passed"
fi

log "OK"
exit 0
