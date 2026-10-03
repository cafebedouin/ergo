source diag/lib.sh
step green-thread-spec pass "" 'org.ergoplatform.mining.ErgoMiningThreadSpec'
step red-base-thread fail 'git checkout 8769baace -- src/main/scala/org/ergoplatform/mining/ErgoMiningThread.scala' 'org.ergoplatform.mining.ErgoMiningThreadSpec'
step candidate-generator-with-change fail "" 'org.ergoplatform.mining.CandidateGeneratorSpec'
step candidate-generator-on-base fail 'git checkout 8769baace -- src/main/scala/org/ergoplatform/mining/ErgoMiningThread.scala' 'org.ergoplatform.mining.CandidateGeneratorSpec'
step miner-spec-with-change fail "" 'org.ergoplatform.mining.ErgoMinerSpec'
step miner-spec-on-base fail 'git checkout 8769baace -- src/main/scala/org/ergoplatform/mining/ErgoMiningThread.scala' 'org.ergoplatform.mining.ErgoMinerSpec'
finish
