# scripts/

Bash 5+ scripts for development tooling. Each supports `--help`.

- `find-flaky-tests.sh` — reruns Maven tests in a loop and prints a flakiness report. Run it
  from the repository root, or pass `--root DIR`. Per-test results go to
  `flaky-results-<timestamp>.tsv` in the current directory (gitignored); the Surefire XML
  reports of failing iterations are copied to `target/find-flaky-tests/failures/<run>/`.
  `--rerun-failures FILE` reruns only the classes that failed in an earlier results file;
  `--fail-fast` stops at the first failing iteration.
- `extract-surefire-failure.sh` — extracts failure messages and captured output from a
  Surefire XML report (`TEST-*.xml`), e.g. one of the copies above. Works from any
  directory.

## Constraints

- Shebang `#!/usr/bin/env bash` — requires bash 5 on `PATH` (e.g. `brew install bash`); the
  scripts enforce this with a version check. macOS's `/bin/bash` is 3.2.
- Scripts run under `set -euo pipefail`. Use `(( ++x ))` not `(( x++ ))` when `x` starts at
  0 — post-increment evaluates to the old value (0/falsy), which kills the script under
  `set -e`.
- Must work with macOS/BSD userland: `grep -P` (PCRE) is not available on BSD grep — use
  `awk` instead.
- XML parsing uses `xmllint` (macOS built-in).

## Tests

Each script has a [bats](https://github.com/bats-core/bats-core) suite in `scripts/tests/`
(bats 1.5.0+, e.g. `brew install bats-core`), with Surefire XML fixtures under
`scripts/tests/fixtures/`. Add or update tests when changing a script.

```bash
bats scripts/tests/                          # all suites
bats scripts/tests/find-flaky-tests.bats     # one suite
```

Both scripts skip `main` when `BATS_TEST_FILENAME` is set, allowing bats to source them and
test individual functions. Keep that guard at the bottom of any new script.
