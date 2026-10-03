source diag/lib.sh
step nvs-pr-head pass 'git checkout 220123640 -- src/test' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
step cannonq-property pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "fills tx cache and stops"'
step nvs-with-property pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
finish
