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
