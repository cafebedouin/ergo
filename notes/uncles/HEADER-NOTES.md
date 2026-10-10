# Header-level input-block uncles: notes

Branch `matrix-uncles-header`, created from `claude/ergo-input-uncles-i9lxmj` at 8a5b9f25c (the
transaction-merging prototype, see `DESIGN-NOTES.md`), on top of `matrix-uncles-base` (80f638555).

Ethereum-style header-level uncles for Matrix input blocks. A later input block **references** up to two
PoW-valid sibling input blocks for credit. The siblings' transactions are **not** executed, merged or relayed.
The rule is non-strict: a reference never makes a block invalid and never changes fork choice. It is either
credited or not. Everything is behind the existing node setting `ergo.node.inputBlockUncles` (default `false`).

## Build and test status

**Round 1 (7ad049247), verified by the maintainer on GitHub:** compiles as written, all new specs green over 2
repeats, existing specs match the baseline, and a mutation crediting nothing turns all 9 credit-asserting
properties red. Round 3 was verified on GitHub and rechecked on the network (18 runs). Round 4 was verified on GitHub (compiled with no fixes, new specs green, no regression, each change killed by
a mutant). Round 5 likewise (zero compile fixes, specs green over 2 repeats, no regression, each fix killed by
its mutant). **Rounds 2 to 6 (see the end of this
file) were not compiled or run here either.** Round 6 was then verified on GitHub (compiled with no fixes, new specs
green over 2 repeats, the new on-demand path killed by its mutant; one timing test, OrderingBlockMessageFlowSpec, was
red in 2 of 7 round-6 jobs against 0 of 17 on the starting branch, an open watch item) and run on the network as the
header-level build of the final run set (21 dispatches, 76 runs).

**Round 1 was written without compiling or running anything here.** The session had no sbt, and Maven Central was blocked
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
     exactly as on the base. Since round 2 the refresh runs in its own `Try`: a failure is logged and the
     parent request (`toDownload`) is returned as computed, so credit can never affect block handling.
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
   - Since round 4, a solution (on the current or an earlier candidate) is refused only if the candidate's own
     transactions share an input box with the chain through its parent; otherwise it is accepted
     (`judgeInputSolution`, see Round 4, which replaces round 3's refusal of earlier candidates on the best input
     block).
   - Since round 4, no candidate is assembled on the node's own new input block before its body is processed
     (`waitForOwnInputBlock`, at most 1 s).
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
| solution repeating its prefix refused, otherwise accepted (round 4, replaces round 3) | `CandidateGeneratorUnclesSpec`: "solution on a candidate built before its parent's body was processed, repeating it, is refused", "solution on a candidate built in that window is accepted when its parent carried no transactions", "input solution on an earlier candidate is accepted if its parent is an earlier block" |
| no candidate on the own unprocessed block (round 4) | `CandidateGeneratorUnclesSpec`: "a candidate on the node's own input block waits until that block's body is processed" |
| sibling bodies on demand (round 4) | `InputBlockUnclesSpecification`: "sibling body not wanted; a later child of it is…", "uncles disabled: every body wanted…", "switch to the sibling's fork applies once its late bodies arrive" (both orders); `ErgoNodeViewSynchronizerSpecification`: "uncles enabled, a sibling's transactions are fetched only once a child needs them", "uncles disabled, … fetched on its announcement (base)" |
| own siblings relayed, credited by peers (round 4) | `ErgoNodeViewHolderUnclesSpec`: "own-mined input block that does not become best is announced to peers" (+ flag off); `InputBlockUnclesSpecification`: "a block whose body failed on its miner is still credited by a peer…" |
| K = 2, by arrival, not already credited | `CandidateGeneratorUnclesSpec`: "generator takes at most two siblings, by arrival…"; `InputBlockUnclesSpecification`: "uncle candidates…" |
| three ids / malformed length / self-reference / duplicate | `InputBlockUnclesSpecification`: "three ids…", "malformed length…", "self-reference…", "duplicate id…" (+ ergo-core "field rule") |
| uncle from another ordering block / parent not on chain / already credited / unknown | `InputBlockUnclesSpecification`: "uncle from another ordering block…", "uncle whose parent is not on the chain, or which is on the chain…", "uncle already credited to an ancestor…", "unknown id…; credited once the sibling's announcement arrives" |
| each failing case: block valid, reference uncredited | each of the above asserts `(Seq(id) -> Seq.empty)` (processed as best) and the credit; where possible a valid reference is credited alongside |
| stripped field: valid, no credit | `InputBlockUnclesSpecification`: "stripped field…" |
| flag off = base | processor "uncles disabled…", generator "uncles disabled…", view holder "uncles disabled: no sibling relay", REST spec |
| mixed | `InputBlockUnclesSpecification`: "version 1 announcements … accepted"; `CandidateGeneratorUnclesSpec`: "mixed: a flag-on node accepts a flag-off node's input blocks", "mixed: a flag-off node parses, ignores and relays unchanged…" |
| L with uncles == L without | `InputBlockUnclesSpecification`: "L of a block with uncles equals L of the same block without them" (+ the first property checks L excludes the sibling's transaction) |
| sibling announcement relay | `ErgoNodeViewHolderUnclesSpec`; `ErgoNodeViewSynchronizerSpecification`: "NewInputBlockSibling announces the sibling's id only" |
| REST field | `BlocksApiRouteSpec`: "report credited uncles…" (shape, flag on/off), "report a credited uncle in /blocks/bestInputChain and /blocks/bestInputBlock" (value, round 2) |

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
2. **Credit is local accounting** (confirmed by the maintainer against the code). Two nodes can disagree:
   - when one has not seen a sibling;
   - when one received a stripped copy first. The full copy is then ignored as "already known", as on the base,
     so that node **permanently** loses the credit for that block. Keeping the better copy's field would fix it, at the cost of
     replacing stored announcements.
   Using credit for rewards needs a deterministic source, for example the ordering block committing to the
   credited set, or uncles being validated at ordering-block time.
3. **"PoW-valid" means "known".** The synchronizer checks input-block PoW before storing an announcement only when
   it has a UTXO state reader (`usrOpt.map(...).getOrElse(true)`). The maintainer confirmed this is narrower
   than first stated: input blocks, and so the sibling relay too, go only to UTXO-mode peers
   (`inputBlockRecipients`), so the unchecked path concerns little beyond a digest-mode node that obtains an
   announcement anyway. The processor could re-check PoW with the parameters, which it does not have today.
4. **Fetching unknown uncles.** The starting branch downloaded unknown uncles' announcements. This branch does
   not: credit is re-evaluated when the announcement arrives through normal relay. Requesting a missing
   referenced announcement (announcement only) would make credit converge faster.
5. **Relay gap** (confirmed by the maintainer). The first child of a parent is relayed only if it becomes the best
   input block, through the base path. A first child whose transactions never arrive, so that it never becomes
   best, is not relayed as a sibling. Later competitors are relayed. "First" is per node: it depends on the
   order in which that node received the announcements. **Since round 4** an own-mined block that does not
   become best is announced too, which closes the gap for the miner's own blocks (all disagreements observed).
   A received first child that never becomes best is still not relayed by this node.
6. **Uncle depth** is bounded only by the ordering block: an uncle's parent may be any ancestor. Ethereum bounds
   it (6 generations). Whether a bound is wanted depends on the reward answer.
7. **The base receive path** fetches sibling transactions on announcement (see deviations). **Since round 4** a
   flag-on node does not (see Round 4); flag-off nodes still do.
10. **Credit does not check a sibling's transactions** (round 4): a sibling repeating its parent's transactions is
    credited. See Round 4, item 1.
8. **The cost of `refreshUncleCredits`** grows with the number of input blocks per ordering block. If needed,
   recompute only the blocks whose chain contains the new announcement's parent, or which reference it.
9. `InputBlocksProcessor.scala` was already over the 800-line style limit on the base (1269 lines). It is now
   about 1460 lines. Moving the credit code into its own trait would be a follow-up.

## Round 2

Two requests from the round 1 review, no design change:

1. **`BlocksApiRouteSpec` checks a value, not only the shape.** New property "report a credited uncle in
   /blocks/bestInputChain and /blocks/bestInputBlock".
   - It builds a flag-on history: chain A, B, sibling S (child of A), and C (child of B) referencing S, using
     `InputBlockUnclesTestHelpers`.
   - It serves that history to the route through a small readers actor answering `GetDataFromHistory`.
   - It asserts that `creditedUncles` in `bestInputChain` maps C to `[S]` and B to `[]`, and that
     `bestInputBlock` reports C with `creditedUncles` `[S]`.
2. **The credit refresh can no longer affect block handling.** In `InputBlocksProcessor.applyInputBlock`,
   `toDownload` (the parent request) is computed as before. `refreshUncleCredits` then runs in its own `Try`, and
   a failure is logged at error level and ignored. Before, an exception from the refresh reached the outer
   `catch`, which returned `None` and so dropped the parent request for a block that was already stored.
   - `Try` catches non-fatal exceptions only. A fatal error (for example an out-of-memory error) still reaches the
     outer `catch`, as it does for the rest of the method.

Spec to run for round 2, in addition to the round 1 list:

```
sbt "testOnly org.ergoplatform.http.routes.BlocksApiRouteSpec org.ergoplatform.nodeView.history.modifierprocessors.InputBlockUnclesSpecification org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorSpecification"
```

No spec forces `refreshUncleCredits` to throw: it is private and has no failure injection point, and adding one
only for a test would change production code beyond the request.

## Round 3

One change in `CandidateGenerator`'s handling of an input-block solution, no design change.

- **Change.** A solution found on an earlier candidate is accepted only if that candidate's parent is not the
  current best input block (`state.hr.bestBlocks._2`). Otherwise it is refused as on the base: the reply is the
  base's error, "Invalid input block! PoW valid: false", and the current candidate stays.
- **Reason** (measured by the maintainer on GitHub runs):
  - Such a candidate was built before the miner's previous block applied its transactions, so it repeats them.
  - The miner's own node rejects it as "Double spending" (28 to 65 per run under load).
  - The unchanged base keeps it as the tip and the miner stalls on it. Since it is never best, it is never
    relayed.
  - Every failure seen extended the tip (307 of 307), so the change loses no creditable sibling. It refuses
    about 0.3 to 1.4 % of replies.
- **Code.** The decision is the pure function `CandidateGenerator.judgeInputSolution(current, earlier, solution,
  powValid, bestInputBlockId)`, which the actor calls. It returns one of four verdicts:
  - `MinedOnCurrentCandidate`
  - `MinedOnEarlierCandidate`
  - `EarlierCandidateOnBestParent` (refused; replaced in round 4 by `RepeatsPrefixTransactions`)
  - `NoCandidateForInputSolution`

  With the flag off, `earlier` is empty, so only the first and last verdicts occur, exactly as on the base.
- **Choice.** With no best input block, a candidate without a parent counts as "on the current best": both
  `None`. That candidate builds on the same thing a fresh candidate would, so it is refused too.
- **Specs** (`CandidateGeneratorUnclesSpec`):
  - "input solution on an earlier candidate is refused if its parent is the current best input block". Also
    checks the current-candidate and no-candidate verdicts.
  - "input solution on an earlier candidate is accepted if its parent is an earlier block". Also checks that the
    accepted block is a sibling announcement.

*Superseded by round 4:* the blanket refusal is replaced by a transaction check, and the first spec above was
rewritten (see Round 4).

Spec to run for round 3:

```
sbt "testOnly org.ergoplatform.mining.CandidateGeneratorUnclesSpec org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.ErgoMinerSpec org.ergoplatform.mining.ErgoMiningThreadSpec"
```

## Round 4

Three causes behind the remaining costs, found by offline diagnosis of the round 3 network runs (18 runs, below
the cap, external miner). All changes are flag-gated: with the flag off the code paths are the base's.

### 1. Candidate built before the miner's own block is processed

**Observed:**
- The generator assembled a candidate a few ms after the node stored its own input block but before that
  block's body was processed, while the mempool still held the block's transactions. The candidate's parent is
  the new block, and it repeats the parent's transactions.
- A solution on it fails "Double spending" on its own miner. In one run the failed block stayed the tip, a peer
  fetched it as a parent and failed it too, and both nodes stalled 46 s.
- 11.2 % of own blocks were assembled in this window with the flag on (2.6 % on the base).
- Round 3's refusals were almost all candidates from this window: about 79 % would have failed, about 21 %
  (parent without transactions) were valid and were refused.
- 0.7 to 7.8 % of credited siblings repeat their own parent's transactions.

**Choices:**
- **Transaction check instead of round 3's refusal.**
  - `judgeInputSolution(current, earlier, solution, powValid, prefixTransactions)` stays the pure decision
    point. The candidate the solution fits (current or earlier) is refused (`RepeatsPrefixTransactions`) if its
    own input-block transactions share an input box with `prefixTransactions(parent)`. Otherwise it is accepted
    (`MinedOnCurrentCandidate` / `MinedOnEarlierCandidate`).
  - The actor passes `chainTransactionsThrough(parent)`, the stored bodies of the chain from the first input
    block of the ordering block through the candidate's parent, read when the solution arrives. By then the
    parent's body is normally processed.
  - *Why the input-box check and not the digest comparison:* comparing the candidate's previous-transactions
    digest with the current prefix digest would refuse every candidate whose prefix changed, including those
    that repeat nothing (the 21 % above). The input-box check refuses exactly what the node would reject as
    "Double spending" against the prefix.
  - It is the test of `CandidateGenerator.doublespend`, done with one set of the prefix's spent box ids instead
    of a scan per transaction.
  - It does not catch other kinds of invalidity, which were not observed.
  - With the flag off, the prefix function returns nothing and earlier candidates are not considered, so the
    handling is the base's.
- **No candidate on the own block before its body is processed.**
  - The generator remembers its own last accepted input block and when it was mined (`ownPendingInputBlock`).
  - While a new candidate would build on that block (`bestBlocks._2`) and the block is not in the processed best
    chain, a candidate request is not assembled. It is rescheduled every 20 ms, as the base already does when no
    candidate can be made, for at most `OwnInputBlockWait` = 1 s (`waitForOwnInputBlock`).
  - After that the candidate is assembled anyway, so a block whose body fails cannot stall the miner forever
    (that case is now also prevented by the check above).
  - Requests are not answered during the wait: the internal miner keeps mining its previous candidate, and an
    external miner's request waits for up to 1 s.
  - *What the base would need:* the same window exists there (2.6 %).
    - Either the same wait without the flag,
    - or the node view holder must make the new tip visible to readers only after its body is processed. For a
      local block, process the body before inserting the announcement into the tree (or insert and process
      atomically with respect to readers), and remove the block's transactions from the mempool before readers
      see the new tip.
    - The base also builds ordering-block candidates in this window with an L that lacks the parent's
      transactions.
- **Credit and a sibling repeating its parent's transactions: left as an open question.** Credit is header-level
  by design.
  - Checking a sibling's transactions needs the sibling's body, which item 2 deliberately stops fetching, and
    its parent's body.
  - A check made only when the bodies happen to be present would make credit depend on what each node fetched,
    adding disagreement between nodes (item 3's problem).
  - The announcement's weak transaction ids (only for blocks of at most 3 transactions) are not enough to decide
    either.
  - The source observed was miners' own candidates from the window above. With the check above, an honest flag-on
    miner no longer produces such blocks, but a peer's could still be credited.
  - The spec "credit does not check a sibling's transactions: one repeating its parent's is credited" documents
    the behaviour.

**Specs** (`CandidateGeneratorUnclesSpec`):
- "solution on a candidate built before its parent's body was processed, repeating it, is refused", as the
  current and as an earlier candidate.
- "solution on a candidate built in that window is accepted when its parent carried no transactions", as the
  current and as an earlier candidate on the current best input block (refused in round 3), plus the
  no-candidate verdict.
- "input solution on an earlier candidate is accepted if its parent is an earlier block" (kept).
- "a candidate on the node's own input block waits until that block's body is processed" (the pure wait rule).
- The window is reproduced in the fixture: P's announcement is stored, the candidate is built on P before P's
  body is processed, and the candidate's input-block transactions are set to P's to stand in for the mempool
  that still held them. P's body is then processed before the solution is judged.

### 2. Sibling transactions fetched though only the announcement is needed

**Observed:** all of the extra input-block bytes (1.45 to 1.73× the base) came from credited siblings, about half
their relayed announcements and half their transaction fetches. The fetch path is
`processInputBlock` → `resolveInputBlockTransactions` (or `requestInputBlockTransactionIds`), with no best-chain
check.

**Choices:**
- `InputBlocksProcessor.inputBlockBodyWanted(ib)`, called by the synchronizer:
  - With the flag off, or for another ordering block than the best one: always (base).
  - Otherwise the body is wanted when any of the following holds:
    - the block's depth (its known chain plus one) is greater than the length of the processed best chain, so it
      extends the best chain or makes a sibling branch long enough for the fork choice to switch;
    - its chain is not known yet;
    - a known block already builds on it.
  - A sibling not meeting this is handed to the node view holder as an announcement only. No transaction ids or
    transactions are requested and the mempool is not consulted.
- **Fetched later when needed.** When a wanted announcement arrives, the bodies of its stored ancestors without a
  body (`bodilessAncestors`, nearest first, up to the first with a body) are requested from the same peer, and
  its own body is requested as before. A child of a sibling therefore fetches the sibling's body.
- **Fork switch.**
  - A switch to the sibling's fork needs a longer branch, so it always involves such a child, and the fetch
    above.
  - The base fork choice judges by the depth of the block whose body has just arrived. If the child's body
    arrives before the sibling's, the base would never switch.
  - With the flag on, `applyInputBlockTransactions` therefore processes the deepest descendant with a body again
    when a body arrives without progress, and the switch happens in either order.
- **Costs:**
  - A reorg onto a sibling branch waits for the bodies: one more round trip for the sibling's, requested from the
    peer that sent the child. If that peer lacks it (a flag-on peer that has not fetched it either), the request
    goes unanswered and is not retried, so the switch waits for the next block on that branch. *Corrected in
    round 5:* that was wrong. The next block walked back only to the nearest ancestor with a body, so it did not
    ask again, and the node stayed on the shorter chain until the next ordering block. See Round 5, A.
  - The node cannot serve a sibling's transactions or transaction ids it never fetched. A flag-off peer asking it
    for them gets no answer (logged, no penalty). *Changed in round 5:* the body is now fetched on demand and the
    peer is answered then. See Round 5, B.
  - Ordering-block reconstruction is the base's (from the best chain). An ordering block linking a sibling branch
    whose bodies were not fetched cannot be rebuilt and falls back to the full download.
  - The node's mempool and wallet do not see a sibling's transactions until they are fetched.
- The relay of sibling announcements (round 1) is unchanged: announcements still travel. Only the bodies are no
  longer fetched.

**Specs:**
- `InputBlockUnclesSpecification`:
  - "sibling body not wanted; a later child of it is, and its body-less ancestors are fetched";
  - "uncles disabled: every body wanted, no ancestor fetch (base behaviour)";
  - "switch to the sibling's fork applies once its late bodies arrive", the child's body first and the sibling's
    first.
- `ErgoNodeViewSynchronizerSpecification`, through `processInputBlock` with PoW-valid announcements and a flag-on
  synchronizer (`Synchronizer2Fixture` now takes the flag):
  - "uncles enabled, a sibling's transactions are fetched only once a child needs them": no request on the
    sibling's announcement, then requests for the sibling and the child on the child's;
  - "uncles disabled, a sibling's transactions are fetched on its announcement (base)".

### 3. A miner credits its own siblings that its peers never received

**Observed:** every credit disagreement between nodes (81 pairs in one run set) was a node crediting an uncle it
mined itself, whose announcement was never sent on any link (open question 5's gap).

**Choice: relay them.**
- With the flag on, the node view holder announces an own-mined input block that did not become the best one
  after its body was handled (`NewInputBlockSibling`, local). This covers a block that lost its parent position
  or whose body failed. The announcement goes out by id, as for other siblings: peers request the announcement,
  and with item 2 they do not fetch its body.
- A block announced as a sibling on arrival, or that became best, was announced already and is not announced
  again. *Changed in round 5:* an own block is announced only if its transactions apply on its own prefix, and
  only after its body was handled. See Round 5, C.
- *Why not "reference only relayed or received siblings":* that would drop the credit for the miner's own work
  that this branch exists to measure, and it would still leave the peers without the announcement. Relaying
  costs one Inv per such block, plus one announcement for each peer that requests it.

**Specs:**
- `ErgoNodeViewHolderUnclesSpec`: "own-mined input block that does not become best is announced to peers". Here
  its body fails. The flag-off control asserts that it is not announced.
- `InputBlockUnclesSpecification`: "a block whose body failed on its miner is still credited by a peer that
  received its announcement".

### Not compiled

Nothing of round 4 was compiled or run here. sbt is unavailable and Maven Central is blocked (HTTP 403). Places
worth a first look if the build fails:
- `CandidateGenerator`:
  - the parenthesised function literals in the `if` that chooses `prefixTransactions`;
  - the `if … else if (!forced && cachedFor(…)) … else` chain in `GenerateCandidate`;
  - the new field `ownPendingInputBlock` with a default value at the end of `CandidateGeneratorState`.
- `InputBlocksProcessor`: the nested `@tailrec` local functions in `knownAncestry` and `bodilessAncestors`.
- `ErgoNodeViewSynchronizer`: `if (!bodyWanted) {…} else weakTxIdsOpt match {…}`.
- `ErgoNodeViewSynchronizerSpecification`:
  - `Synchronizer2Fixture(inputBlockUncles: Boolean = false)`, constructed as `new Synchronizer2Fixture(uncles)`;
  - the local `object helpers` mixing in `InputBlockUnclesTestHelpers`.

### Spec classes to run

```
sbt "testOnly org.ergoplatform.mining.CandidateGeneratorUnclesSpec org.ergoplatform.nodeView.history.modifierprocessors.InputBlockUnclesSpecification org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderUnclesSpec org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification"
sbt "testOnly org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorSpecification org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorConcurrencySpecification org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.CandidateRetryReorgSpec org.ergoplatform.mining.ErgoMiningThreadSpec org.ergoplatform.mining.ErgoMinerSpec org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderSpec org.ergoplatform.network.InputBlockParentBindingSpec org.ergoplatform.network.OrderingBlockMessageFlowSpec org.ergoplatform.settings.ErgoSettingsSpecification org.ergoplatform.http.routes.BlocksApiRouteSpec"
sbt "ergoCore/testOnly org.ergoplatform.mining.InputBlockUnclesSpec"
```

## Round 5

Round 4 was verified on GitHub: it compiled with no fixes, the new specs were green over 2 repeats, there was no
regression against the stack baseline, and each of the three changes was killed by a mutant. A code read then
found three liveness gaps. All fixes are flag-gated: with the flag off the code paths are the base's.

### A. A lost sibling body was never requested again

**Gap** (confirmed by the maintainer with a local test):
- If the single request for sibling S's body went unanswered (the peer lacked it or left), each later block on
  that branch walked back only as far as S's child, which had its body. S was never asked for again, and a second
  announcement of S is ignored as already known.
- The node stayed on the shorter chain until the next ordering block, whose reconstruction then fell back to the
  full download.
- The round 4 note saying the switch "waits for the next block on that branch" was wrong; it is corrected above.

**Change:**
- `bodilessAncestors` walks back through **all** known ancestors of the same ordering block, up to the first input
  block, past those that have their body. It returns every one without a body, nearest first.
- When a wanted block arrives, each of them is requested from the peer that sent that block, which built on them
  and is usually a different peer than the one that failed. This happens through
  `ErgoNodeViewSynchronizer.requestInputBlockBodyBounded`, at most `MaxInputBlockBodyRequests` = 3 times per input
  block, counting every request.
- A lost request is therefore retried by each later descendant, up to the bound. The node does not remember which
  peer failed: if the same peer sends the next descendant, it is asked again.
- Once the sibling's body arrives, round 4's late-body retry processes the deepest descendant with a body, and the
  fork choice switches.
- The counters are kept in the synchronizer, bounded to the latest `MaxTrackedInputBlocks` = 1024 input blocks.
- *Remaining:* after 3 lost requests the node waits for the next ordering block, as before.

**Specs:**
- `ErgoNodeViewSynchronizerSpecification`:
  - "uncles, a lost sibling body is requested again from the peer of a later descendant": S's request to
    `peer` is not answered, T (child of S) gets its body, U (child of T) arrives from another peer, S is requested
    from that peer, and the switch to A, S, T happens when S's body arrives;
  - "uncles, re-requests of a missing sibling body are bounded".
- `InputBlockUnclesSpecification`: "body-less ancestors are found behind ancestors that have their body".

### B. Mixed networks: flag-off peers could not get a body-less sibling

**Gap:** the base asks for an input block's transactions once, with no timeout, retry or penalty. A flag-on node
relays (and serves on request) the announcement of a sibling whose body it never fetched. A flag-off peer asking
it for the transactions got nothing. If a block later built on that sibling, the flag-off node could not follow
until the next ordering block.

**Choice: the alternative, fetch on demand and answer late.**
- When a peer asks for the transaction ids of an input block whose announcement is stored but whose body is not,
  the synchronizer remembers the peer and requests the body from the peer that announced the sibling. That peer
  is recorded when the sibling was processed as an announcement only. The request is bounded as in A.
- When the body is stored, the node view holder publishes `InputBlockBodyStored(id)`, and every waiting peer is
  answered. Because the base requester waits without a timeout, the late answer is used.
- *Why not the recommended option*, which relays a sibling announcement to a peer only if the node holds its body
  or the peer is known to be flag-on:
  - "Known flag-on" can only be guessed. A flag-on node sends version 2 announcements only when it references
    uncles, so most flag-on peers would look flag-off.
  - It would cover the relay only. Announcements are also served on request, for example as the unknown parent of
    a later block, and that path would still hand out body-less siblings.
  - The on-demand fetch covers every path, needs no capability guess, and fetches a body only when someone
    actually asks for it.
- *Costs:*
  - The asking peer gets the body one round trip later.
  - If the announcer cannot provide it, the asker waits until the next ordering block, as before. This happens
    after 3 requests, or when the announcer is unknown: for example the sibling's announcement came through the
    view holder from a peer this synchronizer did not record, or the record fell out of the 1024-entry bound.
  - The waiting peers and the announcers are kept bounded like the counters.
  - *Gap (fixed in round 6):* only the transaction-ids request was covered, not the request for specific
    transactions. See Round 6.

**Specs** (`ErgoNodeViewSynchronizerSpecification`):
- "uncles, a peer asking for a body-less sibling's transactions gets them after an on-demand fetch": nothing is
  sent to the asker at first, and the body is requested from the announcer; after the body is stored and
  `InputBlockBodyStored` is received, the asker gets `InputBlockTransactionIdsData` for S.
- "uncles disabled, a request for unknown transaction ids is not answered nor fetched (base)".
- `ErgoNodeViewHolderUnclesSpec`: "a stored input block body is signalled (on-demand body requests wait for it)",
  and no signal with the flag off.

### C. The own-block relay spread blocks whose body failed

**Gap:** round 4 announced every own block that did not become best, including one whose body failed. A
same-depth peer then fetched it and failed it too, which is the shape of the 46 s two-node stall seen in round 3.

**Change:**
- With the flag on, after an own block's body was handled, the block is announced as a sibling only if it is not
  in the best chain and its transactions apply on its own prefix. The check is `ErgoState.applyInputBlock(txs,
  chainTransactionsThrough(parent), header)` on the node's state, the same validation the processor runs. A
  digest-mode node fails this check, so it does not announce.
- An own block whose transactions fail is never announced.
- The round 1 announcement of an own block that was a sibling on arrival is removed for own blocks, so that every
  own sibling goes through this check, after its body. Received siblings are still announced on arrival, as in
  round 1.

**Specs** (`ErgoNodeViewHolderUnclesSpec`):
- "own-mined input block that applied but lost its position is announced to peers": Y, a peer's child of A, was
  processed first.
- "own-mined input block whose transactions fail is not announced".
- "uncles disabled: own-mined input block that does not become best is not announced (base behaviour)".

### Not compiled

Nothing of round 5 was compiled or run here (no sbt; Maven Central blocked). Places worth a first look if the
build fails:
- `ErgoNodeViewSynchronizer`:
  - the generic `putBounded[V]` on `mutable.LinkedHashMap`;
  - the guarded `case None if …` in `processInputBlockTransactionIdsRequest`.
- `ErgoNodeViewHolder`: `Try(minimalState().applyInputBlock(…)).flatten`.
- `ErgoNodeViewSynchronizerSpecification`: `withBodilessSibling`, with its seven-parameter function and the
  `SendToNetwork(msg, SendToPeer(p))` patterns.

**Fixture risk:** the round 5 synchronizer specs send announcements made by `InputBlockUnclesTestHelpers.announceOn`
through `processInputBlock`. These are header copies whose extension root was changed. The test PoW scheme
accepts any ordering-block header, but the input-block PoW check (`checkInputBlockPoW`) is real and runs against
the test difficulty. I expect it to pass for any header at that difficulty, but this was not run. If those
announcements are rejected as invalid (penalty messages), that is the cause.

### Spec classes to run

```
sbt "testOnly org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderUnclesSpec org.ergoplatform.nodeView.history.modifierprocessors.InputBlockUnclesSpecification org.ergoplatform.mining.CandidateGeneratorUnclesSpec"
sbt "testOnly org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorSpecification org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorConcurrencySpecification org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.CandidateRetryReorgSpec org.ergoplatform.mining.ErgoMiningThreadSpec org.ergoplatform.mining.ErgoMinerSpec org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderSpec org.ergoplatform.network.InputBlockParentBindingSpec org.ergoplatform.network.OrderingBlockMessageFlowSpec org.ergoplatform.settings.ErgoSettingsSpecification org.ergoplatform.http.routes.BlocksApiRouteSpec"
sbt "ergoCore/testOnly org.ergoplatform.mining.InputBlockUnclesSpec"
```

## Round 6

Round 5 was verified on GitHub: zero compile fixes, all specs green over 2 repeats, no regression against the stack
baseline, and each fix killed by its mutant.

### R5-B1: the request for specific transactions was not covered

**Gap:** the base requests an input block's body in two ways:
- *Transaction ids*, when the announcement carries no weak ids. Round 5's on-demand fetch covered this request.
- *Specific transactions*, when the announcement carries weak ids. This is the common case: the generator writes
  weak ids for all transactions, only the local push of a best block strips them above 3, and stored
  announcements are served as stored. The peer resolves what it can from its mempool and sends an
  `InputBlockTransactionsRequest` for the rest.

The handler for the second request, `processInputBlockTransactionsRequest`, was unchanged. A flag-on node without
the body neither answered nor fetched. A sibling whose transactions a flag-off peer lacked in its mempool, for
example because they were already in its chain, was therefore unreachable for that peer.

**Change** (flag on only):
- `processInputBlockTransactionsRequest`: when the input block's announcement is stored but its body is not, the
  request is kept, the body is fetched on demand, and the requested subset is sent once the body is stored. When
  the body is stored, the request is answered as before. With the flag off, or for an unknown block, nothing
  changes.
- Both request kinds go through one path, `awaitBodyOnDemand`, and share one attempt budget
  (`MaxInputBlockBodyRequests` = 3 per block). That budget is also shared with the fetches triggered by a later
  descendant (round 5, A).
- Each kept request remembers its kind: `PendingTransactionIds(peer)` or `PendingTransactions(peer, request)`. On
  `InputBlockBodyStored` each kept request is answered by the handler of its kind.
- *Optional item, done:* every peer that announces such a sibling is remembered, up to `MaxSiblingAnnouncers` = 4.
  That includes a peer announcing it again after it is known: its announcement is otherwise ignored, but it is now
  recorded as a source. On-demand attempts go to these peers in turn, attempt n to peer n mod k, so a departed
  first announcer cannot use up all 3 attempts.
- Peers that only sent an `Inv` for the known block are not recorded. The node did not request the announcement
  from them, so it cannot tell whether they hold the body.

**Remaining:**
- Once 3 attempts are spent, or when no announcer is known, waiting requests stay unanswered until the next
  ordering block, as before.
- A kept request for specific transactions is not deduplicated by content (its weak ids are byte arrays), so a peer
  repeating the same request may be answered twice. That is harmless.

**Specs** (`ErgoNodeViewSynchronizerSpecification`):
- "uncles, a peer requesting specific transactions of a body-less sibling gets them after an on-demand fetch":
  - nothing is sent to the asker at first, and the body is requested from the announcer;
  - after S's body (one transaction) is stored and `InputBlockBodyStored` is received, the asker gets
    `InputBlockTransactionsData` with exactly that transaction.
- "uncles, on-demand fetches go to the other peers that announced the sibling in turn": S is announced again by a
  second peer. A transaction-ids request fetches from the first announcer, and the next request, for specific
  transactions, fetches from the second.
- "uncles disabled, a request for transactions of an unknown body is not answered nor fetched (base)".
- `withBodilessSibling` takes S's transactions (default none, as in round 5).

### Not compiled

Nothing of round 6 was compiled or run here (no sbt; Maven Central blocked). Places worth a first look if the
build fails:
- in the `ErgoNodeViewSynchronizer` companion: the sealed `PendingBodyRequest` with its two case classes;
- `Vector` values in the `putBounded` maps;
- the pattern match on the companion's case classes in the `InputBlockBodyStored` handler;
- in the spec: the class-level `private object round6` that mixes in the helpers.

Round 5's fixture risk applies here too: the announcements that `announceOn` modifies must pass the real
input-block PoW check at the test difficulty.

### Spec classes to run

```
sbt "testOnly org.ergoplatform.network.ErgoNodeViewSynchronizerSpecification org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderUnclesSpec org.ergoplatform.nodeView.history.modifierprocessors.InputBlockUnclesSpecification org.ergoplatform.mining.CandidateGeneratorUnclesSpec"
sbt "testOnly org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorSpecification org.ergoplatform.nodeView.history.modifierprocessors.InputBlockProcessorConcurrencySpecification org.ergoplatform.mining.CandidateGeneratorSpec org.ergoplatform.mining.CandidateRetryReorgSpec org.ergoplatform.mining.ErgoMiningThreadSpec org.ergoplatform.mining.ErgoMinerSpec org.ergoplatform.nodeView.viewholder.ErgoNodeViewHolderSpec org.ergoplatform.network.InputBlockParentBindingSpec org.ergoplatform.network.OrderingBlockMessageFlowSpec org.ergoplatform.settings.ErgoSettingsSpecification org.ergoplatform.http.routes.BlocksApiRouteSpec"
sbt "ergoCore/testOnly org.ergoplatform.mining.InputBlockUnclesSpec"
```
