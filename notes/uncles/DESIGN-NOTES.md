# Input-block uncles prototype: design notes

Branch `claude/ergo-input-uncles-i9lxmj`, created from `matrix-uncles-base` (80f638555). Prototype of
"chain + uncles" for Matrix input blocks: a later input block may merge up to two siblings ("uncles") of
elements of its chain, so that their transactions are collected and their work counts, while the chain depth
(weak confirmations) and dependent spends keep running at input-block speed.

Everything is behind the node setting `ergo.node.inputBlockUncles` (default `false`).

## Build and test status

**Nothing in this branch was compiled and no test was run.** The session had no sbt, and Maven Central and
GitHub releases were blocked by the network proxy (HTTP 403), so neither sbt nor the dependencies could be
fetched. The code was written by reading the types of the surrounding code. Places where the types were
uncertain are written conservatively, for example with explicit lambdas instead of eta-expansion of
polymorphic methods, and with no unused imports, locals or pattern variables, because sbt-tpolecat runs in its
default CI mode and makes warnings fatal.

**Results reported by the maintainer** (GitHub runners, base weak-blocks b2a9e7b00 + this branch):
- Compile errors in `ErgoMiningThread` (two `Candidate(...)` patterns with 4 fields) are fixed on the branch.
- Passing: `InputBlockUnclesSpec` 6/0, `InputBlockUnclesSpecification` 14/0 and `CandidateGeneratorUnclesSpec` 8/0.
- No regressions: `InputBlockProcessorSpecification` 65/0, `ErgoNodeViewHolderSpec` 150/0,
  `ErgoNodeViewSynchronizerSpecification` 96/0 and `ErgoSettingsSpecification` 7/0.
- `CandidateGeneratorSpec` is noisy on those runners, with 13-14 failures on the bare base too.
- `ErgoNodeViewHolderUnclesSpec`, "rebuilt from L and uncles", failed because of its fixture. Its input blocks
  carried the transactions of a generated full block, which include a reward transaction (soft field
  `minerPubKey`) and outputs created above the current height. Those blocks were rejected, so there was nothing
  to rebuild from. The fixture now uses ordinary transactions: A splits a genesis output, B (child of A) spends
  one half, and S (sibling of B) spends the other. The test first asserts that the input blocks were applied and
  that S was validated, and that `orderingBlockCollectedTransactions` gives A, B, S. Only then does it send the
  ordering block. The rewritten fixture has not been run yet.

Spec classes to run (new):

```
sbt "ergoCore/testOnly org.ergoplatform.mining.InputBlockUnclesSpec"
sbt "testOnly org.ergoplatform.nodeView.history.modifierprocessors.InputBlockUnclesSpecification"
sbt "testOnly org.ergoplatform.mining.CandidateGeneratorUnclesSpec"
sbt "testOnly org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderUnclesSpec"
```

Existing specs touching the changed code (regression; flag off, so behaviour should be unchanged):

```
sbt "ergoCore/testOnly org.ergoplatform.mining.InputBlockAnnouncementSpec org.ergoplatform.network.InputBlockMessageSpecsSpec"
sbt "testOnly org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorSpecification org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorConcurrencySpecification org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.CandidateRetryReorgSpec org.ergoplatform.mining.ErgoMiningThreadSpec org.ergoplatform.mining.ErgoMinerSpec org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderSpec org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification org.ergoplatform.network.InputBlockParentBindingSpec org.ergoplatform.network.OrderingBlockMessageFlowSpec org.ergoplatform.settings.ErgoSettingsSpecification"
```

`HistoryTestHelpers.generateHistory` got a new optional parameter `inputBlockUncles` (default `false`).

## Requirements → tests

| Behaviour | Spec |
| --- | --- |
| uncle accepted, its transactions in L in order | `InputBlockUnclesSpecification`: "uncle accepted and its transactions collected in order" |
| duplicate transactions deduplicated | "uncle transactions already collected are deduplicated" |
| conflicting sibling not mergeable | "sibling conflicting with the collected transactions is not mergeable"; generator side: `CandidateGeneratorUnclesSpec` "selectUncles skips a conflicting sibling…", "generator does not reference a conflicting sibling" |
| spending an uncle's output requires referencing it | "spending an output of a sibling's branch is invalid without referencing it", "spending an output of an uncle is valid when the uncle is referenced" |
| L over the cost cap → invalid | "collected transactions over the cost limit make the input block invalid" (limit −1 invalid, exact limit valid) |
| generator references a waiting sibling, the block validates | `CandidateGeneratorUnclesSpec` "generator references a waiting sibling and the resulting input block validates" |
| ordering block rebuilt from L, matching transactions root | `InputBlockUnclesSpecification` "ordering block transactions rebuilt from L match the transactions root"; `ErgoNodeViewHolderUnclesSpec` "ordering block transactions rebuilt from L and uncles, not downloaded" |
| flag off → as the base | "uncles disabled: …" in the processor, generator and view holder specs |
| siblings validated on arrival, validity recorded | "sibling is validated against its own prefix…", "sibling invalid against its own prefix…" |
| K = 2, merged once | "at most two uncles, each merged once" |
| solution judged against the candidate it was mined on | `CandidateGeneratorUnclesSpec` "input-block solution is judged against the candidate it was mined on" |
| encodings, commitment of the announcement | `InputBlockUnclesSpec` (ergo-core) |
| missing uncle downloaded, late uncle transactions | `ErgoNodeViewHolderUnclesSpec` "unknown uncle of an input block is requested"; processor "block waiting for its uncle's transactions is processed when they arrive" |

## Design decisions (following the brief, numbered as in it)

1. **Chain kept.** `InputBlocksChain`/`InputBlocksTree` and the fork code are unchanged in structure. An element
   may carry up to `K = InputBlockUncles.MaxUncles = 2` uncles. An uncle is a single input block of the same
   ordering block whose parent (`prevInputBlockId`) is a processed element of the referencing block's chain up to
   and including its parent, i.e. a sibling of the block itself, of its parent, or of an earlier ancestor. It must
   not be an element of the chain and must not be merged by an earlier element: each uncle counts once.
   *Choice:* an input block without a parent (a sibling of the first element) is also accepted as an uncle;
   the ordering block plays the role of element −1.
   *Choice:* an uncle's own uncle references are not merged transitively. If its transactions depend on them, the
   merge fails validation and the uncle is not mergeable.

2. **Where the references live.**
   - Extension key `0x03 0x03` (`Extension.InputBlockUnclesKey`): the concatenation of 0 to 2 ids, 0 to 64 bytes.
     With the flag on the generator **always** writes the field, empty when it merges nothing. Released nodes check
     extensions only by format and ignore the `0x03` prefix, and an empty value is allowed (`exValueLength` sets
     only a maximum), so this is a soft fork for released nodes.
   - Announcement: version `InputBlockUncles.UnclesMessageVersion = 2`. The uncle ids go in the trailing bytes
     that the version 1 serializer already keeps as `unparsedBytes` for `version > 1`: first the count, then the
     ids. Bytes after the ids are left for future fields. *Deviation from adding a parsed field:* the serializer is
     unchanged, so an old Matrix node parses, stores and relays version 2 announcements byte for byte.
   - `ExtensionCandidate.proofForInputBlockData` proves the uncles leaf along with the three existing input-block
     leaves when the field is present. A key that is absent is skipped, so the proof is identical without the
     field (covered in `InputBlockUnclesSpec`).
   - Check against the announcement: `InputBlockAnnouncement.unclesCommitted` requires the leaf
     `kvToLeaf(0x03 0x03, ids)` to be among the leaves proven by the announcement's batch proof. The synchronizer's
     existing `valid` already checks that proof against `header.extensionRoot`, so the ids are committed by the
     header.
   - **Strictness (deviation, justified):** with the flag on, the processor ignores, without storing it, any
     announcement whose uncles field is missing or not committed. That includes version 1 announcements. Otherwise
     a relay could strip the uncles. From a 4-leaf batch proof one can build a valid 3-leaf proof, because the
     uncles leaf hash simply becomes a sibling hash. The stripped copy would be stored as "no uncles", its
     transactions that spend uncle outputs would fail, and the correct copy would later be ignored as "already
     known". Requiring the field to be present on every block closes this hole: a stripped copy is not stored, so
     a correct copy can still be accepted. The price is that a flag-on node ignores input blocks of nodes without
     the flag (see 8).

3. **Collected order.** `L(block) = L(parent) ++ dedup(uncle1) ++ dedup(uncle2) ++ own`. Deduplication is by
   transaction id against everything collected so far, and the first appearance is kept. The same function
   (`collectTransactions`) is used by `InputBlocksChain.collectedTransactions`, which gives the generator L of
   the tip and the base of the validation, and by the ordering-block reconstruction (in strict mode, where any
   missing transaction means "cannot rebuild"). With the flag off, `uncleIdsOf` is always empty and the function
   behaves exactly like the old loop (missing transactions are skipped).
   *Choice:* an ordering block's transactions follow the same rule: L of the input block it links (`0x03 0x02`),
   then the deduplicated transactions of the uncles in its own extension (`0x03 0x03`), then its own
   transactions. The generator builds one candidate for both roles, and its ordering-block transaction list is
   `L(tip) ++ uncles ++ ordering transactions`, so receivers rebuild exactly that.

4. **Conflict rule.** Each uncle's deduplicated transactions are validated by `ErgoState.applyInputBlock`
   against everything collected before them (`L(parent)` and earlier uncles). That call rejects a double spend
   of any input across the previous and current transactions. The block's own transactions are then validated
   against `L(parent) ++ uncles`. A conflicting sibling therefore makes a referencing block invalid. The
   generator does not reference it: `selectUncles` checks input conflicts first (cheap), then validity. A
   transaction spending an output of an uncle's branch is valid only if that uncle is in the state view, i.e.
   referenced.

5. **Sibling validation.** When an input block's transactions arrive and it is neither the next block of the
   best fork nor a fork switch, the flag-on processor validates it against its own prefix: the fork in which it
   is the next block to complete, i.e. its parent is processed. It records `inputBlockValidity(id)` and
   `inputBlockCosts(id)` (`getInputBlockValidity`, `getInputBlockCost`).
   *Choice:* the tree is **not** changed. The sibling is not marked processed in its fork. Marking it would
   alter which fork is "best": `bestIndex` breaks ties by fork order, so the tip would flip between equal-depth
   siblings. Validity is also recorded for every block processed normally, which makes the old tip a candidate
   after a fork switch.
   *Limitation:* a sibling whose parent is not processed yet when its transactions arrive is not validated
   later. For example, its parent becomes processed through `applicationStep` continuation, and
   `registerCompletion` only advances forks whose next block is the first block applied. Such a sibling is never
   a generator candidate. Receivers still accept blocks that reference it, because they revalidate.
   *Late uncles:* if a block's uncle, or the uncle's transactions, is not known yet, validation returns
   `UnclesNotAvailable`. That is not a validity verdict and nothing is recorded. When the uncle's transactions
   arrive, `processWaitingOnUncle` processes the waiting blocks. The view holder downloads unknown uncles like
   unknown parents (`missingUncles` → `DownloadInputBlock`).

6. **Limits and costs.**
   - With the flag on, every input block's L must satisfy `cost(L) <= maxBlockCost - RewardCostReserve` and
     `size(L) <= maxBlockSize - RewardSizeReserve`, or the block is invalid. `cost(L)` is the sum of the fork's
     `processedBlocks` plus the block's own increment, which covers its uncles and its own transactions.
     `size(L)` is the sum of transaction sizes. The reserves are `RewardCostReserve = 100000` and
     `RewardSizeReserve = 4096` bytes. They are prototype values (see open questions).
   - Generator: `selectUncles` skips an uncle that would push `cost(L(tip)) + uncle` past the limit. With the flag
     on, `collectTxs` gets `maxBlockCost - max(safeGap, RewardCostReserve)` and `maxBlockSize - RewardSizeReserve`,
     so the new input transactions keep L inside the limit.
   - The base's two `registerCompletion(ib.id, costDelta = 0)` calls (marked by a todo comment) now pass the
     block's real cost, taken from the processed chain. Forks sharing the prefix gain the same cost.
   - With the flag off, no limit is enforced (as in the base). The existing spec "transactions with cumulative
     cost over block limit spread across 2 input blocks should be accepted" depends on that.

7. **Generator.**
   - `mergeableUncleCandidates()` (processor) returns siblings recorded valid, of the best ordering block, whose
     parent is in the best chain (or which have no parent), that are not in the chain and not merged by it, and
     whose transactions are available. They are sorted oldest first: by the parent's depth, then by timestamp.
     All input blocks of an ordering block have the same difficulty, so "most work" adds nothing to "oldest".
   - `createCandidate` (only when there is a best input block) selects up to 2 of them with `selectUncles`. Each
     uncle is validated with `UtxoState.applyInputBlock` (via `withTransactions(Seq.empty)`), the same check
     receivers run, against the parent ordering header. The extension gets the uncles field and the candidate
     gets `consideredUncles`.
   - Cache key: `cachedFor` also compares `consideredUncles` with the current `mergeableUncleCandidates()`, so a
     newly validated sibling triggers a regeneration on the next poll. The key uses the considered set, not the
     merged one, so an unmergeable sibling does not force a regeneration on every poll.
   - `prevTransactionsDigest` of the candidate stays the digest of `L(tip)` (uncles excluded), the value
     `cachedFor` compares against.
   - Input solutions: with the flag on, `inputSolutionCandidate` tries the current candidate, then
     `previousCandidates` (newest first, same parent: they are cleared on a parent change), and takes the first one
     whose input-block PoW fits. A block from an earlier candidate is sent to the node view. Its link may no
     longer be the tip, so it becomes a sibling. The current candidate is kept, and is regenerated once the new
     block is a mergeable sibling or the best tip. With the flag off only the current candidate is tried, as
     before.
   - `completeInputBlock` emits a version 2 announcement whenever the candidate has an uncles field.

8. **Version gate and mixed networks.** Everything is gated by `inputBlockUncles` (application.conf documents
   it). Flag off means identical processing: uncle references are ignored, there is no sibling validation, no
   L limit, no strictness, and the base reconstruction, generator and solution handling are used.
   *All Matrix nodes of a network must use the same value.*
   - **Old Matrix node** (no uncles support) or a flag-off node receiving a block with uncle references:
     - The version 2 announcement parses, and the trailing bytes are kept and relayed. The `0x03 0x03` key is
       ignored.
     - It validates the block's own transactions against its own L(parent), which has no uncle transactions.
     - If the block, or a descendant, spends an output created by a merged uncle (or by a transaction only the
       uncle has), that node finds the transaction spending a missing box. The block is not processed there, and
       its input-block chain stops at the parent: for that node the work and dependent spends are lost, but no
       peer is penalized for it.
     - If the merged block's own transactions do not depend on the uncle, the old node accepts the block, but
       its L lacks the uncle's transactions. Its later candidates may include those transactions again (they are
       still in its mempool). New nodes then see a double spend against their L and reject those blocks. So the
       input-block layer diverges between old and new nodes. This is why the flag is network-wide.
     - Ordering blocks stay compatible. The ordering block's transactions are `L ++ uncles ++ own`, a valid
       sequence that every node, released or Matrix, validates as an ordinary block. When an old node cannot
       rebuild the transactions from its own input blocks (Merkle root mismatch), it downloads them, as it
       already does.
   - **Flag-on node** receiving input blocks from old Matrix nodes or flag-off nodes: they are version 1 / without
     the committed field, so they are ignored (logged at warn level, not penalized). Their ordering blocks are
     processed normally.

9. **Ordering-block reconstruction.** The base did **not** do this consistently. `ErgoNodeViewHolder.processOrderingBlock`
   built `own ++ getCollectedInputBlocksTransactions(headerId)`. That is the wrong order (the generator puts the
   collected transactions first) and the wrong tree: input blocks are keyed by their ordering *parent*, so the
   tree of the new block's own id is empty. As a result, any ordering block with input-block transactions fell
   back to a full download. With the flag on, the view holder now calls
   `orderingBlockCollectedTransactions(header.parentId, extensionFields)`: the path from the first input block to
   the linked one (`0x03 0x02`) in the parent's tree, L along it, then the ordering block's uncles, then its own
   transactions. It checks the transactions root and falls back to the full download on a mismatch or when
   anything is missing.
   *Deviation:* with the flag off the base reconstruction is kept unchanged, to keep "flag off = base". The
   flag-off fix is correct, but it is a fix to the base, not part of uncles, and leaving it out keeps the
   flag-off comparison runs equal to the stack. Fixing it
   independently of uncles is a two-line change: use the parent's tree and put the collected transactions first.
   It is recommended.

## Other wiring

- `ErgoNodeViewHolder`:
  - When an input block becomes best, the transactions of its uncles are removed from the mempool. When it is
    rolled back, they are put back.
  - With the flag on, `txModify` validates mempool transactions against L of the best chain, so a transaction
    spending a merged uncle's output is accepted.
- `NodeConfigurationSettings.inputBlockUncles` (default `false`; the reader treats a missing key as `false`, so
  old configs work).
- `CandidateBlock` JSON gained `inputBlockFields.uncleIds`.

## Open questions / known gaps

- **Reserve values:** `RewardCostReserve = 100000` and `RewardSizeReserve = 4096` are guesses for the emission
  and fee-collection transactions. They should be measured. The generator estimates new transactions with
  `validateWithCost` per transaction, while validation uses the block cost from `applyInputBlock`. The reserve
  is assumed to cover any difference between the two.
- **Wallet:** `vault().scanInputBlock` / `rollbackInputBlock` are not called for uncle transactions. Wallet
  views of input-block (unconfirmed) transactions miss merged uncles until the ordering block arrives.
- **Strict field requirement** (decision 2) makes a flag-on node ignore old Matrix nodes' input blocks. An
  alternative is to accept version 1 announcements but not mark them "known" when their transactions fail.
  That alternative is more complex and still exposed to stripping. Choose one before a public test network.
- **Pre-existing, not fixed:** in `processInputBlockTransactions` the completion of other forks does
  `updTree = new InputBlocksTree(forks.updated(idx, ibc))`. Because that starts from `forks`, it drops the
  update just made from `r._1`. It probably should be `updTree.forks.updated(...)`. It is left as is because
  changing it alters fork selection with the flag off.
- **Pre-existing, not fixed:** an invalid first child at some depth stays the next block of the best fork. A
  valid sibling child at the same depth is then never processed as best: ties go to the lower fork index, and
  `switchNeeded` requires a strictly deeper fork. Uncles reduce the cost of this, because the valid sibling is
  validated and can be merged, but they do not fix it.
- **Pre-existing, not fixed:** the generator writes the input-transactions digest into the `0x03 0x01` leaf
  ("previous transactions digest") and puts the real previous digest only in the announcement. The commitment
  check therefore covers only the uncles leaf, not `0x03 0x01`. Likewise, nothing checks that the announced
  `prevInputBlockId` equals the proven `0x03 0x02` leaf.
- Ordering by `(parent depth, timestamp)` relies on miner-set timestamps. A local arrival counter would be more
  robust.
- `InputBlocksProcessor.scala` was already over the 800-line style limit (1269 lines) and is now about 1630
  lines. Moving the uncle helpers into their own trait would be a follow-up.
- `AGENTS.md` asks contributors to change only `src/test`. This task explicitly required production changes, so
  `src/main` and `ergo-core/src/main` were changed as requested.

## Files

- ergo-core:
  - `Extension.InputBlockUnclesKey`
  - `InputBlockFields.uncleIds` and `toExtensionFields(..., uncleIdsOpt)`
  - `ExtensionCandidate.proofForInputBlockData`
  - `subblocks/InputBlockUncles` (constants, encodings, leaf hash)
  - `InputBlockAnnouncement.uncleIdsOpt` / `uncleIds` / `unclesCommitted`
- node:
  - `InputBlocksProcessor` (L, uncle validation, sibling validation, limits, costs, getters, reconstruction)
  - `ErgoHistoryReader` (flag)
  - `CandidateGenerator` (selection, cache key, candidate history for input solutions, version 2
    announcements)
  - `ErgoNodeViewHolder` (reconstruction, uncle download, mempool)
  - `NodeConfigurationSettings`, `application.conf`
- tests:
  - `InputBlockUnclesTestHelpers` (shared fixtures)
  - the four new specs listed above
  - `HistoryTestHelpers.generateHistory(inputBlockUncles)`

## Round 2: the reconstruction finding

The failure at `ErgoNodeViewHolderUnclesSpec.scala:136` came from the **test**, not the reconstruction.
- **What the test got wrong:** `ProcessOrderingBlock` first appends the ordering block's header (`pmodModify`).
  With nothing applicable yet, `pmodModify` calls `requestDownloads(progressInfo)`, which publishes a
  `DownloadRequest` for the header's missing sections, block transactions included. This happens before the
  reconstruction and whatever it decides. The test failed on the first `DownloadRequest` carrying the block
  transactions id, which was that one.
- **What it checked, against items (a)-(d):** the check just before it, run from the test thread on the
  receiving node's history, already passed. That check calls the receiver's own function
  (`orderingBlockCollectedTransactions`) and got A, B, S in order. So, on the receiving side:
  - (a) the order is L(B) ++ deduplicated S ++ own;
  - (b) it is taken from the tree of the ordering block's parent;
  - (c) S's transactions are pulled in on the receiving side;
  - (d) with the flag on, the base's own-then-collected order is not used on this path.

The base's "Merkle root does not match" message is only logged on the flag-off path. The flag-off control test
runs the same fixture, so those log lines are most likely from that test.

Changes:
- `InputBlocksProcessor.collectedTransactionsFor(orderingParentId, prevInputBlockId, uncles)` is the one function
  for "L of the linked input block plus deduplicated uncles". `orderingBlockCollectedTransactions` (receiver) and
  `CandidateGenerator.createCandidate` (producer, flag on) both use it. The generator falls back to the previous
  computation only if it returns None. It logs the producer's collected ids at debug level.
- `InputBlocksProcessor.rebuildOrderingBlockTransactions(header, fields, own)` rebuilds the transactions and checks
  the root. The node view holder uses it with the flag on. On a mismatch its message lists both the rebuilt
  collected and own ids, the computed root and the header's root, and the view holder logs that message before
  falling back to the download. The flag-off path is unchanged.
- The view-holder spec now asserts `rebuildOrderingBlockTransactions(...) == Right(A, B, S)` on the receiving node
  before sending the ordering block. After sending, it waits for the block transactions to be applied, since
  nothing else supplies them in the test, instead of failing on the first download request. The flag-off control
  also asserts that they are not applied. `InputBlockUnclesSpecification` covers the rebuild function, both match
  and mismatch.
