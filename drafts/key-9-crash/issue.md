# Input-block PoW throws `key not found: 9` after a v3 voting boundary on a chain without parameter 9

> Draft — not posted.

## What happens

`Parameters.subBlocksPerBlock` was a bare map lookup:

```scala
lazy val subBlocksPerBlock: Int = parametersTable(SubblocksPerBlockIncrease)   // key 9
```

Since `9e74160d5` ("readjustable number of sub-blocks per block", 2026-03-11), three paths read it at every block version. None of them is gated by version:

| Caller | Where | Effect when key 9 is absent |
|---|---|---|
| `AutolykosPowScheme.checkNonces` | `ergo-core/.../mining/AutolykosPowScheme.scala:404` | The internal miner (`prove`, `proveCandidate`) throws before testing a single nonce. |
| `AutolykosPowScheme.checkInputBlockPoW` | `AutolykosPowScheme.scala:134`, called from `CandidateGenerator.scala:273` | Submitting an input-block solution fails. |
| `InputBlockAnnouncement.valid` → `checkInputBlockPoW` | `InputBlockAnnouncement.scala:35`, called from `ErgoNodeViewSynchronizer.scala:1558` | Validating a peer's input-block announcement throws inside the synchronizer actor. |

## When key 9 is absent

At an epoch start, `ErgoStateContext.process` adopts the parameters **parsed from the block's extension** (`ErgoStateContext.scala:248`). It does not keep the locally calculated ones. So a node holds key 9 only if the miner of the latest epoch-start block wrote it.

- **Nodes that don't know key 9** (mainline 5.x, and 6.x before activation) never write it at v3. The 6.0 consensus line deliberately removed key 9 from `DefaultParameters` in `1ded532d4`, because the pre-6.0 `exMatchParameters` rule compares table sizes.
- **First boundary after genesis.** `currentParameters.height == 0`, so the parsed table is taken as-is. The launch table's 9 → 64 is replaced by whatever the extension holds.
- **Later v3 boundaries.** `Parameters.update` only carries keys forward. At v3 it never inserts key 9 (`Parameters.scala:93` applies only at block version 4).
- **On reaching v4,** `update` inserts 9 → 30, and `processExtension` falls back to 30 (`ErgoStateContext.scala:234`). From then on the key is present.

So: any node running this code crosses a voting boundary at block version 3 on a chain whose epoch-start extensions came from nodes without key 9. From then until v4 activation, its current parameters have no key 9, and the three reads above throw `NoSuchElementException: key not found: 9`.

On a chain built only by Matrix nodes, key 9 is never lost. Their launch table carries 9 → 64, and `CandidateGenerator` writes `update(currentParameters)` into every epoch-start extension (`CandidateGenerator.scala:652–662`), so the key is carried forward.

## Reproduction

`ergo-core/src/test/scala/org/ergoplatform/mining/SubBlocksPerBlockMissingKeySpec.scala` builds a state context from the launch table at block version 3. It then processes two epoch-start blocks whose extensions omit key 9: the first boundary after genesis, and one computed through `update`. Both pass `exMatchParameters`. Results:

- The characterization property (key 9 is gone from the current parameters) holds before and after the fix.
- `subBlocksPerBlock`, `checkInputBlockPoW` and `checkNonces` on those parameters throw `NoSuchElementException` before the fix.

## 64 vs 30

There are two defaults for parameter 9:

| Constant | Value | Where it enters |
|---|---|---|
| `SubsPerBlockDefault` | 64 | `DefaultParameters`, so every launch table (mainnet, testnet, devnet, devnet60) at height 0 (`Parameters.scala:322, 333`) |
| `SubblocksPerBlockDefault` | 30 | Inserted on 6.0 activation by `Parameters.update` (`:93`), and the fallback in `ErgoStateContext.processExtension` (`:234`) |

History:

- **2024-07-30, `bed12e60b`.** The input-block line puts `SubsPerBlockIncrease -> SubsPerBlockDefault` (64) into `DefaultParameters`.
- **2024-10-17, `19330610f`.** The 6.0 line adds `SubblocksPerBlockDefault = 30` and puts it in `DefaultParameters`.
- **2025-02-01, `1ded532d4`.** The 6.0 line comments key 9 out of `DefaultParameters` ("todo: consider when to inject, matchParameters rule should be tweaked").
- **2025-02-03 / 02-11, `93ab11a84`, `0f7389cb6`.** The 6.0 line injects 30 on activation instead.
- **2025-05-13, `d2929b638`** ("merging w. 6.0"). The merge resolution keeps the input-block side's 64 entry, renamed to `SubblocksPerBlockIncrease`. The 6.0 side had none.
- **2026-09-25, `b6fcc2152`.** The docs already note the split and present 64 only as an example.

**Settled:**

- **30 is the network value** on every chain that activated 6.0 (mainnet, public testnet). Those chains' tables never carried key 9 before activation, and activation inserts 30.
- **64 survives only on chains launched by Matrix nodes.** Activation inserts 30 only when the key is absent, so a chain whose genesis table already has 9 → 64 never sees 30.
- **The 64 entry is a merge artifact.** It contradicts `1ded532d4`'s deliberate removal.

The fix uses 30 for the missing-key fallback, which agrees with `update` and `processExtension`.

Whether genesis tables should also change (drop key 9 from `DefaultParameters`, or set it to 30) is a separate decision, not made here:

- It changes the value on Matrix-launched devnets.
- It changes what a Matrix node writes in its first epoch-start extension.

## Relevance: does a real network run Matrix at v3 across a voting boundary?

**Matrix code in releases.** Only the `v6.5.0-RC1`, `-RC2` and `-RC3` tags (2026-05-19 to 2026-07-06) contain the strict read. Released 6.0.x/6.1.x (latest `v6.1.5`) and `master` don't.

**Mainnet.** It is at v4: 6.0 activated around 2025-10-06, per the Ergo developer chat for week 41 of 2025.

- **At the tip:** current parameters hold 9 → 30, so there's no crash.
- **While syncing from genesis:** a 6.5 RC node holds pre-activation parameters (v1–v3, no key 9) until its state reaches activation. Header sync runs ahead, so an input-block announcement whose parent header is known gets validated against those parameters, and the synchronizer throws. This only bites if mainnet peers announce input blocks.
- **Not verified:** whether any 6.5 RC node on mainnet produces input blocks. Input-block production has no version gate in this code.

**Public testnet.** 6.0 activated in early 2025. `TestnetLaunchParameters` sets block version 4 at genesis.

- If the current testnet chain launched at v4, it never had a v3 boundary.
- If it predates that, the same sync-from-genesis exposure applies.
- **Not verified.**

**Matrix sub-blocks devnet.** Launched in January 2026 and relaunched 2026-02-01. It is built only by Matrix nodes, so key 9 is carried from genesis whether it runs as `devnet` (v3) or `devnet60` (v4). It doesn't hit this.

**Conclusion.** No known real network has a Matrix node at its tip running v3 across a voting boundary. The reachable exposure on real networks is a 6.5 RC node syncing mainnet (and possibly testnet) from genesis while receiving input-block announcements. Beyond that, it affects any v3 chain built by non-Matrix nodes, such as private or mixed devnets.

Live chain state couldn't be checked from the investigating environment. The explorer API was blocked, so the activation dates come from developer-chat summaries.
