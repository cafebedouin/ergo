source diag/lib.sh
# the one test that failed once on the snippet: repeated on the snippet and on #2637 as is
step cont-snippet-1 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-2637-1 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-snippet-2 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-2637-2 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-snippet-3 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-2637-3 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-snippet-4 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-2637-4 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-snippet-5 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-2637-5 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-snippet-6 pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
step cont-2637-6 pass 'git checkout 61d11f612 -- src/main' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "apply continuation header from syncV2"'
finish
