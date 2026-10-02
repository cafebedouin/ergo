source diag/lib.sh
step green-mined-block pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "newly mined block"'
step red-mutant-no-eviction fail "perl -0pi -e 's/minedToServe = minedToServe.filterNot\(_.contains\(id\)\)/minedToServe = if (id.isEmpty) minedToServe.filterNot(_.contains(id)) else minedToServe/' src/main/scala/org/ergoplatform/network/ErgoNodeViewSynchronizer.scala && grep -q 'if (id.isEmpty)' src/main/scala/org/ergoplatform/network/ErgoNodeViewSynchronizer.scala" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "newly mined block"'
step green-full-nvs pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
finish
