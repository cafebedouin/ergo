source diag/lib.sh
T=src/main/scala/org/ergoplatform/mining/ErgoMiningThread.scala
CLEAN='rm -rf .ergo_test target/test-reports'
keepxml(){ mkdir -p "diag/out/$1-xml"; cp target/test-reports/TEST-*.xml "diag/out/$1-xml/" 2>/dev/null || true; }
# reds: the tip's thread, the no-re-poll mutant, the thread before coalescing
step thread-tip fail "$CLEAN; git checkout b2a9e7b00 -- $T" 'org.ergoplatform.mining.ErgoMiningThreadSpec'
step thread-no-repoll fail "$CLEAN; sed -i 's/^      candidateGenerator ! PollCandidate\$/      \/\/ MUTANT: no re-poll/' $T && grep -q 'MUTANT: no re-poll' $T" 'org.ergoplatform.mining.ErgoMiningThreadSpec'
step chain-before-coalesce fail "$CLEAN; git checkout 26e6e9251 -- $T" 'org.ergoplatform.mining.ErgoMiningThreadChainSpec'
# greens at the head
step thread-head pass "$CLEAN" 'org.ergoplatform.mining.ErgoMiningThreadSpec org.ergoplatform.mining.ErgoMiningThreadChainSpec'
# generator + miner specs carry pre-existing failures: compare the failing names between head and the tip's thread
step gen-miner-head fail "$CLEAN" 'org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.ErgoMinerSpec'; keepxml gen-miner-head
step gen-miner-tip-thread fail "$CLEAN; git checkout b2a9e7b00 -- $T" 'org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.ErgoMinerSpec'; keepxml gen-miner-tip-thread
finish
