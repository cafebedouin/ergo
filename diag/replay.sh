source diag/lib.sh
# the whole suite twice per tree: does the snippet add failures beyond the suite's own timing failures?
step green-2637-property pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "full V2 sync info is sent after a reduced one"'
step nvs-snippet-1 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
step nvs-snippet-2 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
step nvs-2637-1 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
step nvs-2637-2 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
step nvs-v608-1 pass 'git checkout 3a6b00d37 -- src' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
step nvs-v608-2 pass 'git checkout 3a6b00d37 -- src' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
finish
