# scripts/

Bash 5+ scripts for development tooling.

## Constraints

- Shebang `#!/usr/bin/env bash` — requires bash 5 on `PATH` (e.g. `brew install bash`); the scripts enforce this with a version check.
- Use `(( ++x ))` not `(( x++ ))` when `x` starts at 0 — post-increment evaluates to the old value (0/falsy), which kills the script under `set -e`.
- XML parsing uses `xmllint` (macOS built-in). `grep -P` (PCRE) is not available on BSD grep — use `awk` instead.

## Tests

```bash
bats scripts/tests/find-flaky-tests.bats
```

`find-flaky-tests.sh` skips `main` when `BATS_TEST_FILENAME` is set, allowing bats to source and test individual functions.
