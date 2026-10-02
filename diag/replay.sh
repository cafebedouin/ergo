source diag/lib.sh
step green-popow pass "" 'org.ergoplatform.nodeView.history.PopowProcessorSpecification'
step red-base-main fail 'git checkout 972311a86 -- src/main' 'org.ergoplatform.nodeView.history.PopowProcessorSpecification -- -z "after a restart"'
finish
