#!/usr/bin/env bash
set -euo pipefail

if (( BASH_VERSINFO[0] < 5 )); then
    printf 'Error: bash 5+ required (found %s)\n' "$BASH_VERSION" >&2
    exit 1
fi

USE_CLIPBOARD=false
EXTRACT_SYSTEM_OUT=false
EXTRACT_SYSTEM_ERR=false
EXTRACT_ALL=false

usage() {
    cat <<'EOF'
Usage: extract-surefire-failure.sh [--clipboard] [--system-out] [--system-err] [--all] <report.xml> [index]

Extract failure/error messages from a Maven Surefire XML report.

Arguments:
  report.xml   Path to the surefire XML report file (TEST-*.xml)
  index        1-based index of the failure to extract (optional)

Options:
  --clipboard   Copy output to clipboard instead of printing to stdout
  --system-out  Extract system-out of the selected (or sole) failing testcase
  --system-err  Extract system-err of the selected (or sole) failing testcase
  --all         Extract from all relevant testcases with name headers.
                Without stream flags: all failure/error messages (failing testcases only).
                With --system-out/--system-err: streams from all testcases, skipping empty.
  --help        Show this help

If the report contains exactly one failure or error, its stack trace is printed
(or copied). If there are multiple, a numbered list is shown. Pass an index to
select a specific entry.

When --system-out or --system-err is given, only the requested stream(s) are
extracted instead of the failure/error text.
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

extract_stream() {
    local file="$1" index="$2" element="$3"
    local content
    content=$(xmllint --xpath "string(//testcase[failure or error][$index]/$element)" \
        "$file" 2>/dev/null || true)
    printf '%s' "$content"
}

extract_all() {
    local file="$1"
    local output="" separator=""

    if [[ "$EXTRACT_SYSTEM_OUT" == false && "$EXTRACT_SYSTEM_ERR" == false ]]; then
        local -a names=()
        mapfile -t names < <(
            xmllint --xpath "//testcase[failure or error]/@name" "$file" 2>/dev/null \
            | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
        )
        local total=${#names[@]}
        if (( total == 0 )); then
            printf 'No failures or errors found in %s\n' "$file" >&2
            return
        fi
        local i msg
        for (( i = 1; i <= total; i++ )); do
            msg=$(extract_message "$file" "$i")
            output+="${separator}=== ${names[$((i-1))]} ==="$'\n'"$msg"
            separator=$'\n\n'
        done
    else
        local -a elements=()
        [[ "$EXTRACT_SYSTEM_OUT" == true ]] && elements+=("system-out")
        [[ "$EXTRACT_SYSTEM_ERR" == true ]] && elements+=("system-err")
        local -a all_names=()
        mapfile -t all_names < <(
            xmllint --xpath "//testcase/@name" "$file" 2>/dev/null \
            | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
        )
        local total=${#all_names[@]}
        local i elem content testcase_content subsep
        for (( i = 1; i <= total; i++ )); do
            local name="${all_names[$((i-1))]}"
            testcase_content="" subsep=""
            for elem in "${elements[@]}"; do
                content=$(xmllint --xpath "string(//testcase[$i]/$elem)" \
                    "$file" 2>/dev/null || true)
                if [[ -n "$content" ]]; then
                    if (( ${#elements[@]} > 1 )); then
                        testcase_content+="${subsep}--- $elem ---"$'\n'"$content"
                        subsep=$'\n'
                    else
                        testcase_content+="$content"
                    fi
                fi
            done
            if [[ -n "$testcase_content" ]]; then
                output+="${separator}=== $name ==="$'\n'"$testcase_content"
                separator=$'\n\n'
            fi
        done
        if [[ -z "$output" ]]; then
            printf 'No captured stream content found in %s\n' "$file" >&2
            return
        fi
    fi

    copy_or_print "$output"
}

do_extract() {
    local file="$1" index="$2"
    if [[ "$EXTRACT_SYSTEM_OUT" == false && "$EXTRACT_SYSTEM_ERR" == false ]]; then
        copy_or_print "$(extract_message "$file" "$index")"
        return
    fi
    local -a elements=()
    [[ "$EXTRACT_SYSTEM_OUT" == true ]] && elements+=("system-out")
    [[ "$EXTRACT_SYSTEM_ERR" == true ]] && elements+=("system-err")
    local -a results=()
    local elem content
    for elem in "${elements[@]}"; do
        content=$(extract_stream "$file" "$index" "$elem")
        if [[ -z "$content" ]]; then
            printf 'Note: no %s captured for testcase [%d]\n' "$elem" "$index" >&2
        fi
        results+=("$content")
    done
    if (( ${#elements[@]} == 1 )); then
        copy_or_print "${results[0]}"
    else
        local combined="" sep="" i
        for (( i = 0; i < ${#elements[@]}; i++ )); do
            if [[ -n "${results[$i]}" ]]; then
                combined+="${sep}=== ${elements[$i]} ==="$'\n'"${results[$i]}"
                sep=$'\n'
            fi
        done
        if [[ -n "$combined" ]]; then
            copy_or_print "$combined"
        fi
    fi
}

main() {
    local -a _positional=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --clipboard)   USE_CLIPBOARD=true;        shift ;;
            --system-out)  EXTRACT_SYSTEM_OUT=true;   shift ;;
            --system-err)  EXTRACT_SYSTEM_ERR=true;   shift ;;
            --all)         EXTRACT_ALL=true;           shift ;;
            --help)        usage; exit 0 ;;
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

    if [[ "$EXTRACT_ALL" == true ]]; then
        if [[ -n "$index" ]]; then
            printf 'Error: --all and an index are mutually exclusive\n' >&2
            exit 1
        fi
        extract_all "$file"
        return
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
        do_extract "$file" "$index"
        return
    fi

    if (( count == 1 )); then
        do_extract "$file" 1
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
