source diag/lib.sh
step green pass "" 'org.ergoplatform.nodeView.history.BlockSectionValidationSpecification -- -z "ADProofs with right headerId but wrong digest"'
step red-mutant-rule302-off fail "sed -i 's/\.validate(bsCorrespondsToHeader, header.isCorrespondingModifier(m),/.validate(bsCorrespondsToHeader, true,/' src/main/scala/org/ergoplatform/nodeView/history/storage/modifierprocessors/FullBlockSectionProcessor.scala && grep -q 'bsCorrespondsToHeader, true' src/main/scala/org/ergoplatform/nodeView/history/storage/modifierprocessors/FullBlockSectionProcessor.scala" 'org.ergoplatform.nodeView.history.BlockSectionValidationSpecification -- -z "ADProofs with right headerId but wrong digest"'
finish
