# step <name> <expect: pass|fail> <prep shell, run before sbt; "" for none> <sbt testOnly argument>
# Each step starts from the committed tree; prep may restore old files or apply a mutation. Outcome = sbt exit status.
# Logs keep sbt's [info]/[error] test lines only (the generator's own logging runs to gigabytes in the miner specs).
set -u
mkdir -p diag/out; : > diag/out/SUMMARY.txt; bad=0
filt(){ sed 's/\x1b\[[0-9;]*m//g' | grep -aE '^\[(info|error)\] ' | grep -aE '^\[error\]|^\[info\] (- |  \+|  [A-Za-z(]|Tests:|\*\*\*|[A-Za-z]+Spec:)' | cut -c1-600; }
step(){ local name=$1 expect=$2 prep=$3 test=$4
  git reset -q --hard HEAD; git clean -fdq src 2>/dev/null; rm -rf .ergo_test target/test-reports
  [ -n "$prep" ] && { bash -c "$prep" || { echo "$name: PREP FAILED" | tee -a diag/out/SUMMARY.txt; bad=1; return; }; }
  git diff > "diag/out/$name.diff"
  sbt -Denv=test ++2.12.20 "testOnly $test" 2>&1 | filt > "diag/out/$name.log"; local rc=${PIPESTATUS[0]}
  local got=$([ $rc -eq 0 ] && echo pass || echo fail)
  # a compile error is not a red: a 'fail' expectation needs a failed test, not a failed build
  if [ "$got" = fail ] && ! grep -qE 'TEST FAILED|TESTS FAILED' "diag/out/$name.log"; then got="build-error"; fi
  local res=$(grep -E 'Tests: succeeded' "diag/out/$name.log" | tail -1)
  local ok=$([ "$got" = "$expect" ] && echo OK || echo MISMATCH); [ "$ok" = OK ] || bad=1
  echo "$ok  $name: expected $expect, got $got | $res" | tee -a diag/out/SUMMARY.txt
  grep -A1 'FAILED \*\*\*' "diag/out/$name.log" | grep -vE 'FAILED \*\*\*|^--$' | head -6 | sed 's/^/      /' | tee -a diag/out/SUMMARY.txt
  mkdir -p "diag/out/$name-xml"; cp target/test-reports/TEST-*.xml "diag/out/$name-xml/" 2>/dev/null || true
}
finish(){ git reset -q --hard HEAD; echo "replay done: $([ $bad -eq 0 ] && echo all as expected || echo MISMATCH)" | tee -a diag/out/SUMMARY.txt; exit $bad; }
