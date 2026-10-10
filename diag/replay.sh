source diag/lib.sh
T=src/main/scala/org/ergoplatform/mining/ErgoMiningThread.scala
TS=org.ergoplatform.mining.ErgoMiningThreadSpec; CS=org.ergoplatform.mining.ErgoMiningThreadChainSpec
GS=org.ergoplatform.mining.CandidateGeneratorSpec; MS=org.ergoplatform.mining.ErgoMinerSpec
# reds: the weak-blocks tip's thread (b2a9e7b00), the previous PR head's thread (7ea4cf2a3), mutants of this change, the thread before coalescing (26e6e9251)
step tip-thread fail "git checkout b2a9e7b00 -- $T" "$TS $CS"
step old-head-thread fail "git checkout 7ea4cf2a3 -- $T" "$TS $CS"
step mutant-no-drop fail "python3 - <<'P'
p='$T'; s=open(p).read(); a='''    case MineCmd if awaitingCandidate =>
      mineCmdPending = false
'''; assert s.count(a)==1; open(p,'w').write(s.replace(a,''))
P" "$TS"
step mutant-flag-never-set fail "sed -i 's/^      awaitingCandidate = true\$/      \/\/ MUTANT: flag never set/' $T && grep -q 'MUTANT: flag' $T" "$TS"
step mutant-kept-ignored fail "python3 - <<'P'
p='$T'; s=open(p).read(); a='''      } else if (awaitingCandidate) {
        // the generator kept this candidate after the rejection: search on after the rejected nonce
        awaitingCandidate = false
        enqueueMineCmdIfIdle()
      }'''; assert s.count(a)==1; open(p,'w').write(s.replace(a,'      }'))
P" "$TS"
step mutant-no-coalesce fail "sed -i 's/^    if (!mineCmdPending) {\$/    if (!mineCmdPending || true) {/' $T && grep -q 'mineCmdPending || true' $T" "$CS"
step before-coalesce-thread fail "git checkout 26e6e9251 -- $T" "$CS"
# greens at this head
step new-head-thread pass "" "$TS $CS"
finish
