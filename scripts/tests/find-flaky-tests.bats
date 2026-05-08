#!/usr/bin/env bats
bats_require_minimum_version 1.5.0

FIXTURES="$BATS_TEST_DIRNAME/fixtures"

# Source the script — BATS_TEST_FILENAME is set, so main() is skipped
source "$BATS_TEST_DIRNAME/../find-flaky-tests.sh"

setup() {
    TEST_TMPDIR="$(mktemp -d)"
}

teardown() {
    rm -rf "$TEST_TMPDIR"
}

# ---- parse_surefire_xml ----

@test "parse_surefire_xml: all-passing suite emits only 'pass' lines" {
    run parse_surefire_xml "$FIXTURES/passing-suite.xml" 1
    [ "$status" -eq 0 ]
    [ "${#lines[@]}" -eq 2 ]
    [[ "$output" == *$'\tpass'* ]]
    [[ "$output" != *$'\tfail'* ]]
    [[ "$output" != *$'\terror'* ]]
    [[ "$output" != *$'\tskip'* ]]
}

@test "parse_surefire_xml: mixed suite emits correct statuses" {
    run parse_surefire_xml "$FIXTURES/mixed-suite.xml" 1
    [ "$status" -eq 0 ]
    [ "${#lines[@]}" -eq 4 ]
    [[ "$output" == *"com.example.MixedTest#testPassing"$'\tpass'* ]]
    [[ "$output" == *"com.example.MixedTest#testFailing"$'\tfail'* ]]
    [[ "$output" == *"com.example.MixedTest#testErroring"$'\terror'* ]]
    [[ "$output" == *"com.example.MixedTest#testSkipped"$'\tskip'* ]]
}

@test "parse_surefire_xml: parameterized test names preserve [N] suffixes" {
    run parse_surefire_xml "$FIXTURES/parameterized-suite.xml" 1
    [ "$status" -eq 0 ]
    [ "${#lines[@]}" -eq 2 ]
    [[ "$output" == *"testMethod(int)[1]"* ]]
    [[ "$output" == *"testMethod(int)[2]"* ]]
    [[ "${lines[0]}" == *$'\tpass'* ]]
    [[ "${lines[1]}" == *$'\tfail'* ]]
}

@test "parse_surefire_xml: iter number is included in each output line" {
    run parse_surefire_xml "$FIXTURES/passing-suite.xml" 42
    [ "$status" -eq 0 ]
    [[ "${lines[0]}" == "42"$'\t'* ]]
}

# ---- print_summary ----

make_tsv() {
    local file="$1"
    printf '# started=2026-01-01T00:00:00Z\n' > "$file"
    printf '# iterations_planned=3\n' >> "$file"
    printf '# columns: iter\ttest_key\tstatus\n' >> "$file"
}

@test "print_summary: flaky test appears in FLAKY bucket" {
    local tsv="$TEST_TMPDIR/flaky.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"FLAKY"* ]]
    [[ "$output" == *"com.example.FooTest#testBar"* ]]
    [[ "$output" == *"ALWAYS FAILING"* ]]
    [[ "$output" == *"(none)"* ]]
}

@test "print_summary: always-failing test appears in ALWAYS FAILING bucket" {
    local tsv="$TEST_TMPDIR/always-fail.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"ALWAYS FAILING"* ]]
    [[ "$output" == *"com.example.FooTest#testBar"* ]]
    # Should not appear in flaky section
    [[ "${lines[*]}" != *"FLAKY"*"com.example.FooTest"* ]] || \
        [[ "$output" == *"FLAKY"*"(none)"* ]]
}

@test "print_summary: malformed line triggers warning and renders partial results" {
    local tsv="$TEST_TMPDIR/partial.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf 'THIS IS MALFORMED\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"

    run --separate-stderr print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$stderr" == *"malformed"* ]]
    [[ "$output" == *"ALWAYS PASSING"* ]]
    # Line after the malformed line should not be counted (only 1 pass, no fail)
    [[ "$output" != *"FLAKY"*"FooTest"* ]] || [[ "$output" == *"(none)"* ]]
}

@test "print_summary: exits non-zero with error message for missing file" {
    run print_summary "$TEST_TMPDIR/nonexistent.tsv"
    [ "$status" -ne 0 ]
    [[ "$output" == *"not found"* ]]
}

@test "print_summary: flaky test shows streak=N when last N runs passed" {
    local tsv="$TEST_TMPDIR/streak-n.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '4\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"streak=3"* ]]
}

@test "print_summary: flaky test shows streak=0 when last run failed" {
    local tsv="$TEST_TMPDIR/streak-0.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"streak=0"* ]]
}

@test "print_summary: streak is not affected by skipped runs" {
    local tsv="$TEST_TMPDIR/streak-skip.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tskip\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '4\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"streak=2"* ]]
}

@test "print_summary: streak counts passes that sandwich a skip" {
    local tsv="$TEST_TMPDIR/streak-sandwich.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testBar\tskip\n' >> "$tsv"
    printf '4\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"streak=2"* ]]
}

@test "print_summary: always-failing test shows streak=0" {
    local tsv="$TEST_TMPDIR/streak-always-fail.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"streak=0"* ]]
}

@test "print_summary: flaky test shows unfixed=37% for 1 fail in 100 followed by 100 clean passes" {
    local tsv="$TEST_TMPDIR/unfixed-math.tsv"
    make_tsv "$tsv"
    local i
    for (( i = 1; i <= 99; i++ )); do
        printf '%d\tcom.example.FooTest#testBar\tpass\n' "$i" >> "$tsv"
    done
    printf '100\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    for (( i = 101; i <= 200; i++ )); do
        printf '%d\tcom.example.FooTest#testBar\tpass\n' "$i" >> "$tsv"
    done

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"streak=100"* ]]
    [[ "$output" == *"unfixed=37%"* ]]
}

@test "print_summary: unfixed not shown when streak is 0" {
    local tsv="$TEST_TMPDIR/unfixed-streak0.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"

    run print_summary "$tsv"
    [ "$status" -eq 0 ]
    [[ "$output" == *"streak=0"* ]]
    [[ "$output" != *"unfixed="* ]]
}

@test "print_summary: unfixed not shown in from_iter path" {
    local tsv="$TEST_TMPDIR/unfixed-from-iter.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testBar\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"

    run print_summary "$tsv" 1
    [ "$status" -eq 0 ]
    [[ "$output" != *"unfixed="* ]]
}

@test "print_summary with from_iter only includes later iterations and shows This Run label" {
    local tsv="$TEST_TMPDIR/multi-iter.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testA\tpass\n' >> "$tsv"
    printf '1\tcom.example.FooTest#testB\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testA\tpass\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testB\tpass\n' >> "$tsv"

    run print_summary "$tsv" 1
    [ "$status" -eq 0 ]
    [[ "$output" == *"(This Run)"* ]]
    [[ "$output" == *"iter 2 to 2"* ]]
    [[ "$output" == *"ALWAYS PASSING / SKIPPED : 2 tests"* ]]
}

# ---- print_delta ----

@test "print_delta: always-pass test that gains failures appears under NEWLY AFFECTED" {
    local tsv="$TEST_TMPDIR/delta.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testA\tpass\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testA\tpass\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testA\tpass\n' >> "$tsv"
    printf '4\tcom.example.FooTest#testA\tfail\n' >> "$tsv"

    run print_delta "$tsv" 3
    [ "$status" -eq 0 ]
    [[ "$output" == *"NEWLY AFFECTED"* ]]
    [[ "$output" == *"com.example.FooTest#testA"* ]]
    [[ "$output" == *"before: always-pass"* ]]
}

@test "print_delta: always-fail test that starts passing appears under PARTLY RESOLVED" {
    local tsv="$TEST_TMPDIR/delta2.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testB\tfail\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testB\tfail\n' >> "$tsv"
    printf '3\tcom.example.FooTest#testB\tpass\n' >> "$tsv"

    run print_delta "$tsv" 2
    [ "$status" -eq 0 ]
    [[ "$output" == *"PARTLY RESOLVED"* ]]
    [[ "$output" == *"com.example.FooTest#testB"* ]]
}

# ---- copy_failure_reports ----

@test "copy_failure_reports copies failing XML with iter suffix, skips passing XML" {
    local repo; repo=$(mktemp -d)
    local reports_dir="$repo/core/target/surefire-reports"
    mkdir -p "$reports_dir"

    local sentinel; sentinel=$(mktemp)
    sleep 0.01  # ensure XML mtime is strictly newer than sentinel

    printf '<testsuite><testcase classname="A" name="b"><failure>boom</failure></testcase></testsuite>\n' \
        > "$reports_dir/TEST-A.xml"
    printf '<testsuite><testcase classname="A" name="c"/></testsuite>\n' \
        > "$reports_dir/TEST-Clean.xml"

    REPO_ROOT="$repo" OUTPUT_FILE="my-run.tsv" copy_failure_reports 5 "$sentinel"

    local base="$repo/target/find-flaky-tests/failures/my-run"
    local rel="core/target/surefire-reports"
    [ -f "$base/$rel/TEST-A-iter-005.xml" ]
    [ ! -f "$base/$rel/TEST-Clean-iter-005.xml" ]

    rm -rf "$repo" "$sentinel"
}

@test "copy_failure_reports produces no output and no directory when no failures" {
    local repo; repo=$(mktemp -d)
    local reports_dir="$repo/core/target/surefire-reports"
    mkdir -p "$reports_dir"

    local sentinel; sentinel=$(mktemp)
    sleep 0.01

    printf '<testsuite><testcase classname="A" name="c"/></testsuite>\n' \
        > "$reports_dir/TEST-Clean.xml"

    run env REPO_ROOT="$repo" OUTPUT_FILE="my-run.tsv" bash -c \
        'source "'"$BATS_TEST_DIRNAME/../find-flaky-tests.sh"'"; copy_failure_reports 1 "'"$sentinel"'"'
    [ "$status" -eq 0 ]
    [ -z "$output" ]
    [ ! -d "$repo/target/find-flaky-tests" ]

    rm -rf "$repo" "$sentinel"
}

# ---- init_results_file ----

# ---- load_failing_classes ----

@test "load_failing_classes: exits with error when all tests are passing" {
    local tsv="$TEST_TMPDIR/all-pass.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testA\tpass\n' >> "$tsv"
    printf '2\tcom.example.FooTest#testA\tpass\n' >> "$tsv"

    run load_failing_classes "$tsv"
    [ "$status" -ne 0 ]
    [[ "$output" == *"passing"* ]]
}

@test "load_failing_classes: exits with error when file not found" {
    run load_failing_classes "$TEST_TMPDIR/nonexistent.tsv"
    [ "$status" -ne 0 ]
    [[ "$output" == *"not found"* ]]
}

@test "load_failing_classes: extracts unique class names from fail/error rows and sets TEST_FILTER" {
    local tsv="$TEST_TMPDIR/mixed.tsv"
    make_tsv "$tsv"
    printf '1\tcom.example.FooTest#testA\tpass\n'  >> "$tsv"
    printf '2\tcom.example.FooTest#testA\tfail\n'  >> "$tsv"
    printf '1\tcom.example.BarTest#testB\terror\n' >> "$tsv"
    printf '1\tcom.example.BazTest#testC\tpass\n'  >> "$tsv"

    TEST_FILTER=""
    load_failing_classes "$tsv"
    [[ "$TEST_FILTER" == *"com.example.FooTest"* ]]
    [[ "$TEST_FILTER" == *"com.example.BarTest"* ]]
    [[ "$TEST_FILTER" != *"com.example.BazTest"* ]]
    [[ "$TEST_FILTER" != *"#"* ]]
}

# ---- parse_args --rerun-failures ----

@test "parse_args: --rerun-failures sets RERUN_FAILURES" {
    RERUN_FAILURES=""
    parse_args --rerun-failures my-results.tsv
    [ "$RERUN_FAILURES" = "my-results.tsv" ]
}

# ---- init_results_file ----

@test "init_results_file: creates header when file does not exist" {
    MAVEN_CMD="./mvnw test"
    local tsv="$TEST_TMPDIR/new.tsv"
    init_results_file "$tsv"
    [ -f "$tsv" ]
    grep -q '^# started=' "$tsv"
}

@test "init_results_file: does not overwrite an existing file" {
    local tsv="$TEST_TMPDIR/existing.tsv"
    printf '# started=2020-01-01T00:00:00Z\n' > "$tsv"
    printf '1\tcom.example.FooTest#testBar\tpass\n' >> "$tsv"
    local before
    before="$(cat "$tsv")"

    MAVEN_CMD="./mvnw test"
    init_results_file "$tsv"

    local after
    after="$(cat "$tsv")"
    [ "$before" = "$after" ]
}
