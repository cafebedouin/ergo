source diag/lib.sh
step green-opt3-prop pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "header Inv on our best chain"'
step green-tracker pass "" 'org.ergoplatform.network.ErgoSyncTrackerSpecification'
step red-base-synchronizer fail 'git checkout 8769baace -- src/main/scala/org/ergoplatform/network/ErgoNodeViewSynchronizer.scala' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "header Inv on our best chain"'
step measure-400-id-lookup pass 'cp diag/RaiseLookupCostMeasureSpecification.scala src/test/scala/org/ergoplatform/nodeView/history/' 'org.ergoplatform.nodeView.history.RaiseLookupCostMeasureSpecification'
step green-full-nvs pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
finish
