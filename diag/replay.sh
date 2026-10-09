source diag/lib.sh
G=src/main/scala/org/ergoplatform/mining/CandidateGenerator.scala
CLEAN='rm -rf .ergo_test'
# PR #2664 commits: the parent-event case kills a release that ignores the solved block's parent
step parent-event-mutant fail "$CLEAN; sed -i 's/val solvedBlockAfter = if (needNewSolution(state.solvedBlock, header.id)) None else state.solvedBlock/val solvedBlockAfter: Option[ErgoFullBlock] = None/' $G && grep -q 'Option\[ErgoFullBlock\] = None' $G" 'org.ergoplatform.mining.CandidateGeneratorSpec -- -z "event that arrives is for its parent"'
# ... and a release that also drops the cache (the second solution would then get "Block already solved : None")
step parent-event-mutant-clear-both fail "$CLEAN; sed -i 's/^        context.become(initialized(stateWithAppliedTxs))\$/        context.become(initialized(stateWithAppliedTxs.copy(cachedCandidate = None, solvedBlock = None)))/' $G && grep -q 'stateWithAppliedTxs.copy(cachedCandidate = None, solvedBlock = None)' $G" 'org.ergoplatform.mining.CandidateGeneratorSpec -- -z "event that arrives is for its parent"'
# on #2411: the sibling cases fail with the generator before the parent check (0e3caffe9 = #2664 head + #2411)
step sibling-red fail "$CLEAN; git checkout 0e3caffe9 -- $G" 'org.ergoplatform.mining.CandidateGeneratorSpec -- -z "not mine a sibling from the previous candidate"'
step generator-and-miner pass "$CLEAN" 'org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.ErgoMinerSpec'
finish
