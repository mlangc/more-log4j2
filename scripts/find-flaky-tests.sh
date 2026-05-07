#!/usr/bin/env bash
set -euo pipefail

if (( BASH_VERSINFO[0] < 5 )); then
    printf 'Error: bash 5+ required (found %s)\n' "$BASH_VERSION" >&2
    exit 1
fi

ITERATIONS=""
TEST_FILTER=""
MODULE=""
OUTPUT_FILE=""
REPORT_MODE=false
MAVEN_CMD=""
REPO_ROOT="$PWD"

usage() {
    cat <<'EOF'
Usage: find-flaky-tests.sh [OPTIONS]

Run mode (default):
  --iterations N    Stop after N iterations (default: run until Ctrl-C)
  --test TEST       Maven -Dtest value (e.g. ThrottlingFilterTest)
  --module MODULE   Maven -pl value (e.g. core; default: all modules)
  --output FILE     Results file (default: flaky-results-<timestamp>.tsv)
  --root DIR        Repository root (default: current directory)
  --help

Report mode:
  --report FILE         Print summary from an existing results file
EOF
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --iterations) ITERATIONS="$2"; shift 2 ;;
            --test)       TEST_FILTER="$2"; shift 2 ;;
            --module)     MODULE="$2"; shift 2 ;;
            --output)     OUTPUT_FILE="$2"; shift 2 ;;
            --root)       REPO_ROOT="$(cd "$2" && pwd)"; shift 2 ;;
            --report)     REPORT_MODE=true; OUTPUT_FILE="$2"; shift 2 ;;
            --help)       usage; exit 0 ;;
            *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 1 ;;
        esac
    done

    if [[ "$REPORT_MODE" == false && -z "$OUTPUT_FILE" ]]; then
        OUTPUT_FILE="flaky-results-$(date +%Y%m%d-%H%M%S).tsv"
    fi
}

build_maven_cmd() {
    local cmd="${REPO_ROOT}/mvnw"
    if [[ -n "$MODULE" ]]; then
        cmd+=" -pl $MODULE -am"
    fi
    if [[ -n "$TEST_FILTER" ]]; then
        cmd+=" -Dtest=$TEST_FILTER -Dsurefire.failIfNoSpecifiedTests=false"
    fi
    cmd+=" -B test"
    echo "$cmd"
}

init_results_file() {
    local file="$1"
    if [[ ! -f "$file" ]]; then
        printf '# started=%s\n' "$(date -u +%FT%TZ)" > "$file"
        printf '# columns: iter\ttest_key\tstatus\n' >> "$file"
    fi
}

# Parses one surefire XML file and emits "iter<TAB>classname#name<TAB>status" lines.
parse_surefire_xml() {
    local file="$1" iter="$2"

    # xmllint --xpath outputs: ` attr="val1" attr="val2" ...`
    # awk -F'"' extracts every even-indexed field (the values between quotes).
    mapfile -t _classnames < <(
        xmllint --xpath "//testcase/@classname" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )
    mapfile -t _names < <(
        xmllint --xpath "//testcase/@name" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )

    # Build lookup sets for failed/errored/skipped test names.
    local -A _fail_set=() _error_set=() _skip_set=()
    local name
    while IFS= read -r name; do _fail_set[$name]=1; done < <(
        xmllint --xpath "//testcase[failure]/@name" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )
    while IFS= read -r name; do _error_set[$name]=1; done < <(
        xmllint --xpath "//testcase[error]/@name" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )
    while IFS= read -r name; do _skip_set[$name]=1; done < <(
        xmllint --xpath "//testcase[skipped]/@name" "$file" 2>/dev/null \
        | awk -F'"' '{for(i=2;i<=NF;i+=2) print $i}' || true
    )

    local i
    for i in "${!_names[@]}"; do
        local key="${_classnames[$i]}#${_names[$i]}"
        local status="pass"
        [[ -v _fail_set[${_names[$i]}]  ]] && status="fail"
        [[ -v _error_set[${_names[$i]}] ]] && status="error"
        [[ -v _skip_set[${_names[$i]}]  ]] && status="skip"
        printf '%s\t%s\t%s\n' "$iter" "$key" "$status"
    done
}

copy_failure_reports() {
    local iter="$1" sentinel="${2:-}"
    local run_name; run_name="$(basename "${OUTPUT_FILE%.tsv}")"
    local failures_base="$REPO_ROOT/target/find-flaky-tests/failures/$run_name"
    local copied=0

    local -a find_cmd=(find "$REPO_ROOT" -path '*/target/surefire-reports/TEST-*.xml')
    [[ -n "$sentinel" ]] && find_cmd+=(-newer "$sentinel")

    while IFS= read -r -d '' xml_file; do
        if grep -qE '<(failure|error)' "$xml_file"; then
            local xml_base; xml_base="$(basename "$xml_file" .xml)"
            local xml_rel_dir; xml_rel_dir="$(dirname "${xml_file#$REPO_ROOT/}")"
            local dest_name="${xml_base}-iter-$(printf '%03d' "$iter").xml"
            local dest="$failures_base/$xml_rel_dir/$dest_name"
            mkdir -p "$(dirname "$dest")"
            cp "$xml_file" "$dest"
            (( ++copied ))
        fi
    done < <("${find_cmd[@]}" -print0 2>/dev/null)

    if [[ $copied -gt 0 ]]; then
        printf 'Failure reports (%d) saved to: %s\n' "$copied" "$failures_base"
    fi
}

# Parses surefire XML reports under REPO_ROOT written after the sentinel file (if given).
# A sentinel file is used instead of a captured `date` timestamp because `find -newer <file>`
# is the only portable time-filter option: BSD find (macOS) lacks GNU's -newermt flag, and
# `stat` mtime format also differs between platforms.
parse_all_reports() {
    local iter="$1" sentinel="${2:-}"
    local found=0
    local -a find_cmd=(find "$REPO_ROOT" -path '*/target/surefire-reports/TEST-*.xml')
    [[ -n "$sentinel" ]] && find_cmd+=(-newer "$sentinel")
    while IFS= read -r -d '' xml_file; do
        parse_surefire_xml "$xml_file" "$iter"
        found=1
    done < <("${find_cmd[@]}" -print0 2>/dev/null)

    if [[ $found -eq 0 ]]; then
        printf 'Warning: no surefire XML reports found under %s\n' "$REPO_ROOT" >&2
    fi
}

print_summary() {
    local file="$1"
    local from_iter="${2:-0}"

    if [[ ! -f "$file" ]]; then
        printf 'Error: results file not found: %s\n' "$file" >&2
        exit 1
    fi

    awk -F'\t' -v from_iter="$from_iter" '
    /^#/ {
        if ($0 ~ /^# started=/) started = substr($0, 11)
        next
    }
    NF != 3 {
        printf "Warning: malformed line %d, rendering partial results\n", NR > "/dev/stderr"
        exit
    }
    {
        iter = $1 + 0; key = $2; status = $3
        if (from_iter > 0 && iter <= from_iter) next
        if (iter > max_iter) max_iter = iter
        if      (status == "pass")  pass[key]++
        else if (status == "fail")  fail[key]++
        else if (status == "error") err[key]++
        else if (status == "skip")  skip[key]++
        seen[key] = 1
    }
    END {
        title = (from_iter > 0) ? "=== Flaky Test Detection Summary (This Run) ===" \
                                 : "=== Flaky Test Detection Summary ==="
        printf "\n%s\n", title
        printf "Results file : %s\n", FILENAME
        if (from_iter > 0)
            printf "Iterations   : %d (iter %d to %d)\n\n", max_iter - from_iter, from_iter + 1, max_iter
        else
            printf "Iterations   : %d\n\n", max_iter

        n_flaky = 0; n_always_fail = 0; n_ok = 0
        for (key in seen) {
            p = pass[key]+0; f = fail[key]+0; e = err[key]+0
            if ((f + e) > 0 && p > 0)       flaky_keys[n_flaky++] = key
            else if ((f + e) > 0 && p == 0) always_fail_keys[n_always_fail++] = key
            else                             n_ok++
        }

        for (i = 0; i < n_flaky - 1; i++)
            for (j = i + 1; j < n_flaky; j++) {
                ki = flaky_keys[i]; kj = flaky_keys[j]
                ti = pass[ki]+0 + fail[ki]+0 + err[ki]+0 + skip[ki]+0
                tj = pass[kj]+0 + fail[kj]+0 + err[kj]+0 + skip[kj]+0
                ri = (fail[ki]+0 + err[ki]+0) / ti
                rj = (fail[kj]+0 + err[kj]+0) / tj
                if (rj > ri) { tmp = flaky_keys[i]; flaky_keys[i] = flaky_keys[j]; flaky_keys[j] = tmp }
            }

        print "FLAKY (pass sometimes, fail sometimes)"
        if (n_flaky == 0) {
            print "  (none)"
        } else {
            for (i = 0; i < n_flaky; i++) {
                key = flaky_keys[i]
                p = pass[key]+0; f = fail[key]+0; e = err[key]+0; s = skip[key]+0
                total = p + f + e + s
                pct = int(p * 100 / total)
                printf "  %3d%% pass  %-70s  pass=%-4d fail=%-4d error=%d\n", pct, key, p, f, e
            }
        }

        printf "\nALWAYS FAILING\n"
        if (n_always_fail == 0) {
            print "  (none)"
        } else {
            for (i = 0; i < n_always_fail; i++) {
                key = always_fail_keys[i]
                printf "  %-70s  fail=%d error=%d\n", key, fail[key]+0, err[key]+0
            }
        }

        printf "\nALWAYS PASSING / SKIPPED : %d tests\n", n_ok
    }
    ' "$file"
}

print_delta() {
    local file="$1"
    local from_iter="$2"

    awk -F'\t' -v from_iter="$from_iter" '
    /^#/ { next }
    NF != 3 { next }
    {
        iter = $1 + 0; key = $2; status = $3
        seen[key] = 1
        if      (status == "pass")  total_pass[key]++
        else if (status == "fail")  total_fail[key]++
        else if (status == "error") total_err[key]++
        if (iter > from_iter) {
            if      (status == "pass")  new_pass[key]++
            else if (status == "fail")  new_fail[key]++
            else if (status == "error") new_err[key]++
        }
    }
    END {
        printf "\n=== Delta Summary (Effect of This Run) ===\n\n"

        n_newly_affected = 0; n_partly_resolved = 0; n_rate_changed = 0; n_unchanged = 0

        for (key in seen) {
            ap = total_pass[key]+0; af = total_fail[key]+0; ae = total_err[key]+0
            np = new_pass[key]+0;   nf = new_fail[key]+0;   ne = new_err[key]+0
            bp = ap - np;           bf = af - nf;            be = ae - ne

            after_total  = ap + af + ae
            before_total = bp + bf + be

            if      (before_total == 0)  before_cat = -1
            else if ((bf + be) == 0)     before_cat = 0
            else if (bp > 0)             before_cat = 1
            else                         before_cat = 2

            if      (after_total == 0)   after_cat = 0
            else if ((af + ae) == 0)     after_cat = 0
            else if (ap > 0)             after_cat = 1
            else                         after_cat = 2

            before_fail_rate = (before_total > 0) ? int((bf + be) * 100 / before_total) : 0
            after_fail_rate  = (after_total  > 0) ? int((af + ae) * 100 / after_total)  : 0

            if ((before_cat == 0 || before_cat == -1) && (after_cat == 1 || after_cat == 2)) {
                newly_affected_cat[key]  = before_cat
                newly_affected[n_newly_affected++] = key
            } else if (before_cat == 2 && after_cat == 1) {
                partly_resolved[n_partly_resolved++] = key
            } else if (before_cat == 1 && after_cat == 1) {
                diff = after_fail_rate - before_fail_rate
                if (diff < 0) diff = -diff
                if (diff >= 5) {
                    rate_before[key] = before_fail_rate
                    rate_after[key]  = after_fail_rate
                    rate_changed[n_rate_changed++] = key
                } else {
                    n_unchanged++
                }
            } else {
                n_unchanged++
            }
        }

        if (n_newly_affected > 0) {
            print "NEWLY AFFECTED (no prior failures → failures now detected)"
            for (i = 0; i < n_newly_affected; i++) {
                key = newly_affected[i]
                ap = total_pass[key]+0; af = total_fail[key]+0; ae = total_err[key]+0
                after_total = ap + af + ae
                after_fail_rate = int((af + ae) * 100 / after_total)
                if (newly_affected_cat[key] == -1)
                    printf "  %-72s  before: —            after: %d%% fail  (fail=%d / total=%d)\n", \
                        key, after_fail_rate, af + ae, after_total
                else
                    printf "  %-72s  before: always-pass  after: %d%% fail  (fail=%d / total=%d)\n", \
                        key, after_fail_rate, af + ae, after_total
            }
            printf "\n"
        }

        if (n_partly_resolved > 0) {
            print "PARTLY RESOLVED (always-fail → flaky)"
            for (i = 0; i < n_partly_resolved; i++) {
                key = partly_resolved[i]
                ap = total_pass[key]+0; af = total_fail[key]+0; ae = total_err[key]+0
                after_total = ap + af + ae
                after_fail_rate = int((af + ae) * 100 / after_total)
                printf "  %-72s  before: always-fail  after: %d%% fail  (pass=%d / total=%d)\n", \
                    key, after_fail_rate, ap, after_total
            }
            printf "\n"
        }

        if (n_rate_changed > 0) {
            print "RATE CHANGED (flaky before, flaky after; failure rate shifted ≥ 5 pp)"
            for (i = 0; i < n_rate_changed; i++) {
                key = rate_changed[i]
                ap = total_pass[key]+0; af = total_fail[key]+0; ae = total_err[key]+0
                np = new_pass[key]+0;   nf = new_fail[key]+0;   ne = new_err[key]+0
                bf = (af - nf); be = (ae - ne)
                before_total = (ap - np) + bf + be
                after_total  = ap + af + ae
                printf "  %-72s  fail rate: %d%% → %d%%  (before: fail=%d/%d  after: fail=%d/%d)\n", \
                    key, rate_before[key], rate_after[key], bf + be, before_total, af + ae, after_total
            }
            printf "\n"
        }

        printf "NO SIGNIFICANT CHANGE : %d tests\n", n_unchanged
    }
    ' "$file"
}

print_progress() {
    local iter="$1" mvn_exit="$2" file="$3"
    local this_pass=0 this_fail=0 this_err=0

    while IFS=$'\t' read -r i _key status; do
        [[ "$i" == "$iter" ]] || continue
        case "$status" in
            pass)  (( ++this_pass )) ;;
            fail)  (( ++this_fail )) ;;
            error) (( ++this_err  )) ;;
        esac
    done < <(grep -v '^#' "$file" 2>/dev/null || true)

    local flaky
    flaky=$(awk -F'\t' '
        /^#/ { next }
        { s[$2] = s[$2] " " $3 }
        END { n=0; for (k in s) if (s[k] ~ /pass/ && s[k] ~ /fail|error/) n++; print n }
    ' "$file")

    local iter_disp
    if [[ -n "$ITERATIONS" ]]; then
        iter_disp="$(printf '%d/%d' "$iter" "$ITERATIONS")"
    else
        iter_disp="$iter"
    fi

    printf '[iter %7s] mvn exit=%d  this-run: %d fail, %d err, %d pass  cumulative flaky: %d\n' \
        "$iter_disp" "$mvn_exit" "$this_fail" "$this_err" "$this_pass" "$flaky"
}

main() {
    if [[ $# -eq 0 ]]; then
        usage
        exit 0
    fi

    parse_args "$@"

    if [[ "$REPORT_MODE" == true ]]; then
        print_summary "$OUTPUT_FILE"
        return 0
    fi

    cd "$REPO_ROOT"
    MAVEN_CMD="$(build_maven_cmd)"
    init_results_file "$OUTPUT_FILE"

    printf 'Results : %s\n' "$OUTPUT_FILE"
    printf 'Command : %s\n\n' "$MAVEN_CMD"

    local iterations_done
    iterations_done=$(awk -F'\t' '!/^#/ && NF==3 && $1+0>max {max=$1+0} END {print max+0}' "$OUTPUT_FILE")
    local start_iter="$iterations_done"

    local _iter_sentinel
    _iter_sentinel=$(mktemp)

    on_interrupt() {
        printf '\nInterrupted after %d iteration(s).\n' "$iterations_done"
        rm -f "$_iter_sentinel"
        if [[ "$start_iter" -gt 0 ]]; then
            print_summary "$OUTPUT_FILE" "$start_iter"
            print_delta   "$OUTPUT_FILE" "$start_iter"
        fi
        print_summary "$OUTPUT_FILE"
        exit 0
    }
    trap on_interrupt INT
    trap 'rm -f "$_iter_sentinel"' EXIT

    while true; do
        (( ++iterations_done ))

        touch "$_iter_sentinel"
        local mvn_exit=0
        eval "$MAVEN_CMD" || mvn_exit=$?

        parse_all_reports "$iterations_done" "$_iter_sentinel" >> "$OUTPUT_FILE"
        copy_failure_reports "$iterations_done" "$_iter_sentinel"
        print_progress "$iterations_done" "$mvn_exit" "$OUTPUT_FILE"

        if [[ -n "$ITERATIONS" && "$iterations_done" -ge "$ITERATIONS" ]]; then
            break
        fi
    done

    if [[ "$start_iter" -gt 0 ]]; then
        print_summary "$OUTPUT_FILE" "$start_iter"
        print_delta   "$OUTPUT_FILE" "$start_iter"
    fi
    print_summary "$OUTPUT_FILE"
}

# Allow sourcing by bats tests without triggering main
if [[ -z "${BATS_TEST_FILENAME:-}" ]]; then
    main "$@"
fi
