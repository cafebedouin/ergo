source diag/lib.sh
# the two new cases with the generator before 8d67ef829: the sibling case fails, the parent-event case passes
step sibling-red fail 'git checkout 1e53ce716 -- src/main' 'org.ergoplatform.mining.CandidateGeneratorSpec -- -z "sibling of the applied block"'
# the parent-event case kills a release that ignores the solved block's parent
step parent-event-mutant fail "sed -i 's/val solvedBlockAfter = if (needNewSolution(state.solvedBlock, header.id)) None else state.solvedBlock/val solvedBlockAfter: Option[ErgoFullBlock] = None/' src/main/scala/org/ergoplatform/mining/CandidateGenerator.scala && grep -q 'Option\[ErgoFullBlock\] = None' src/main/scala/org/ergoplatform/mining/CandidateGenerator.scala" 'org.ergoplatform.mining.CandidateGeneratorSpec -- -z "event that arrives is for its parent"'
step generator-and-miner pass "" 'org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.ErgoMinerSpec'
finish
