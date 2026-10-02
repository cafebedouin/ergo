source diag/lib.sh
step green-throttle-props pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "throttle interval" -z "throttled V1"'
step green-tracker pass "" 'org.ergoplatform.network.ErgoSyncTrackerSpecification'
step red-base-processSync fail 'git checkout 8769baace -- src/main/scala/org/ergoplatform/network/ErgoNodeViewSynchronizer.scala' 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "throttle interval"'
step red-fullpath-status-check fail "sed -i 's/copy(lastSyncGetTime = Some(System.currentTimeMillis() + 60000))/copy(lastSyncGetTime = None)/' src/test/scala/org/ergoplatform/network/ErgoNodeViewSynchronizerSpecification.scala" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "throttle interval"'
step red-mutant-V1-case fail "perl -0pi -e 's/(raiseOnly = false\)\n        \}\n        case _ =>)/\$1 syncTracker.getStatus(remote).foreach(_ => syncTracker.updateStatus(remote, org.ergoplatform.consensus.Equal, None))/' src/main/scala/org/ergoplatform/network/ErgoNodeViewSynchronizer.scala && grep -q 'updateStatus(remote, org.ergoplatform.consensus.Equal' src/main/scala/org/ergoplatform/network/ErgoNodeViewSynchronizer.scala" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification -- -z "throttled V1"'
step green-full-nvs pass "" 'org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification'
finish
