#!/usr/bin/env bash
set -euo pipefail

if (( BASH_VERSINFO[0] < 5 )); then
    printf 'Error: bash 5+ required (found %s)\n' "$BASH_VERSION" >&2
    exit 1
fi

USE_CLIPBOARD=false

usage() {
    cat <<'EOF'
Usage: extract-surefire-failure.sh [--clipboard] <report.xml> [index]

Extract failure/error messages from a Maven Surefire XML report.

Arguments:
  report.xml   Path to the surefire XML report file (TEST-*.xml)
  index        1-based index of the failure to extract (optional)

Options:
  --clipboard  Copy output to clipboard instead of printing to stdout
  --help       Show this help

If the report contains exactly one failure or error, its stack trace is printed
(or copied). If there are multiple, a numbered list is shown. Pass an index to
select a specific entry.
EOF
}

copy_or_print() {
    local text="$1"
    if [[ "$USE_CLIPBOARD" == true ]]; then
        if command -v pbcopy &>/dev/null; then
            printf '%s' "$text" | pbcopy
            printf 'Copied to clipboard.\n' >&2
        elif command -v xclip &>/dev/null; then
            printf '%s' "$text" | xclip -selection clipboard
            printf 'Copied to clipboard.\n' >&2
        elif command -v xsel &>/dev/null; then
            printf '%s' "$text" | xsel --clipboard --input
            printf 'Copied to clipboard.\n' >&2
        else
            printf 'Warning: no clipboard tool found (pbcopy/xclip/xsel); printing to stdout.\n' >&2
            printf '%s\n' "$text"
        fi
    else
        printf '%s\n' "$text"
    fi
}

extract_message() {
    local file="$1" index="$2"
    local msg
    msg=$(xmllint --xpath "string(//testcase[failure or error][$index]/failure)" "$file" 2>/dev/null || true)
    if [[ -z "$msg" ]]; then
        msg=$(xmllint --xpath "string(//testcase[failure or error][$index]/error)" "$file" 2>/dev/null || true)
    fi
    printf '%s' "$msg"
}

main() {
    local -a _positional=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --clipboard) USE_CLIPBOARD=true; shift ;;
            --help)      usage; exit 0 ;;
            --*) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 1 ;;
            *) _positional+=("$1"); shift ;;
        esac
    done

    if [[ ${#_positional[@]} -eq 0 ]]; then
        usage
        exit 0
    fi

    local file="${_positional[0]}"
    local index="${_positional[1]:-}"

    if [[ ! -f "$file" ]]; then
        printf 'Error: file not found: %s\n' "$file" >&2
        exit 1
    fi

    local -a _names=() _classnames=()
    mapfile -t _names < <(
        xmllint --xpath "//testcase[failure or error]/@name" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )
    mapfile -t _classnames < <(
        xmllint --xpath "//testcase[failure or error]/@classname" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )

    local count=${#_names[@]}

    if (( count == 0 )); then
        printf 'No failures or errors found in %s\n' "$file"
        exit 0
    fi

    if [[ -n "$index" ]]; then
        if ! [[ "$index" =~ ^[0-9]+$ ]] || (( index < 1 || index > count )); then
            printf 'Error: index %s out of range (1-%d)\n' "$index" "$count" >&2
            exit 1
        fi
        local msg
        msg=$(extract_message "$file" "$index")
        copy_or_print "$msg"
        return
    fi

    if (( count == 1 )); then
        local msg
        msg=$(extract_message "$file" 1)
        copy_or_print "$msg"
        return
    fi

    local -A _fail_set=()
    local _fname
    while IFS= read -r _fname; do _fail_set[$_fname]=1; done < <(
        xmllint --xpath "//testcase[failure]/@name" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )

    printf 'Found %d failures/errors:\n' "$count"
    local i
    for (( i = 1; i <= count; i++ )); do
        local name="${_names[$((i-1))]}"
        local classname="${_classnames[$((i-1))]}"
        local kind="error"
        [[ -v _fail_set[$name] ]] && kind="failure"
        printf '  [%d] %s#%s  (%s)\n' "$i" "$classname" "$name" "$kind"
    done
    printf '\nRe-run with an index to extract, e.g.:\n'
    printf '  %s %s 1\n' "$0" "$file"
}

if [[ -z "${BATS_TEST_FILENAME:-}" ]]; then
    main "$@"
fi
