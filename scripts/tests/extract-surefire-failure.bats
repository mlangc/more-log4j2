#!/usr/bin/env bats
bats_require_minimum_version 1.5.0

FIXTURES="$BATS_TEST_DIRNAME/fixtures"

source "$BATS_TEST_DIRNAME/../extract-surefire-failure.sh"

# ---- no-args / help ----

@test "no args prints usage and exits 0" {
    run main
    [ "$status" -eq 0 ]
    [[ "$output" == *"Usage:"* ]]
}

@test "--help prints usage and exits 0" {
    run main --help
    [ "$status" -eq 0 ]
    [[ "$output" == *"Usage:"* ]]
}

# ---- passing suite ----

@test "passing suite reports no failures and exits 0" {
    run main "$FIXTURES/passing-suite.xml"
    [ "$status" -eq 0 ]
    [[ "$output" == *"No failures"* ]]
}

# ---- mixed suite: list (2 entries: 1 failure + 1 error) ----

@test "mixed suite without index lists both entries" {
    run main "$FIXTURES/mixed-suite.xml"
    [ "$status" -eq 0 ]
    [[ "$output" == *"[1]"* ]]
    [[ "$output" == *"[2]"* ]]
    [[ "$output" == *"testFailing"* ]]
    [[ "$output" == *"testErroring"* ]]
    [[ "$output" == *"(failure)"* ]]
    [[ "$output" == *"(error)"* ]]
}

# ---- mixed suite: extract by index ----

@test "mixed suite index 1 extracts the failure message" {
    run main "$FIXTURES/mixed-suite.xml" 1
    [ "$status" -eq 0 ]
    [[ "$output" == *"AssertionError"* ]]
}

@test "mixed suite index 2 extracts the error message" {
    run main "$FIXTURES/mixed-suite.xml" 2
    [ "$status" -eq 0 ]
    [[ "$output" == *"NullPointerException"* ]]
}

# ---- index validation ----

@test "index 0 is rejected with exit 1" {
    run main "$FIXTURES/mixed-suite.xml" 0
    [ "$status" -eq 1 ]
}

@test "index exceeding count is rejected with exit 1" {
    run main "$FIXTURES/mixed-suite.xml" 3
    [ "$status" -eq 1 ]
}

@test "non-numeric index is rejected with exit 1" {
    run main "$FIXTURES/mixed-suite.xml" abc
    [ "$status" -eq 1 ]
}

# ---- missing file ----

@test "missing file exits 1" {
    run main "$FIXTURES/nonexistent.xml"
    [ "$status" -eq 1 ]
}

# ---- stream extraction (--system-out / --system-err) ----

@test "--system-out extracts system-out from failing testcase" {
    run main --system-out "$FIXTURES/streams-suite.xml" 1
    [ "$status" -eq 0 ]
    [[ "$output" == *"some stdout output"* ]]
}

@test "--system-err extracts system-err from failing testcase" {
    run main --system-err "$FIXTURES/streams-suite.xml" 1
    [ "$status" -eq 0 ]
    [[ "$output" == *"some stderr output"* ]]
}

@test "--system-out and --system-err together include both streams with headers" {
    run main --system-out --system-err "$FIXTURES/streams-suite.xml" 1
    [ "$status" -eq 0 ]
    [[ "$output" == *"system-out"* ]]
    [[ "$output" == *"system-err"* ]]
    [[ "$output" == *"some stdout output"* ]]
    [[ "$output" == *"some stderr output"* ]]
}

@test "--system-out and --system-err both empty prints nothing to stdout" {
    run --separate-stderr main --system-out --system-err "$FIXTURES/streams-suite.xml" 2
    [ "$status" -eq 0 ]
    [ -z "$output" ]
    [[ "$stderr" == *"no system-out"* ]]
    [[ "$stderr" == *"no system-err"* ]]
}

@test "--system-out on testcase with no stream warns on stderr and exits 0" {
    run --separate-stderr main --system-out "$FIXTURES/streams-suite.xml" 2
    [ "$status" -eq 0 ]
    [ -z "$output" ]
    [[ "$stderr" == *"no system-out"* ]]
}

@test "--system-err on testcase with no stream warns on stderr and exits 0" {
    run --separate-stderr main --system-err "$FIXTURES/streams-suite.xml" 2
    [ "$status" -eq 0 ]
    [ -z "$output" ]
    [[ "$stderr" == *"no system-err"* ]]
}

@test "--system-err on realistic fixture with no streams warns on stderr and exits 0" {
    run --separate-stderr main --system-err "$FIXTURES/throttling-filter-failure.xml"
    [ "$status" -eq 0 ]
    [ -z "$output" ]
    [[ "$stderr" == *"no system-err"* ]]
}

@test "listing mode is unchanged when --system-out flag is present but no index given" {
    run main --system-out "$FIXTURES/streams-suite.xml"
    [ "$status" -eq 0 ]
    [[ "$output" == *"[1]"* ]]
    [[ "$output" == *"[2]"* ]]
}

# ---- --all flag ----

@test "--all extracts failure messages from all failing testcases" {
    run main --all "$FIXTURES/mixed-suite.xml"
    [ "$status" -eq 0 ]
    [[ "$output" == *"testFailing"* ]]
    [[ "$output" == *"testErroring"* ]]
    [[ "$output" == *"AssertionError"* ]]
    [[ "$output" == *"NullPointerException"* ]]
}

@test "--all --system-out extracts streams from all testcases that have content" {
    run main --all --system-out "$FIXTURES/streams-suite.xml"
    [ "$status" -eq 0 ]
    [[ "$output" == *"testPassing"* ]]
    [[ "$output" == *"testFailing"* ]]
    [[ "$output" != *"testErroringNoStreams"* ]]
    [[ "$output" == *"some stdout output"* ]]
    [[ "$output" == *"passing stdout"* ]]
}

@test "--all --system-err extracts only system-err content" {
    run main --all --system-err "$FIXTURES/streams-suite.xml"
    [ "$status" -eq 0 ]
    [[ "$output" == *"some stderr output"* ]]
    [[ "$output" != *"some stdout output"* ]]
}

@test "--all --system-out --system-err shows sub-headers for each stream" {
    run main --all --system-out --system-err "$FIXTURES/streams-suite.xml"
    [ "$status" -eq 0 ]
    [[ "$output" == *"--- system-out ---"* ]]
    [[ "$output" == *"--- system-err ---"* ]]
}

@test "--all on suite with no streams warns on stderr and exits 0" {
    run --separate-stderr main --all --system-err "$FIXTURES/throttling-filter-failure.xml"
    [ "$status" -eq 0 ]
    [ -z "$output" ]
    [[ "$stderr" == *"No captured stream content"* ]]
}

@test "--all with an index is rejected with exit 1" {
    run main --all "$FIXTURES/mixed-suite.xml" 1
    [ "$status" -eq 1 ]
}
