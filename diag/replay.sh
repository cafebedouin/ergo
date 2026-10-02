source diag/lib.sh
step green-adproofs pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "does not request announced ADProofs"'
step red-without-fix fail 'git checkout 9ad2aecb2 -- src/main/scala/org/ergoplatform/network/ErgoNodeViewSynchronizer.scala' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "does not request announced ADProofs"'
step green-full-nvs pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
finish
