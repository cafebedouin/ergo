# Fall back to the 6.0 activation value when parameter 9 is absent

> Draft — not posted.

## Summary

`Parameters.subBlocksPerBlock` read `parametersTable(9)` directly. Some parameter tables have no key 9: those a node adopts after crossing a block-version-3 voting boundary on a chain whose epoch-start extensions were written by nodes that don't know parameter 9. Holding such a table, three reads throw `NoSuchElementException: key not found: 9`:

- the internal miner (`checkNonces`);
- input-block solution submission (`checkInputBlockPoW` in `CandidateGenerator`);
- peer input-block announcement validation (`InputBlockAnnouncement.valid` in `ErgoNodeViewSynchronizer`).

None of these is gated by block version. See `drafts/key-9-crash/issue.md` for the full mechanism.

## Changes

- **Spec, `a4fc7178e`.** Adds `ergo-core/src/test/scala/org/ergoplatform/mining/SubBlocksPerBlockMissingKeySpec.scala`. It drives `ErgoStateContext.process` across two v3 voting boundaries with extensions that lack key 9: the first boundary after genesis, and one computed through `Parameters.update`. Then it checks three things:
  - the current parameters have no key 9 (characterization; passes before and after);
  - `subBlocksPerBlock` returns `SubblocksPerBlockDefault` (30);
  - `checkInputBlockPoW` and `checkNonces` don't throw on those parameters, and `checkInputBlockPoW` agrees with the same table holding 9 → 30.
- **Fix, `76d83df56`.** `subBlocksPerBlock = subBlocksPerBlockOpt.getOrElse(SubblocksPerBlockDefault)`.
  - Tables that carry key 9 are read exactly as before.
  - Consensus code doesn't use this accessor: `ErgoStateContext` and `Parameters.update` read the table directly. Only input-block PoW thresholds change, and only where the key is missing, which used to throw.

## Why 30 and not 64

- 30 is the value 6.0 activation inserts (`Parameters.update`) and the value `ErgoStateContext.processExtension` falls back to. So every chain that activated 6.0 holds 30, and with this fallback every reader agrees on chains that never carried key 9.
- 64 appears only in genesis tables. It came in through the merge resolution in `d2929b638`, after the 6.0 line had deliberately removed key 9 from `DefaultParameters` (`1ded532d4`).
- This PR leaves genesis tables alone. Aligning them is a separate decision, because it changes the value on Matrix-launched devnets.

## Testing

- **Not executed.** sbt could not resolve dependencies in the authoring environment: Maven Central was blocked by egress policy. The spec was checked by reading the code instead:
  - Both boundaries pass `exBlockVersion`, `exMatchParameters` and `exMatchValidationSettings`.
  - `checkNonces` evaluates `subBlocksPerBlock` before its nonce loop, so properties 2–4 throw before the fix and hold after it.
- **To run:** `sbt "ergoCore/testOnly org.ergoplatform.mining.SubBlocksPerBlockMissingKeySpec"`. The red run uses the spec commit alone; the green run includes the fix. Also run `AutolykosPowSchemeParametersSpec` and `VotingSpecification`.

## Out of scope

- Aligning `DefaultParameters`' 9 → 64 with 30, or dropping key 9 from launch tables.
- Gating input-block production and validation by block version.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01LJEkbKi7ngNm4ytK3wjTZH
