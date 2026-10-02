package org.ergoplatform.nodeView.history

import org.ergoplatform.modifiers.history.HeaderChain
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.utils.ErgoCorePropertyTest

// THROWAWAY measurement for #2626 (not for commit): cost of the per-id lookups raiseHeightFromHeaderInv adds to a
// header Inv, at the 400-id Inv maximum, every id on the best chain (the worst case: both lookups run for each).
class RaiseLookupCostMeasureSpecification extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.HistoryTestHelpers._
  import org.ergoplatform.utils.generators.ChainGenerator._

  property("raise lookup cost for a 400-id header Inv") {
    val history = generateHistory(verifyTransactions = true, StateType.Digest, PoPoWBootstrap = false, blocksToKeep = -1)
    val headers = genHeaderChain(450, history, diffBitsOpt = None, useRealTs = false).headers
    applyHeaderChain(history, HeaderChain(headers))
    val ids = headers.takeRight(400).map(_.id)
    def lookup(): Seq[Int] = ids.flatMap(id => history.heightOf(id).filter(_ => history.isInBestChain(id)))
    (1 to 20).foreach(_ => lookup()) // warm-up
    val ns = (1 to 50).map { _ =>
      val t0 = System.nanoTime(); val r = lookup(); val t = System.nanoTime() - t0
      r.size shouldBe 400
      t
    }.sorted
    println(f"RAISE-LOOKUP 400 ids: median ${ns(25) / 1e6}%.3f ms, p95 ${ns(47) / 1e6}%.3f ms, max ${ns.last / 1e6}%.3f ms")
  }
}
