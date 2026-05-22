#!/usr/bin/env bash
# i18n sweep — emit candidate-literal TSV for the Docking framework.
#
# Each row: file<TAB>line<TAB>raw_literal<TAB>suggested_key
#
# Key naming convention (matches docs/i18n/DEVELOPER-GUIDE.md):
#   docking.<class-slug>.<element>.<slug>
# where <class-slug> = filename without .java, snake_cased
#       <element>    = label|button|tooltip|title|menu|dialog
#       <slug>       = lowercased literal with non-alphanumerics → _
#                      truncated to ~40 chars at a word boundary
#
# Patterns recognized:
#   new J{Label,Button,CheckBox,RadioButton,MenuItem,Menu}("Lit")
#   new G{Label,Button,CheckBox,RadioButton}("Lit")
#   setToolTipText("Lit")
#   setTitle("Lit") | super("Lit", ...)
#
# After human review of the TSV, run scripts/i18n-apply.py < <reviewed.tsv>
# to rewrite literals to I18n.tr(key) and append keys to messages.properties.

set -euo pipefail

ROOT="${1:-Ghidra/Framework/Docking/src/main/java/docking}"
MODULE_PREFIX="docking"

if [[ ! -d "$ROOT" ]]; then
	echo "scan root not found: $ROOT" >&2
	exit 2
fi

slugify() {
	local s="$1"
	s="${s,,}"
	s="${s//\{[0-9]*\}/_arg_}"
	s=$(echo "$s" | tr -c 'a-z0-9' '_' | tr -s '_' | sed 's/^_//; s/_$//')
	if (( ${#s} > 40 )); then
		s="${s:0:40}"
		s="${s%_*}"
	fi
	echo "$s"
}

class_slug() {
	local base="${1##*/}"
	base="${base%.java}"
	echo "$base" | sed 's/\([a-z0-9]\)\([A-Z]\)/\1_\2/g' | tr '[:upper:]' '[:lower:]'
}

emit_row() {
	local file="$1" line="$2" elem="$3" literal="$4"
	local class
	class=$(class_slug "$file")
	local slug
	slug=$(slugify "$literal")
	if [[ -z "$slug" ]]; then
		return
	fi
	local key="${MODULE_PREFIX}.${class}.${elem}.${slug}"
	printf '%s\t%s\t%s\t%s\n' "$file" "$line" "$literal" "$key"
}

scan_pattern() {
	local element="$1" regex="$2"
	while IFS= read -r hit; do
		local file="${hit%%:*}"
		local rest="${hit#*:}"
		local line="${rest%%:*}"
		local content="${rest#*:}"
		local lit
		lit=$(echo "$content" | grep -oE '"[A-Z0-9][^"]+"' | head -1 | sed 's/^"//; s/"$//')
		[[ -z "$lit" ]] && continue
		emit_row "$file" "$line" "$element" "$lit"
	done < <(grep -rnE "$regex" "$ROOT" --include='*.java' 2>/dev/null || true)
}

scan_pattern label 'new [JG](Label|Button|CheckBox|RadioButton|MenuItem|Menu)\("[A-Z0-9][^"]+"'
scan_pattern tooltip 'setToolTipText\("[A-Z0-9][^"]+"\)'
scan_pattern title 'setTitle\("[A-Z0-9][^"]+"\)'
scan_pattern title 'super\("[A-Z0-9][^"]+",'
