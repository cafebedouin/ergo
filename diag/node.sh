# node.sh <this-branch | previous-head-7ea4cf2a3>: upstream's node job (sbt -Denv=test clean test) with this branch's
# miner thread or the previous PR head's; lists every failing test case from the JUnit reports.
set -u
mkdir -p diag/out
if [ "$1" = previous-head-7ea4cf2a3 ]; then git checkout 7ea4cf2a3 -- src/main/scala/org/ergoplatform/mining/ErgoMiningThread.scala; fi
{ git rev-parse HEAD; git status --short; git diff --cached --stat; } | tee diag/out/TREE.txt
sed_strip(){ sed 's/\x1b\[[0-9;]*m//g'; }
sbt -Denv=test clean ++2.12.20 test 2>&1 | sed_strip | grep -aE '^\[(info|error)\] ' | grep -aE '^\[error\]|^\[info\] (- |Tests:|\*\*\*|[A-Za-z]+Spec:|Passed|Failed|Run completed)' | cut -c1-400 > diag/out/node.log
rc=${PIPESTATUS[0]}
mkdir -p diag/out/xml; find . -path '*/target/test-reports/TEST-*.xml' -exec cp {} diag/out/xml/ \;
python3 - <<'P' | tee diag/out/FAILING.txt
import glob, xml.etree.ElementTree as ET
fails=[]; total=0
for f in sorted(glob.glob('diag/out/xml/TEST-*.xml')):
    for tc in ET.parse(f).getroot().iter('testcase'):
        total+=1
        if tc.find('failure') is not None or tc.find('error') is not None:
            fails.append(f"{tc.get('classname')} :: {tc.get('name')}")
print(f"test cases: {total}, failing: {len(fails)}")
for x in fails: print(x)
P
echo "sbt exit status: $rc" | tee -a diag/out/FAILING.txt
grep -a 'Tests: succeeded' diag/out/node.log | tail -3
exit $rc
