/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.electrum

import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import fr.acinq.bitcoin.{BlockHeader, ByteVector32, Satoshi, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.computeScriptHash
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_PARENT_TX_CONFIRMED}
import fr.acinq.eclair.transactions.Scripts
import scodec.bits.ByteVector

import scala.collection.immutable.{Queue, SortedMap}
import scala.util.Try


class ElectrumWatcher(blockCount: AtomicLong, client: ActorRef, chainSync: ActorRef, headerDb: HeaderDb) extends Actor with Stash with ActorLogging {

  // Merkle proofs are checked against our headers: recent ones come with chain updates, older ones are in db
  var blockchainOpt: Option[Blockchain] = None
  // Proofs for blocks which chain sync has not given us yet, these are checked again on a next chain update
  var unprovenResponses: Set[ElectrumClient.GetMerkleResponse] = Set.empty

  def headerAt(height: Int): Option[BlockHeader] = blockchainOpt.flatMap(_ getHeader height) orElse headerDb.getHeader(height)

  def onChain(blockchain1: Blockchain): Unit = {
    blockchainOpt = Some(blockchain1)
    unprovenResponses.foreach(self ! _)
    unprovenResponses = Set.empty
  }

  context.system.eventStream.subscribe(channel = classOf[Blockchain], subscriber = self)

  client ! ElectrumClient.AddStatusListener(self)

  def receive: Receive = disconnected(Set.empty, Queue.empty, SortedMap.empty, Queue.empty)

  def disconnected(watches: Set[Watch], publishQueue: Queue[PublishAsap], block2tx: SortedMap[Long, Seq[Transaction]], getTxQueue: Queue[(GetTxWithMeta, ActorRef)]): Receive = {
    case blockchain1: Blockchain => onChain(blockchain1)
    case ElectrumClient.ElectrumReady(_, _, _) =>
      client ! ElectrumClient.HeaderSubscription(self)
    case ElectrumClient.HeaderSubscriptionResponse(height, header) =>
      watches.foreach(self ! _)
      publishQueue.foreach(self ! _)
      getTxQueue.foreach { case (msg, origin) => self.tell(msg, origin) }
      context become running(height, header, Set(), Map(), block2tx, Queue.empty)
    case watch: Watch => context become disconnected(watches + watch, publishQueue, block2tx, getTxQueue)
    case publish: PublishAsap => context become disconnected(watches, publishQueue :+ publish, block2tx, getTxQueue)
    case getTx: GetTxWithMeta => context become disconnected(watches, publishQueue, block2tx, getTxQueue :+ (getTx, sender))
  }

  def running(height: Int, tip: BlockHeader, watches: Set[Watch], scriptHashStatus: Map[ByteVector32, String], block2tx: SortedMap[Long, Seq[Transaction]], sent: Queue[Transaction]): Receive = {
    case blockchain1: Blockchain => onChain(blockchain1)

    case ElectrumClient.HeaderSubscriptionResponse(_, newtip) if tip == newtip => ()

    case ElectrumClient.HeaderSubscriptionResponse(newheight, newtip) =>
      log.info(s"new tip: ${newtip.blockId} $height")
      watches collect {
        case watch: WatchConfirmed =>
          val scriptHash = computeScriptHash(watch.publicKeyScript)
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      }
      val toPublish = block2tx.filterKeys(_ <= newheight)
      toPublish.values.flatten.foreach(publish)
      context become running(newheight, newtip, watches, scriptHashStatus, block2tx -- toPublish.keys, sent ++ toPublish.values.flatten)

    case watch: Watch if watches.contains(watch) => ()

    case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _, _, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case watch@WatchConfirmed(_, txid, publicKeyScript, _, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-confirmed on txid=$txid scriptHash=$scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case Terminated(actor) =>
      val watches1 = watches.filterNot(_.replyTo == actor)
      context become running(height, tip, watches1, scriptHashStatus, block2tx, sent)

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      scriptHashStatus.get(scriptHash) match {
        case Some(s) if s == status => log.debug(s"already have status=$status for scriptHash=$scriptHash")
        case _ if status.isEmpty => log.info(s"empty status for scriptHash=$scriptHash")
        case _ =>
          log.info(s"new status=$status for scriptHash=$scriptHash")
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      }
      context become running(height, tip, watches, scriptHashStatus + (scriptHash -> status), block2tx, sent)

    case ElectrumClient.GetScriptHashHistoryResponse(_, history) =>
      // we retrieve the transaction before checking watches
      // NB: height=-1 means that the tx is unconfirmed and at least one of its inputs is also unconfirmed. we need to take them into consideration if we want to handle unconfirmed txes (which is the case for turbo channels)
      history.filter(_.height >= -1).foreach { item => client ! ElectrumClient.GetTransaction(item.txHash, Some(item)) }

    case ElectrumClient.GetTransactionResponse(tx, Some(item: ElectrumClient.TransactionHistoryItem)) =>
      // this is for WatchSpent/WatchSpentBasic
      val watchSpentTriggered = tx.txIn.zipWithIndex.flatMap { case (txIn, inputIndex) => watches.collect {
        case WatchSpent(channel, txid, pos, publicKeyScript, amount, event, _) if txid == txIn.outPoint.txid && pos == txIn.outPoint.index.toInt =>
          // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
          // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
          // A server can make up a spending tx, so its signatures must be valid for our output
          if (ElectrumWatcher.spendsOutput(tx, inputIndex, publicKeyScript, amount)) {
            log.info(s"output $txid:$pos spent by transaction ${tx.txid}")
            channel ! WatchEventSpent(event, tx)
          } else log.warning(s"ignoring transaction ${tx.txid}: it does not validly spend output $txid:$pos")
          None
      }}.flatten

      // this is for WatchConfirmed
      val watchConfirmedTriggered = watches.collect {
        case w@WatchConfirmed(channel, txid, _, minDepth, BITCOIN_FUNDING_DEPTHOK) if txid == tx.txid && minDepth == 0 =>
          // special case for mempool watches (min depth = 0)
          val (dummyHeight, dummyTxIndex) = ElectrumWatcher.makeDummyShortChannelId(txid)
          channel ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, TxConfirmedAt(dummyHeight, tx), dummyTxIndex)
          Some(w)
        case WatchConfirmed(_, txid, _, minDepth, _) if txid == tx.txid && minDepth > 0 && item.height > 0 =>
          // min depth > 0 here
          val txheight = item.height
          val confirmations = height - txheight + 1
          log.info(s"txid=$txid was confirmed at height=$txheight and now has confirmations=$confirmations (currentHeight=$height)")
          if (confirmations >= minDepth) {
            // we need to get the tx position in the block
            client ! ElectrumClient.GetMerkle(txid, txheight, Some(tx))
          }
          None
      }.flatten

      context become running(height, tip, watches -- watchSpentTriggered -- watchConfirmedTriggered, scriptHashStatus, block2tx, sent)

    case response @ ElectrumClient.GetMerkleResponse(tx_hash, _, txheight, pos, Some(tx: Transaction)) => headerAt(txheight) match {
      case Some(header) if header.hashMerkleRoot == response.root =>
        val confirmations = height - txheight + 1
        val triggered = watches.collect {
          case w@WatchConfirmed(channel, txid, _, minDepth, event) if txid == tx_hash && confirmations >= minDepth =>
            log.info(s"txid=$txid had confirmations=$confirmations in block=$txheight pos=$pos")
            channel ! WatchEventConfirmed(event, TxConfirmedAt(txheight.toInt, tx), pos)
            w
        }
        context become running(height, tip, watches -- triggered, scriptHashStatus, block2tx, sent)

      case Some(_) =>
        // The watch stays, its history and proof are asked for again on a next block
        log.warning(s"merkle proof for txid=$tx_hash does not match our header at height=$txheight")

      case None =>
        // A header which is older than our recent chain does not come with chain updates, so its chunk is asked for (as wallet does)
        val isOlderThanRecentChain = blockchainOpt.flatMap(_.bestchain.headOption).exists(txheight < _.height)
        if (isOlderThanRecentChain) chainSync ! ElectrumClient.GetHeaders(txheight / RETARGETING_PERIOD * RETARGETING_PERIOD, RETARGETING_PERIOD)
        unprovenResponses += response
    }

    case GetTxWithMeta(txid) => client ! ElectrumClient.GetTransaction(txid, Some(sender))

    case ElectrumClient.GetTransactionResponse(tx, Some(origin: ActorRef)) => origin ! GetTxWithMetaResponse(tx.txid, Some(tx), tip.time)

    case ElectrumClient.ServerError(ElectrumClient.GetTransaction(txid, Some(origin: ActorRef)), _) => origin ! GetTxWithMetaResponse(txid, None, tip.time)

    case PublishAsap(tx) =>
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeouts = Scripts.csvTimeouts(tx)
      if (csvTimeouts.nonEmpty) {
        // watcher supports txs with multiple csv-delayed inputs: we watch all delayed parents and try to publish every
        // time a parent's relative delays are satisfied, so we will eventually succeed.
        csvTimeouts.foreach { case (parentTxId, csvTimeout) =>
          log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parentTxId=$parentTxId tx={}", tx)
          val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(tx.txIn.find(_.outPoint.txid == parentTxId).get.witness)
          self ! WatchConfirmed(self, parentTxId, parentPublicKeyScript, minDepth = csvTimeout, BITCOIN_PARENT_TX_CONFIRMED(tx))
        }
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        publish(tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), _, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty) :+ tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        publish(tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

    case ElectrumClient.BroadcastTransactionResponse(tx, error_opt) =>
      error_opt match {
        case None => log.info(s"broadcast succeeded for txid=${tx.txid} tx={}", tx)
        case Some(error) if ElectrumWatcher.isInChain(error.message) => log.info(s"broadcast ignored for txid=${tx.txid} tx={} (tx was already in blockchain)", tx)
        case Some(error) => log.error(s"broadcast failed for txid=${tx.txid} tx=$tx with error=$error")
      }
      context become running(height, tip, watches, scriptHashStatus, block2tx, sent diff Seq(tx))

    case ElectrumClient.ElectrumDisconnected =>
      // we remember watches and keep track of tx that have not yet been published
      // we also re-send the txes that we previously sent but hadn't yet received the confirmation
      context become disconnected(watches, sent.map(PublishAsap), block2tx, Queue.empty)
  }

  def publish(tx: Transaction): Unit = {
    client ! ElectrumClient.BroadcastTransaction(tx)
  }
}

object ElectrumWatcher {
  // Older Bitcoin Core says "Transaction already in block chain", newer says "Transaction outputs already in utxo set"
  def isInChain(errorMessage: String): Boolean = {
    val message = errorMessage.toLowerCase
    message.contains("already in block chain") || message.contains("already in utxo set")
  }

  // Consensus script rules: signatures and timelocks of a spend are checked, standardness rules are not
  val CONSENSUS_SCRIPT_FLAGS: Int = ScriptFlags.SCRIPT_VERIFY_P2SH | ScriptFlags.SCRIPT_VERIFY_DERSIG | ScriptFlags.SCRIPT_VERIFY_NULLDUMMY |
    ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY | ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY | ScriptFlags.SCRIPT_VERIFY_WITNESS

  def spendsOutput(tx: Transaction, inputIndex: Int, publicKeyScript: ByteVector, amount: Satoshi): Boolean = Try {
    val txIn = tx.txIn(inputIndex)
    val runner = new Script.Runner(Script.Context(tx, inputIndex, amount), CONSENSUS_SCRIPT_FLAGS)
    runner.verifyScripts(txIn.signatureScript, publicKeyScript, txIn.witness)
  } getOrElse false

  // A (blockHeight, txIndex) tuple that is extracted from the input source
  def makeDummyShortChannelId(txid: ByteVector32): (Int, Int) = {
    val txIndex = txid.bits.sliceToInt(0, 16, signed = false)
    (0, txIndex)
  }
}
