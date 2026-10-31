#!/usr/bin/env bash
# Lab contract checks the Antora build cannot do. Run after `npm run build`.
# Usage: bash scripts/check-lab.sh [--draft]
# --draft skips the "no unverified steps" check, for use before the live verification pass.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

draft=false
[[ "${1:-}" == "--draft" ]] && draft=true
pages=content/modules/ROOT/pages
nav=content/modules/ROOT/nav.adoc
failed=0
fail() { printf 'check-lab: %s\n' "$*" >&2; failed=1; }

if grep -rIn --exclude-dir={node_modules,www,.git,.cache,evidence,.xreview} \
     --exclude=check-lab.sh --exclude=package-lock.json 'CHANGE-ME' . >&2; then
  fail "scaffold placeholders remain (CHANGE-ME)"
fi

for page in "$pages"/*.adoc; do
  grep -Fq "xref:$(basename "$page")[" "$nav" || fail "$page is not in nav.adoc"
done

for page in "$pages"/*-module-*.adoc; do
  [[ -e "$page" ]] || { fail "no module pages (*-module-*.adoc) in $pages"; break; }
  for section in '== Learning objectives' '== Evidence checkpoint' '.Answer key'; do
    grep -Fxq "$section" "$page" || fail "$page is missing '$section'"
  done
done

# Every participant command (role="execute") needs an Expected result before the next block or heading.
for page in "$pages"/*.adoc; do
  awk -v f="$page" '
    pending && (/^\[source/ || /^=+ /) { print f ":" line ": execute block without an Expected result"; bad = 1; pending = 0 }
    /^\[source.*role="?execute"?/ { pending = 1; line = FNR; next }
    pending && /^Expected result/ { pending = 0 }
    END { if (pending) { print f ":" line ": execute block without an Expected result"; bad = 1 }; exit bad }
  ' "$page" >&2 || failed=1
done

# Argo CD consumes these charts straight from git, so they must render. Each child from this repo is
# rendered with exactly the values its parent passes, and every Namespace it creates must carry the
# lab's part-of label, which make bootstrap's ownership guard relies on.
[[ -d automation/argocd ]] || fail "automation/argocd (the parent chart) is missing"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
if rendered=$(helm template lab automation/argocd --set deployer.domain=apps.example.com 2>&1); then
  awk -v dir="$tmp" '
    function flush() {
      if (path != "") { n++; printf "%s", vals > (dir "/" n ".values"); print path > (dir "/" n ".path"); close(dir "/" n ".values"); close(dir "/" n ".path") }
      path = ""; vals = ""; vo = 0
    }
    /^---/ { flush(); next }
    vo { if ($0 ~ /^[[:space:]]*$/) next
         if (match($0, /[^ ]/) <= ind) vo = 0; else { vals = vals substr($0, ind + 2) "\n"; next } }
    $1 == "path:" && $2 ~ /^automation\// { path = $2 }
    $1 == "valuesObject:" { vo = 1; ind = match($0, /[^ ]/) }
    END { flush() }
  ' <<<"$rendered"
  for pf in "$tmp"/*.path; do
    [[ -e $pf ]] || break
    path=$(<"$pf")
    [[ -d $path ]] || { fail "a child Application points at $path, which does not exist"; continue; }
    out=$(helm template lab "$path" -f "${pf%.path}.values" 2>&1) || { fail "$path does not render with the values its parent passes"; continue; }
    awk '/^---/ { if (ns && !own) bad = 1; ns = 0; own = 0 }
         /^kind: Namespace$/ { ns = 1 }
         /app\.kubernetes\.io\/part-of: lab$/ { own = 1 }
         END { if (ns && !own) bad = 1; exit bad }' <<<"$out" ||
      fail "$path: a Namespace lacks app.kubernetes.io/part-of (the parent must pass partOf)"
  done
else
  printf '%s\n' "$rendered" >&2
  fail "automation/argocd does not render"
fi

# Every exercise, GUI-only ones included, states an Expected result.
for page in "$pages"/*.adoc; do
  awk -v f="$page" '
    function close_ex() { if (ex && !seen) { print f ":" ex_line ": exercise without an Expected result"; bad = 1 } ex = 0 }
    /^== / || /^= / { close_ex() }  # === subsections stay inside their exercise
    /^== Exercise/ { ex = 1; seen = 0; ex_line = FNR }
    ex && /^Expected result/ { seen = 1 }
    END { close_ex(); exit bad }
  ' "$page" >&2 || failed=1
done

# Every child Application sourced from this repo must point at a directory that exists.
if rendered=$(helm template lab automation/argocd --set deployer.domain=apps.example.com 2>/dev/null); then
  for path in $(awk '$1 == "path:" && $2 ~ /^automation\// {print $2}' <<<"$rendered"); do
    [[ -d $path ]] || fail "a child Application points at $path, which does not exist"
  done
fi

# Participants paste collapsible assertions into their own shell: one that can `exit` must run in ( ... ).
for page in "$pages"/*.adoc; do
  awk -v f="$page" '
    /^\[%collapsible\]/ { col = 1; next }
    col && /^====$/ { inex = !inex; if (!inex) col = 0; next }
    inex && /^----$/ { if (!inl) { inl = 1; first = ""; ln = FNR; ex = 0; next }
                       inl = 0; if (ex && first != "(") { print f ":" ln ": assertion can exit the participant shell; wrap it in ( ... )"; bad = 1 }; next }
    inl { if (first == "") first = $0; if ($0 ~ /(^|[^A-Za-z_])exit([^A-Za-z_]|$)/) ex = 1 }
    END { exit bad }
  ' "$page" >&2 || failed=1
done

# A defined attribute still shown as {name} in the HTML was never substituted:
# its block lacks subs="attributes+" (Asciidoctor does not warn about that).
if [[ ! -d www ]]; then
  fail "no www/ to inspect; run npm run build first"
else
  names=$(awk '/^  attributes:/ {a = 1; next} a && /^    [A-Za-z0-9_-]+:/ {sub(/^ +/, ""); sub(/:.*/, ""); print}' \
    content/antora.yml | paste -sd'|' -)
  if [[ -n "$names" ]] && grep -rnoE "\{($names)\}" www --include='*.html' >&2; then
    fail "attributes rendered literally; add subs=\"attributes+\" to those blocks"
  fi
  # Asciidoctor strips a backslash only before {word}; anywhere else it reaches the participant.
  if grep -rnoF '\{' www --include='*.html' >&2; then
    fail "a backslash before a brace survived into the HTML; escape only {word} forms"
  fi
fi

# Screenshots are live-captured evidence; each needs alt text for screen readers: the first
# positional attribute, or alt=, and not blank.
for page in "$pages"/*.adoc; do
  awk -v f="$page" '
    /^image::[^[]+\[/ {
      inner = $0; sub(/^image::[^[]*\[/, "", inner); sub(/\][^]]*$/, "", inner)
      n = split(inner, a, ","); first = a[1]; gsub(/^[ \t]+|[ \t]+$/, "", first)
      alt = (first ~ /=/) ? "" : first
      for (i = 1; i <= n; i++) { it = a[i]; sub(/^[ \t]+/, "", it); if (it ~ /^alt[ \t]*=/) { sub(/^alt[ \t]*=/, "", it); alt = it } }
      gsub(/["\047[:space:]]/, "", alt)
      if (alt == "") { print f ":" FNR ": image without alt text: image::file.png[What the screenshot shows]"; bad = 1 }
    }
    END { exit bad }
  ' "$page" >&2 || failed=1
done

# The field-content Showroom builder (quay.io/rhpds/antora) has no mermaid extension.
if grep -n '^\[mermaid' "$pages"/*.adoc >&2; then
  fail "[mermaid] blocks do not render in Showroom; use a table or numbered list"
fi

if ! $draft && grep -n '^// UNVERIFIED' "$pages"/*.adoc >&2; then
  fail "steps not yet verified on a live cluster (// UNVERIFIED)"
fi

[[ $failed -eq 0 ]] && echo "check-lab: OK$($draft && echo ' (draft)')"
exit "$failed"
