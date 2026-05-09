package finance.valet.nwc

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.payment.Bolt11Invoice
import immortan._
import immortan.crypto.Tools._
import immortan.fsm.{OutgoingPaymentListener, OutgoingPaymentSenderData}
import immortan.utils.PaymentRequestExt

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}


/**
 * Bridge between NWC protocol and IMMORTAN Lightning implementation.
 *
 * Handles:
 * - Paying invoices
 * - Creating invoices
 * - Getting balance
 * - Listing transactions
 */
object IMMORTANBridge {

  /**
   * Get the current wallet balance in millisatoshis.
   * Returns the sum of all hosted channel local balances.
   */
  def getBalance: Try[Long] = Try {
    require(LNParams.isOperational, "Wallet is not operational")

    val hostedBalance = LNParams.cm.allHostedCommits
      .filter(_.error.isEmpty) // Only count operational channels
      .map(_.lastCrossSignedState.localBalanceMsat)
      .foldLeft(MilliSatoshi(0L))(_ + _)

    hostedBalance.toLong
  }

  /**
   * Get wallet info.
   */
  def getInfo: Try[WalletInfo] = Try {
    require(LNParams.isOperational, "Wallet is not operational")

    val network = if (LNParams.isMainnet) "mainnet" else "testnet"
    val blockHeight = LNParams.blockCount.get.toInt
    val blockHash = "" // We don't have easy access to block hash

    // Our "node" pubkey for NWC purposes - use the first channel's local pubkey or generate one
    val pubkey = LNParams.secret.keys.ourNodePrivateKey.publicKey.toString

    WalletInfo(
      alias = "Valet",
      color = "#FF9900", // Orange
      pubkey = pubkey,
      network = network,
      blockHeight = blockHeight,
      blockHash = blockHash,
      methods = List(
        NWCProtocol.PAY_INVOICE,
        NWCProtocol.MAKE_INVOICE,
        NWCProtocol.GET_BALANCE,
        NWCProtocol.GET_INFO,
        NWCProtocol.LIST_TRANSACTIONS,
        NWCProtocol.LOOKUP_INVOICE
      )
    )
  }

  /**
   * Create a new invoice.
   *
   * @param amountMsat Amount in millisatoshis
   * @param description Invoice description
   * @param expirySecs Seconds until expiry (default 3600 = 1 hour)
   * @return Created invoice info
   */
  def makeInvoice(
    amountMsat: Long,
    description: Option[String],
    expirySecs: Option[Long]
  ): Try[CreatedInvoice] = Try {
    require(LNParams.isOperational, "Wallet is not operational")

    val amount = MilliSatoshi(amountMsat)
    val desc = description.getOrElse("")

    // Get receivable channels
    val sortedReceivable = LNParams.cm.sortedReceivable(LNParams.cm.all.values)
    require(sortedReceivable.nonEmpty, "No channels available to receive")

    // Generate preimage and payment hash
    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val paymentSecret = randomBytes32

    // Get the invoice key
    val invoiceKey = LNParams.secret.keys.fakeInvoiceKey(paymentSecret)

    // Create payment description
    val paymentDescription = PaymentDescription(
      split = None,
      label = None,
      semanticOrder = None,
      invoiceText = desc,
      proofTxid = None,
      meta = None,
      holdPeriodSec = None,
      toSelfPreimage = None
    )

    // Create the invoice using ChannelMaster
    val prExt = LNParams.cm.makePrExt(
      toReceive = amount,
      description = paymentDescription,
      allowedChans = sortedReceivable.takeRight(4),
      hash = paymentHash,
      secret = paymentSecret,
      node_key = invoiceKey
    )

    // Store the preimage and payment info
    val balanceSnap = MilliSatoshi(getBalance.getOrElse(0L))
    val fiatRateSnap = LNParams.fiatRates.info.rates

    LNParams.cm.payBag.replaceIncomingPayment(
      prex = prExt,
      preimage = preimage,
      description = paymentDescription,
      balanceSnap = balanceSnap,
      fiatRateSnap = fiatRateSnap
    )

    val createdAt = System.currentTimeMillis() / 1000
    val expiresAt = createdAt + expirySecs.getOrElse(3600L)

    CreatedInvoice(
      invoice = prExt.raw,
      paymentHash = paymentHash.toHex,
      amount = amountMsat,
      createdAt = createdAt,
      expiresAt = expiresAt,
      description = description
    )
  }

  /**
   * Pay a BOLT11 invoice.
   *
   * @param bolt11 The invoice to pay
   * @param amountMsat Optional amount override for zero-amount invoices
   * @return Future that completes with the preimage on success
   */
  def payInvoice(bolt11: String, amountMsat: Option[Long]): Future[PaymentResult] = {
    val promise = Promise[PaymentResult]()

    Try {
      require(LNParams.isOperational, "Wallet is not operational")

      // Parse the invoice
      val prExt = PaymentRequestExt.fromUri(bolt11)

      // Determine the amount to pay
      val amount: MilliSatoshi = if (amountMsat.isDefined) {
        MilliSatoshi(amountMsat.get)
      } else if (prExt.pr.amountOpt.isDefined) {
        prExt.pr.amountOpt.get
      } else {
        throw new IllegalArgumentException("Invoice has no amount and no amount was provided")
      }

      // Check if already paid or in flight
      LNParams.cm.checkIfSendable(prExt.pr.paymentHash) match {
        case Some(PaymentInfo.NOT_SENDABLE_IN_FLIGHT) =>
          throw new IllegalStateException("Payment already in flight")
        case Some(PaymentInfo.NOT_SENDABLE_SUCCESS) =>
          // Already paid - get the preimage
          LNParams.cm.getPreimageMemo.get(prExt.pr.paymentHash) match {
            case Success(preimage) =>
              promise.success(PaymentResult(preimage.toHex, 0L))
              return promise.future
            case Failure(_) =>
              throw new IllegalStateException("Payment marked as successful but preimage not found")
          }
        case None => // OK to proceed
      }

      // Get usable channels
      val usableChans = LNParams.cm.all.values.filter(Channel.isOperational).toSeq
      require(usableChans.nonEmpty, "No operational channels available")

      // Check if we have enough balance
      val maxSendable = LNParams.cm.maxSendable(usableChans)
      require(amount <= maxSendable, s"Insufficient balance. Max sendable: ${maxSendable.toLong} msat")

      // Calculate fee reserve
      val feeReserve = LNParams.cm.feeReserve(amount)

      // Create payment description
      val description = PaymentDescription(
        split = None,
        label = None,
        semanticOrder = None,
        invoiceText = prExt.pr.description.left.toOption.getOrElse(""),
        proofTxid = None,
        meta = None,
        holdPeriodSec = None,
        toSelfPreimage = None
      )

      // Store the outgoing payment
      val balanceSnap = MilliSatoshi(getBalance.getOrElse(0L))
      val fiatRateSnap = LNParams.fiatRates.info.rates

      LNParams.cm.payBag.replaceOutgoingPayment(
        prex = prExt,
        description = description,
        action = None,
        finalAmount = amount,
        balanceSnap = balanceSnap,
        fiatRateSnap = fiatRateSnap,
        chainFee = MilliSatoshi(0L),
        seenAt = System.currentTimeMillis
      )

      // Create the send command
      val cmd = LNParams.cm.makeSendCmd(prExt, usableChans, feeReserve, amount)

      // Create a listener to track payment result
      val paymentListener = new OutgoingPaymentListener {
        override def wholePaymentSucceeded(data: OutgoingPaymentSenderData): Unit = {
          if (data.cmd.fullTag.paymentHash == prExt.pr.paymentHash) {
            // Get the preimage
            LNParams.cm.getPreimageMemo.get(prExt.pr.paymentHash) match {
              case Success(preimage) =>
                promise.trySuccess(PaymentResult(preimage.toHex, data.usedFee.toLong))
              case Failure(e) =>
                promise.tryFailure(new Exception("Payment succeeded but preimage not found"))
            }
            LNParams.cm.localPaymentListeners -= this
          }
        }

        override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = {
          if (data.cmd.fullTag.paymentHash == prExt.pr.paymentHash) {
            promise.tryFailure(new Exception(s"Payment failed: ${data.failuresAsString}"))
            LNParams.cm.localPaymentListeners -= this
          }
        }
      }

      // Add the listener
      LNParams.cm.localPaymentListeners += paymentListener

      // Start the payment
      LNParams.cm.localSend(cmd)

    } match {
      case Failure(e) =>
        promise.tryFailure(e)
      case Success(_) =>
        // Payment initiated, promise will be completed by listener
    }

    promise.future
  }

  /**
   * List recent transactions.
   *
   * @param limit Maximum number of transactions to return
   * @param offset Number of transactions to skip
   * @param unpaid Include unpaid invoices
   * @param txType Filter by type ("incoming" or "outgoing")
   * @return List of transactions
   */
  def listTransactions(
    limit: Int = 50,
    offset: Int = 0,
    unpaid: Boolean = false,
    txType: Option[String] = None
  ): Try[List[TransactionInfo]] = Try {
    require(LNParams.isOperational, "Wallet is not operational")

    val cursor = LNParams.cm.payBag.listRecentPayments(limit + offset)
    val payments = cursor.iterable { rc =>
      LNParams.cm.payBag.toPaymentInfo(rc)
    }.toList

    payments
      .drop(offset)
      .take(limit)
      .filter { pi =>
        // Filter by type if specified
        txType match {
          case Some("incoming") => pi.isIncoming
          case Some("outgoing") => !pi.isIncoming
          case _ => true
        }
      }
      .filter { pi =>
        // Filter unpaid if not requested
        unpaid || pi.status == PaymentStatus.SUCCEEDED
      }
      .map(paymentInfoToTransaction)
  }

  /**
   * Look up an invoice by payment hash or bolt11.
   */
  def lookupInvoice(
    paymentHash: Option[String],
    invoice: Option[String]
  ): Try[TransactionInfo] = Try {
    require(LNParams.isOperational, "Wallet is not operational")

    val hash: ByteVector32 = paymentHash match {
      case Some(h) => ByteVector32.fromValidHex(h)
      case None =>
        invoice match {
          case Some(inv) =>
            val prExt = PaymentRequestExt.fromUri(inv)
            prExt.pr.paymentHash
          case None =>
            throw new IllegalArgumentException("Either payment_hash or invoice must be provided")
        }
    }

    LNParams.cm.payBag.getPaymentInfo(hash) match {
      case Success(pi) => paymentInfoToTransaction(pi)
      case Failure(_) => throw new Exception("Invoice not found")
    }
  }

  /**
   * Convert PaymentInfo to TransactionInfo.
   */
  private def paymentInfoToTransaction(pi: PaymentInfo): TransactionInfo = {
    TransactionInfo(
      txType = if (pi.isIncoming) "incoming" else "outgoing",
      paymentHash = pi.paymentHash.toHex,
      amount = if (pi.isIncoming) pi.received.toLong else pi.sent.toLong,
      createdAt = pi.seenAt / 1000, // Convert to unix timestamp
      invoice = Some(pi.prString),
      preimage = if (pi.status == PaymentStatus.SUCCEEDED) {
        LNParams.cm.getPreimageMemo.get(pi.paymentHash).toOption.map(_.toHex)
      } else None,
      description = Some(pi.description.invoiceText),
      feesPaid = if (!pi.isIncoming) Some(pi.fee.toLong) else None,
      settledAt = if (pi.status == PaymentStatus.SUCCEEDED) Some(pi.updatedAt / 1000) else None
    )
  }
}


// Result types

case class WalletInfo(
  alias: String,
  color: String,
  pubkey: String,
  network: String,
  blockHeight: Int,
  blockHash: String,
  methods: List[String]
)

case class CreatedInvoice(
  invoice: String,
  paymentHash: String,
  amount: Long,
  createdAt: Long,
  expiresAt: Long,
  description: Option[String]
)

case class PaymentResult(
  preimage: String,
  feePaid: Long
)
