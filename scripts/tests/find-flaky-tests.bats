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
    printf '# command=./mvnw test\n' >> "$file"
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

# ---- init_results_file ----

@test "init_results_file: creates header when file does not exist" {
    MAVEN_CMD="./mvnw test"
    local tsv="$TEST_TMPDIR/new.tsv"
    init_results_file "$tsv"
    [ -f "$tsv" ]
    grep -q '^# started=' "$tsv"
    grep -q '^# command=' "$tsv"
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
