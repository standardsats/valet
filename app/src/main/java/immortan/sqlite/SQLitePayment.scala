package immortan.sqlite

import java.lang.{Integer => JInt, Long => JLong}

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair._
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.wire.FullPaymentTag
import immortan._
import immortan.crypto.Tools.Fiat2Btc
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.PaymentRequestExt
import spray.json._

import scala.util.Try


case class RelaySummary(relayed: MilliSatoshi, earned: MilliSatoshi, count: Long)

case class PaymentSummary(fees: MilliSatoshi, chainFees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)

class SQLitePayment(db: DBInterface, preimageDb: DBInterface) extends PaymentBag {
  def getPaymentInfo(paymentHash: ByteVector32): Try[PaymentInfo] = db.select(PaymentTable.selectByHashSql, paymentHash.toHex).headTry(toPaymentInfo)

  def removePaymentInfo(paymentHash: ByteVector32): Unit = {
    db.change(PaymentTable.killSql, paymentHash.toHex)
    ChannelMaster.next(ChannelMaster.paymentDbStream)
  }

  def addSearchablePayment(search: String, paymentHash: ByteVector32): Unit = {
    val newVirtualSqlPQ = db.makePreparedQuery(PaymentTable.newVirtualSql)
    db.change(newVirtualSqlPQ, search.toLowerCase, paymentHash.toHex)
    newVirtualSqlPQ.close
  }

  def searchPayments(rawSearchQuery: String): RichCursor = db.search(PaymentTable.searchSql, rawSearchQuery.toLowerCase)

  def listPendingSecrets: Iterable[ByteVector32] = {
    val incomingThreshold = System.currentTimeMillis - Bolt11Invoice.OUR_EXPIRY_SECONDS * 1000L // Skip incoming payments which are expired by now
    db.select(PaymentTable.selectPendingSql, incomingThreshold.toString).iterable(_ string PaymentTable.secret).map(ByteVector32.fromValidHex)
  }

  def listRecentPayments(limit: Int): RichCursor = {
    val outgoingThreshold = System.currentTimeMillis - 60 * 60 * 24 * 1000L // Skip outgoing payments which have been failed more than a day ago
    val incomingThreshold = System.currentTimeMillis - Bolt11Invoice.OUR_EXPIRY_SECONDS * 1000L // Skip incoming payments which are expired by now
    db.select(PaymentTable.selectRecentSql, outgoingThreshold.toString, incomingThreshold.toString, limit.toString)
  }

  def updDescription(description: PaymentDescription, payHash: ByteVector32): Unit = {
    val updateDescriptionSqlPQ = db.makePreparedQuery(PaymentTable.updateDescriptionSql)
    db.change(updateDescriptionSqlPQ, description.toJson.compactPrint, payHash.toHex)
    for (label <- description.label) addSearchablePayment(label, payHash)
    ChannelMaster.next(ChannelMaster.paymentDbStream)
    updateDescriptionSqlPQ.close
  }

  def updOkOutgoing(fulfill: RemoteFulfill, fee: MilliSatoshi): Unit = {
    db.change(PaymentTable.updOkOutgoingSql, fulfill.theirPreimage.toHex, fee.toLong: JLong, System.currentTimeMillis: JLong /* UPDATED */, fulfill.ourAdd.paymentHash.toHex)
    ChannelMaster.next(ChannelMaster.paymentDbStream)
  }

  def updOkIncoming(receivedAmount: MilliSatoshi, paymentHash: ByteVector32): Unit = {
    db.change(PaymentTable.updOkIncomingSql, receivedAmount.toLong: JLong, System.currentTimeMillis: JLong /* SEEN */, System.currentTimeMillis: JLong /* UPDATED */, paymentHash.toHex)
    ChannelMaster.next(ChannelMaster.paymentDbStream)
  }

  def updAbortedOutgoing(paymentHash: ByteVector32): Unit = {
    db.change(PaymentTable.updStatusSql, PaymentStatus.ABORTED: JInt, System.currentTimeMillis: JLong /* UPDATED */, paymentHash.toHex)
    ChannelMaster.next(ChannelMaster.paymentDbStream)
  }

  def replaceOutgoingPayment(prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction], finalAmount: MilliSatoshi,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi, seenAt: Long): Unit =
    db txWrap {
      removePaymentInfo(prex.pr.paymentHash)
      val newSqlPQ = db.makePreparedQuery(PaymentTable.newSql)
      db.change(newSqlPQ, prex.raw, ChannelMaster.NO_PREIMAGE.toHex, PaymentStatus.PENDING: JInt, seenAt: JLong, System.currentTimeMillis: JLong /* UPDATED */, description.toJson.compactPrint,
        action.map(_.toJson.compactPrint).getOrElse(PaymentInfo.NO_ACTION), prex.pr.paymentHash.toHex, prex.pr.paymentSecret.get.toHex, 0L: JLong /* RECEIVED AMOUNT = 0 FOR OUTGOING PAYMENT */,
        finalAmount.toLong: JLong, 0L: JLong /* FEE IS UNCERTAIN YET */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint, chainFee.toLong: JLong, 0: JInt /* INCOMING TYPE = 0 */)
      ChannelMaster.next(ChannelMaster.paymentDbStream)
      newSqlPQ.close
    }

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit =
    db txWrap {
      removePaymentInfo(prex.pr.paymentHash)
      val newSqlPQ = db.makePreparedQuery(PaymentTable.newSql)
      db.change(newSqlPQ, prex.raw, preimage.toHex, PaymentStatus.PENDING: JInt, System.currentTimeMillis: JLong /* SEEN */, System.currentTimeMillis: JLong /* UPDATED */, description.toJson.compactPrint,
        PaymentInfo.NO_ACTION, prex.pr.paymentHash.toHex, prex.pr.paymentSecret.get.toHex, prex.pr.amountOpt.getOrElse(0L.msat).toLong: JLong /* 0 WHEN UNDEFINED */, 0L: JLong /* SENT = 0 MSAT, NOTHING TO SEND */,
        0L: JLong /* NO FEE FOR INCOMING PAYMENT */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint, 0L: JLong /* NO CHAIN FEE FOR INCOMING PAYMENTS */, 1: JInt /* INCOMING TYPE = 1 */)
      ChannelMaster.next(ChannelMaster.paymentDbStream)
      newSqlPQ.close
    }

  def paymentSummary: Try[PaymentSummary] =
    db.select(PaymentTable.selectSummarySql).headTry { rc =>
      PaymentSummary(fees = MilliSatoshi(rc long 0), chainFees = MilliSatoshi(rc long 1), received = MilliSatoshi(rc long 2), sent = MilliSatoshi(rc long 3), count = rc long 4)
    }

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(prString = rc string PaymentTable.pr, preimage = ByteVector32.fromValidHex(rc string PaymentTable.preimage),
      status = rc int PaymentTable.status, seenAt = rc long PaymentTable.seenAt, updatedAt = rc long PaymentTable.updatedAt, description = to[PaymentDescription](rc string PaymentTable.description),
      actionString = rc string PaymentTable.action, paymentHash = ByteVector32.fromValidHex(rc string PaymentTable.hash), paymentSecret = ByteVector32.fromValidHex(rc string PaymentTable.secret),
      received = MilliSatoshi(rc long PaymentTable.receivedMsat), sent = MilliSatoshi(rc long PaymentTable.sentMsat), fee = MilliSatoshi(rc long PaymentTable.feeMsat),
      balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceMsat), fiatRatesString = rc string PaymentTable.fiatRates,
      chainFee = MilliSatoshi(rc long PaymentTable.chainFeeMsat), incoming = rc long PaymentTable.incoming)

  // Preimage storage

  def getPreimage(hash: ByteVector32): Try[ByteVector32] = preimageDb.select(PreimageTable.selectByHashSql, hash.toHex).headTry(_ string PreimageTable.preimage).map(ByteVector32.fromValidHex)

  def setPreimage(paymentHash: ByteVector32, preimage: ByteVector32): Unit = preimageDb.change(PreimageTable.newSql, paymentHash.toHex, preimage.toHex)

  // Relayed payments

  def listRecentRelays(limit: Int): RichCursor = db.select(RelayTable.selectRecentSql, limit.toString)

  def addRelayedPreimageInfo(fullTag: FullPaymentTag, preimage: ByteVector32, relayed: MilliSatoshi, earned: MilliSatoshi): Unit = {
    db.change(RelayTable.newSql, fullTag.paymentHash.toHex, fullTag.paymentSecret.toHex, preimage.toHex, System.currentTimeMillis: JLong /* SEEN AT */,
      System.currentTimeMillis: JLong /* UPDATED AT */, relayed.toLong: JLong, earned.toLong: JLong)
    ChannelMaster.next(ChannelMaster.relayDbStream)
  }

  def relaySummary: Try[RelaySummary] = db.select(RelayTable.selectSummarySql).headTry { rc =>
    RelaySummary(relayed = MilliSatoshi(rc long 0), earned = MilliSatoshi(rc long 1), count = rc long 2)
  }

  def toRelayedPreimageInfo(rc: RichCursor): RelayedPreimageInfo =
    RelayedPreimageInfo(rc string RelayTable.hash, rc string RelayTable.secret, rc string RelayTable.preimage,
      MilliSatoshi(rc long RelayTable.relayed), MilliSatoshi(rc long RelayTable.earned), rc long RelayTable.seenAt,
      rc long RelayTable.updatedAt)
}