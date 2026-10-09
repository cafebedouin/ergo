# Header-level input-block uncles: notes

Branch `matrix-uncles-header`, created from `claude/ergo-input-uncles-i9lxmj` at 8a5b9f25c (the
transaction-merging prototype, see `DESIGN-NOTES.md`), on top of `matrix-uncles-base` (80f638555).

Ethereum-style header-level uncles for Matrix input blocks. A later input block **references** up to two
PoW-valid sibling input blocks for credit. The siblings' transactions are **not** executed, merged or relayed.
The rule is non-strict: a reference never makes a block invalid and never changes fork choice. It is either
credited or not. Everything is behind the existing node setting `ergo.node.inputBlockUncles` (default `false`).

## Build and test status

**Nothing on this branch was compiled and no test was run.** The session had no sbt, and Maven Central was blocked
by the network proxy (HTTP 403). The code was written by reading the surrounding types.
- The kept ergo-core code is the starting branch's, which the maintainer compiled and ran.
- New code avoids unused imports, locals and pattern variables, because sbt-tpolecat makes warnings fatal.
- Places worth a first look if the build fails:
  - `CandidateGenerator`: the eta-expansion `powScheme.checkInputBlockPoW`. It was taken from the starting
    branch, which compiled.
  - `BlocksApiRoute`: `Json.obj(... : _*)` and `deepMerge`.
  - `CandidateBlock`: `Map ++ List` in the JSON encoder.

Spec classes to run. New or rewritten:

```
sbt "ergoCore/testOnly org.ergoplatform.mining.InputBlockUnclesSpec"
sbt "testOnly org.ergoplatform.nodeView.history.modifierprocessors.InputBlockUnclesSpecification org.ergoplatform.mining.CandidateGeneratorUnclesSpec org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderUnclesSpec"
```

Existing specs, two of which gained a property (`ErgoNodeViewSynchronizerSpecification`: "NewInputBlockSibling
announces the sibling's id only"; `BlocksApiRouteSpec`: "report credited uncles … only with input-block uncles
enabled"):

```
sbt "ergoCore/testOnly org.ergoplatform.mining.InputBlockAnnouncementSpec org.ergoplatform.network.InputBlockMessageSpecsSpec"
sbt "testOnly org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorSpecification org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorConcurrencySpecification org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.CandidateRetryReorgSpec org.ergoplatform.mining.ErgoMiningThreadSpec org.ergoplatform.mining.ErgoMinerSpec org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderSpec org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification org.ergoplatform.network.InputBlockParentBindingSpec org.ergoplatform.network.OrderingBlockMessageFlowSpec org.ergoplatform.settings.ErgoSettingsSpecification org.ergoplatform.http.routes.BlocksApiRouteSpec"
```

(`CandidateGeneratorSpec` was reported noisy on the maintainer's runners on the bare base too.)

## Diff size against `matrix-uncles-base` (80f638555)

| Part | Files | + | − |
| --- | --- | --- | --- |
| Production (`src/main`, `ergo-core/src/main`, `application.conf`, `openapi.yaml`) | 15 | 490 | 40 |
| Tests (`src/test`, `ergo-core/src/test`) | 8 | 878 | 2 |
| All except `notes/` | 23 | 1368 | 42 |
| Everything, including `notes/uncles/` (both notes files) | 25 | 2076 | 42 |

For comparison, the starting branch (8a5b9f25c) was 20 files, +2044 / −108 against the same base, excluding
`notes/`. The full diff, including both notes files, is printed by `git diff --stat 80f638555 matrix-uncles-header`
(`DESIGN-NOTES.md` +405, this file).

## What is kept, dropped, added

**Kept from the starting branch:**
- ergo-core:
  - `Extension.InputBlockUnclesKey` (0x03 0x03, up to two 32-byte ids).
  - `InputBlockFields.uncleIds`, `toExtensionFields(..., uncleIdsOpt)`.
  - `ExtensionCandidate.proofForInputBlockData` proves the uncles leaf when present. The proof is unchanged when
    the field is absent.
  - `InputBlockUncles` encodings: field value, version 2 announcement bytes, leaf hash.
  - `InputBlockAnnouncement.uncleIdsOpt` / `unclesCommitted`: the announced ids must be a leaf proven by the
    announcement's batch proof.
- Node:
  - The setting and its reader (a missing key reads as `false`).
  - Candidate history for input solutions (`inputSolutionCandidate`).
  - The sibling announcement relay event `NewInputBlockSibling`, with a new trigger (see decision 7).
- Tests: `HistoryTestHelpers.generateHistory(inputBlockUncles)` and most of `InputBlockUnclesTestHelpers`.

**Dropped (restored to the base, no dead code left):**
- The processor's L with uncles, the conflict rule, the collected cost and size limits, and `RewardCostReserve` /
  `RewardSizeReserve`.
- Sibling transaction validation, the validity and cost maps, `processWaitingOnUncle`, `missingUncles` and the
  uncle download.
- The ordering-block uncles semantics, `collectedTransactionsFor`, `orderingBlockCollectedTransactions`,
  `rebuildOrderingBlockTransactions` and the reconstruction diagnostics.
- The pending-rebuild map and its timer, and the synchronizer's "hand every ordering block to the view holder".
- The view holder's mempool and wallet handling of merged transactions, and `txModify` over L with uncles.
- `candidateParentInputBlock`: the generator builds on the base's `bestBlocks._2` again.
- `selectUncles`, `Candidate.consideredUncles` and the five-field `Candidate` patterns in `ErgoMiningThread`.
- The strict field requirement.

**Added:**
- `InputBlockUncles.fieldViolation`.
- The processor's credit:
  - `committedUncleIds`, `knownChainOf`, `uncleRuleViolation`, `creditFor` and `refreshUncleCredits`;
  - the maps `inputBlockArrival` and `creditedUncles`;
  - the getters `getCreditedUncles`, `uncleCandidates` and `isSiblingAnnouncement`.
- The generator's field, version 2 announcement and cache key.
- The REST field.

## Decisions

1. **The structural rule** is shared by the generator and the validator: `InputBlockUncles.fieldViolation` and
   `InputBlocksProcessor.uncleRuleViolation`.
   - **The field** must be committed, that is, a version 2 announcement whose uncles leaf is in the batch proof,
     and that proof must be valid against the header's extension root. The processor checks the proof itself,
     so credit does not depend on the synchronizer path. The field must also be well-formed: 0, 32 or 64 bytes
     (`parseAnnouncementBytes` / `parseFieldValue`), no duplicate id, and no self-reference.
   - **Each referenced id** must:
     - name a known input block, which means PoW-checked (see open question 3);
     - belong to the same ordering block, i.e. have the same `header.parentId`;
     - not be on the referencing block's chain;
     - have its parent on that chain, or have no parent. A first input block of the ordering block counts, with
       the ordering block playing element −1, as on the starting branch;
     - not be credited to an ancestor on that chain.
   - The chain is followed through the **announcements' parent links** (`knownChainOf`), not through processed
     state. The rule therefore depends only on which announcements a node has, never on transactions.
2. **"Referenced at most once along a chain" is read as "credited at most once".** A reference by an ancestor that
   was itself not credited (for example, the sibling was not a valid uncle for the ancestor) does not block a
   descendant. A plain "referenced" reading would let one bad reference waste a valid uncle. With full
   information both readings give each uncle at most one credit on a chain.
3. **Non-strict.** Nothing a validator computes about state, L or fork choice reads the field or the credit.
   - The credit map is written only in `applyInputBlock`, after the block is stored and inserted in the tree,
     exactly as on the base.
   - A missing field, a stripped field, a malformed field or a failing reference leaves the block's processing
     untouched. It only means no credit (or less) for that block.
   - Version 1 announcements and blocks without the field are accepted exactly as on the base.
4. **Re-evaluation is implemented.**
   - When to re-evaluate: every new announcement of an ordering block, so a late sibling, or a late ancestor of a
     waiting block, is picked up.
   - What it does: `refreshUncleCredits` recomputes the credit of all known input blocks of that ordering block,
     shortest chains first, so that ancestors are evaluated before descendants.
   - Effect: credit converges to the full-information result as announcements arrive, and adding an
     announcement never removes an earlier credit (see open question 2).
   - Cost: O(R × depth) per announcement, where R is the number of input blocks of the ordering block.
5. **The field is written only when there is something to reference.** With the flag on and no uncle candidate,
   the generator writes no `0x03 0x03` key and announces with version 1, exactly like a flag-off node.
   - *Deviation from the starting branch*, which always wrote the field (possibly empty) with the flag on.
   - Reason: with a non-strict rule an empty field carries no information, and omitting it keeps flag-on blocks
     without uncles byte-identical in format to the base.
6. **Generator.**
   - Candidates are `uncleCandidates()`: the known input blocks passing the uncle rule for a child of the best
     input block (`bestBlocks._2`, as on the base). They are ordered by a **local arrival counter** (first seen),
     not by miner timestamps, and the first `K = 2` are taken.
   - The cache key (`cachedFor`) compares the candidate's referenced ids with the current selection, so a newly
     seen sibling regenerates the candidate. It compares the selection (top 2), not all candidates, so a
     third sibling does not force a regeneration.
   - L, `prevTransactionsDigest` and the ordering-block transactions are the base's. The uncles' transactions
     are not collected.
   - Input solutions (flag on) are judged against the current candidate, then the previous ones on the same
     parent. A block mined on an earlier candidate is sent to the node view, where it is a sibling, is relayed,
     and can be referenced. With the flag off only the current candidate is tried, as on the base.
7. **Sibling announcement relay.**
   - The trigger, `isSiblingAnnouncement`, is computed before the announcement is stored: the announcement is
     not known yet, it belongs to the best ordering block, and its parent (or the ordering block, for a first
     input block) already has a known child. Both received and locally generated announcements qualify.
   - The view holder then publishes `NewInputBlockSibling`.
   - The synchronizer sends an `Inv` with the id to the input-block peers, for local and received blocks alike.
     A peer that lacks the block requests the announcement (`processInv` → `modifiersReq` →
     `processInputBlockRequest`).
   - An announcement extending the chain is relayed as on the base, when it becomes the best input block.
   - *Deviation from the starting branch*, which relayed a sibling once its transactions were validated. Here no
     transaction is involved, so the sibling is relayed on arrival.
8. **The uncles key in ordering blocks.** The generator builds one candidate for both roles, and the extension is
   shared. A candidate that references uncles therefore also carries `0x03 0x03` when it is completed as an
   ordering block.
   - No node gives the key any meaning in an ordering block: no credit, and reconstruction is the base's.
   - Released and older Matrix nodes accept it: the key is 2 bytes and the value at most 64 bytes, which is
     what the extension rules require.
   - Dropping it would need separate extensions per role, which would change the header commitment. Not done.
9. **Credited uncles over REST** (one extra field, flag on only; flag off returns the base response):
   - `GET /blocks/bestInputBlock` gets `creditedUncles`, the ids credited to the best input block.
   - `GET /blocks/bestInputChain` gets `creditedUncles`, an object mapping each input block id of the chain to
     its credited ids, so that a test network can count credits per ordering block.
   - Both are documented in `openapi.yaml`.
10. **Flag off = the base.** The processor records nothing (no arrival counter, no credit), there are no
    candidates, no relay events and no REST field. The generator writes no field and announces version 1. The
    candidate JSON (`CandidateBlock`) carries `uncleIds` only when uncles are referenced, so it is the base's
    too. `proofForInputBlockData` is unchanged without the key. `cachedFor` compares an empty selection with an
    absent field, which is always equal.

## Mixed networks

- **A flag-off node, or an older Matrix node, receiving a flag-on node's version 2 announcement and `0x03 0x03`
  key:**
  - The base serializer parses version > 1 and keeps the trailing bytes as `unparsedBytes`.
  - The batch proof (four leaves) validates against the extension root.
  - The block is stored and processed like any other. Its transactions and L are the same as without uncles.
  - The key is ignored, and nothing is credited.
  - The node relays it as on the base: by id, and it serves the stored announcement, which re-serializes to the
    same bytes. This is covered in `CandidateGeneratorUnclesSpec`.
  - An ordering block carrying the key is an ordinary block for it.
- **A flag-on node receiving flag-off or older Matrix nodes' input blocks:** version 1, no field. They are
  accepted, stored, processed and relayed exactly as on the base, with no credit. They can themselves be
  referenced as uncles by flag-on miners.
- **Disagreement.** Nodes may disagree about **credit**: one has seen a sibling another has not, or received a
  stripped copy first. They never disagree about validity, L or the best chain because of uncles.

## Deviations from the prompt

- **Self-reference test.** A real block cannot reference itself, because its id is the hash of a header that
  commits to the field. The self-reference case is therefore tested on the shared field rule
  (`InputBlockUncles.fieldViolation`), in `InputBlockUnclesSpecification` and in the ergo-core spec, not through
  a block.
- **Sibling relay sends the id only.** The prompt says "announcements only". What this branch adds sends only an
  announcement id and serves only announcements.
  - A receiving peer's **base** code then handles the announcement like any input block. It asks for the
    transaction ids (`requestInputBlockTransactionIds`) or resolves them from its mempool, and may download the
    transactions.
  - That path is unchanged, so that flag-off peers behave exactly as on the base. Suppressing it for siblings
    would change the base receive path for every node.
  - No sibling transaction is executed into L, and the node never pushes sibling transactions or bodies on its
    own.
- **`AGENTS.md`** asks contributors to change only test code and to put LLM-generated specs under
  `llm_generated/`.
  - This task requires production changes, so `src/main` and `ergo-core/src/main` were changed.
  - It names spec classes in the regular packages, so the specs stay where the starting branch had them.

## Starting-branch specs removed or rewritten

- `InputBlockUnclesSpecification`: all properties removed and the class rewritten for credit. Removed:
  - "sibling is validated against its own prefix on arrival and becomes an uncle candidate"
  - "sibling invalid against its own prefix is recorded invalid and is not an uncle candidate"
  - "uncle accepted and its transactions collected in order: L(parent), uncle, own"
  - "uncle transactions already collected are deduplicated (first appearance kept)"
  - "sibling conflicting with the collected transactions is not mergeable"
  - "spending an output of a sibling's branch is invalid without referencing it"
  - "spending an output of an uncle is valid when the uncle is referenced"
  - "at most two uncles, each merged once"
  - "block waiting for its uncle's transactions is processed when they arrive"
  - "unknown uncles are reported as missing"
  - "collected transactions over the cost limit make the input block invalid"
  - "announcement without a committed uncles field is ignored when uncles are enabled"
  - "uncles disabled: uncle references are ignored, behaviour as without uncles support"
  - "ordering block transactions rebuilt from L match the transactions root"
  - "each reason for collected transactions not being available names what is missing"
- `CandidateGeneratorUnclesSpec`:
  - Removed:
    - the three `selectUncles` properties;
    - "generator does not reference a conflicting sibling";
    - "generator and validation agree on uncles when siblings were merged earlier and the chain has an
      unprocessed tail".
  - Rewritten:
    - "cached candidate is stale when uncle candidates change";
    - "generator references a waiting sibling and the resulting input block validates";
    - "uncles disabled: …".
  - Kept: "input-block solution is judged against the candidate it was mined on".
- `ErgoNodeViewHolderUnclesSpec`: all five removed, replaced by the two relay properties:
  - "unknown uncle of an input block is requested"
  - "ordering block transactions rebuilt from L and uncles, not downloaded"
  - "uncles disabled: ordering block transactions are not rebuilt, they are downloaded as before"
  - "ordering block linking an input block mined locally moments earlier, uncle arriving right after"
  - "ordering block whose chain element merges an uncle, received right around the uncle"
- `InputBlockUnclesSpec` (ergo-core): all six kept; "field rule" added.

## Requirements → specs

| Requirement | Spec (class: property) |
| --- | --- |
| field round-trip, binding to the extension proof | `InputBlockUnclesSpec`: round trip, malformed, "version 2 announcement repeats committed uncles…", "announced uncles other than the committed ones are not committed", "proof is unchanged…" |
| generator references a seen PoW-valid sibling, block validates | `CandidateGeneratorUnclesSpec`: "generator references a seen PoW-valid sibling…" |
| K = 2, by arrival, not already credited | `CandidateGeneratorUnclesSpec`: "generator takes at most two siblings, by arrival…"; `InputBlockUnclesSpecification`: "uncle candidates…" |
| three ids / malformed length / self-reference / duplicate | `InputBlockUnclesSpecification`: "three ids…", "malformed length…", "self-reference…", "duplicate id…" (+ ergo-core "field rule") |
| uncle from another ordering block / parent not on chain / already credited / unknown | `InputBlockUnclesSpecification`: "uncle from another ordering block…", "uncle whose parent is not on the chain, or which is on the chain…", "uncle already credited to an ancestor…", "unknown id…; credited once the sibling's announcement arrives" |
| each failing case: block valid, reference uncredited | each of the above asserts `(Seq(id) -> Seq.empty)` (processed as best) and the credit; where possible a valid reference is credited alongside |
| stripped field: valid, no credit | `InputBlockUnclesSpecification`: "stripped field…" |
| flag off = base | processor "uncles disabled…", generator "uncles disabled…", view holder "uncles disabled: no sibling relay", REST spec |
| mixed | `InputBlockUnclesSpecification`: "version 1 announcements … accepted"; `CandidateGeneratorUnclesSpec`: "mixed: a flag-on node accepts a flag-off node's input blocks", "mixed: a flag-off node parses, ignores and relays unchanged…" |
| L with uncles == L without | `InputBlockUnclesSpecification`: "L of a block with uncles equals L of the same block without them" (+ the first property checks L excludes the sibling's transaction) |
| sibling announcement relay | `ErgoNodeViewHolderUnclesSpec`; `ErgoNodeViewSynchronizerSpecification`: "NewInputBlockSibling announces the sibling's id only" |
| REST field | `BlocksApiRouteSpec`: "report credited uncles…" |

Each would fail without its code: the processor, generator and view-holder specs call methods that do not exist
on the base (`getCreditedUncles`, `uncleCandidates`, `isSiblingAnnouncement`, `fieldViolation`,
`cachedFor(..., selectedUncles)`, `inputSolutionCandidate`, `NewInputBlockSibling`), and the behavioural
assertions (credit present/absent, version 2, field written/not) fail with the respective piece removed.

Fixture note: a sibling that is a child of the best input block's parent while the tip has no child yet is made a
child of an earlier element in a few fixtures. Otherwise the base would append it to the best fork's tail (it
links the fork's last element), and the referencing block, which forks from the same parent, would not be
processed. This is base fork handling, unchanged here.

## Open questions

1. **The reward question** (why this branch exists): should credited sibling work count, and how much? This
   branch only records and exposes credit. It does not pay anything.
2. **Credit is local accounting.** Two nodes can disagree:
   - when one has not seen a sibling;
   - when one received a stripped copy first. The full copy is then ignored as "already known", as on the base,
     so that node never credits that block. Keeping the better copy's field would fix it, at the cost of
     replacing stored announcements.
   Using credit for rewards needs a deterministic source, for example the ordering block committing to the
   credited set, or uncles being validated at ordering-block time.
3. **"PoW-valid" means "known".** The synchronizer checks input-block PoW before storing an announcement only when
   it has a UTXO state reader (`usrOpt.map(...).getOrElse(true)`), so a digest-mode node stores unchecked ones.
   The processor could re-check PoW with the parameters, which it does not have today.
4. **Fetching unknown uncles.** The starting branch downloaded unknown uncles' announcements. This branch does
   not: credit is re-evaluated when the announcement arrives through normal relay. Requesting a missing
   referenced announcement (announcement only) would make credit converge faster.
5. **Relay gap.** The first child of a parent is relayed only if it becomes the best input block, through the
   base path. A first child whose transactions never arrive, so that it never becomes best, is not relayed as a
   sibling. Later competitors are relayed.
6. **Uncle depth** is bounded only by the ordering block: an uncle's parent may be any ancestor. Ethereum bounds
   it (6 generations). Whether a bound is wanted depends on the reward answer.
7. **The base receive path** fetches sibling transactions on announcement (see deviations). Whether siblings
   should be announcement-only end to end is a protocol choice for the base.
8. **The cost of `refreshUncleCredits`** grows with the number of input blocks per ordering block. If needed,
   recompute only the blocks whose chain contains the new announcement's parent, or which reference it.
9. `InputBlocksProcessor.scala` was already over the 800-line style limit on the base (1269 lines). It is now
   about 1460 lines. Moving the credit code into its own trait would be a follow-up.
