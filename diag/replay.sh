source diag/lib.sh
G=src/main/scala/org/ergoplatform/mining/CandidateGenerator.scala
CLEAN='rm -rf .ergo_test'
# with v6.0.9's generator (cc60d65d2): the property throws a MatchError; the restarted generator never serves a candidate
step property-red fail "$CLEAN; git checkout cc60d65d2 -- $G" 'org.ergoplatform.mining.CandidateGeneratorPropSpec -- -z "fewer than two timestamps"'
# the restart loop logs an ERROR per attempt (about 680k in 90 s): logging off for this step keeps the log small
step restart-red fail "$CLEAN; git checkout cc60d65d2 -- $G; sed -i 's#<level>WARN</level>#<level>OFF</level>#' src/test/resources/logback-test.xml && grep -q '<level>OFF</level>' src/test/resources/logback-test.xml" 'org.ergoplatform.mining.CandidateGeneratorSpec -- -z "chain has a single block"'
step generator-prop-miner pass "$CLEAN" 'org.ergoplatform.mining.CandidateGeneratorPropSpec org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.ErgoMinerSpec'
finish
