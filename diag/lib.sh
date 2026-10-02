# step <name> <expect: pass|fail> <prep shell, run before sbt; "" for none> <sbt testOnly argument>
# Each step starts from the committed tree; prep may restore old files or apply a mutation. Outcome = sbt exit status.
set -u
mkdir -p diag/out; : > diag/out/SUMMARY.txt; bad=0
step(){ local name=$1 expect=$2 prep=$3 test=$4
  git reset -q --hard HEAD; git clean -fdq src 2>/dev/null
  [ -n "$prep" ] && { bash -c "$prep" || { echo "$name: PREP FAILED" | tee -a diag/out/SUMMARY.txt; bad=1; return; }; }
  sbt -Denv=test ++2.12.20 "testOnly $test" > "diag/out/$name.log" 2>&1; local rc=$?
  local got=$([ $rc -eq 0 ] && echo pass || echo fail)
  # a compile error is not a red: a 'fail' expectation needs a failed test, not a failed build
  if [ "$got" = fail ] && ! grep -qE 'TEST FAILED|TESTS FAILED' "diag/out/$name.log"; then got="build-error"; fi
  local res=$(sed 's/\x1b\[[0-9;]*m//g' "diag/out/$name.log" | grep -E 'Tests: succeeded' | tail -1)
  local why=$(sed 's/\x1b\[[0-9;]*m//g' "diag/out/$name.log" | grep -A1 'FAILED \*\*\*' | grep -vE 'FAILED \*\*\*' | head -1 | sed 's/^\[info\] *//')
  local ok=$([ "$got" = "$expect" ] && echo OK || echo MISMATCH); [ "$ok" = OK ] || bad=1
  echo "$ok  $name: expected $expect, got $got | $res | $why" | tee -a diag/out/SUMMARY.txt
}
finish(){ git reset -q --hard HEAD; echo "replay done: $([ $bad -eq 0 ] && echo all as expected || echo MISMATCH)" | tee -a diag/out/SUMMARY.txt; exit $bad; }
