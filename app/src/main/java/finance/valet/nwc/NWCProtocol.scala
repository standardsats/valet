package finance.valet.nwc

import fr.acinq.eclair.MilliSatoshi
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.util.Try


/**
 * NIP-47 Nostr Wallet Connect protocol implementation.
 *
 * Handles parsing of NWC request methods:
 * - pay_invoice: Pay a BOLT11 invoice
 * - make_invoice: Create a new invoice
 * - get_balance: Get wallet balance
 * - get_info: Get wallet info
 * - list_transactions: List recent transactions
 *
 * And formatting of responses.
 */
object NWCProtocol {

  // NWC Method names
  val PAY_INVOICE = "pay_invoice"
  val MAKE_INVOICE = "make_invoice"
  val GET_BALANCE = "get_balance"
  val GET_INFO = "get_info"
  val LIST_TRANSACTIONS = "list_transactions"
  val LOOKUP_INVOICE = "lookup_invoice"

  // Error codes from NIP-47
  object ErrorCode {
    val RATE_LIMITED = "RATE_LIMITED"
    val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
    val INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE"
    val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    val RESTRICTED = "RESTRICTED"
    val UNAUTHORIZED = "UNAUTHORIZED"
    val INTERNAL = "INTERNAL"
    val OTHER = "OTHER"
    val PAYMENT_FAILED = "PAYMENT_FAILED"
    val NOT_FOUND = "NOT_FOUND"
  }

  /**
   * Parse a NWC request from JSON content.
   */
  def parseRequest(content: String): Try[NWCRequest] = Try {
    val json = content.parseJson.asJsObject
    val method = json.fields("method").convertTo[String]
    val params = json.fields.get("params").map(_.asJsObject).getOrElse(JsObject())

    method match {
      case PAY_INVOICE =>
        val invoice = params.fields("invoice").convertTo[String]
        val amount = params.fields.get("amount").map(_.convertTo[Long])
        PayInvoiceRequest(invoice, amount)

      case MAKE_INVOICE =>
        val amount = params.fields("amount").convertTo[Long]
        val description = params.fields.get("description").map(_.convertTo[String])
        val descriptionHash = params.fields.get("description_hash").map(_.convertTo[String])
        val expiry = params.fields.get("expiry").map(_.convertTo[Long])
        MakeInvoiceRequest(amount, description, descriptionHash, expiry)

      case GET_BALANCE =>
        GetBalanceRequest()

      case GET_INFO =>
        GetInfoRequest()

      case LIST_TRANSACTIONS =>
        val from = params.fields.get("from").map(_.convertTo[Long])
        val until = params.fields.get("until").map(_.convertTo[Long])
        val limit = params.fields.get("limit").map(_.convertTo[Int])
        val offset = params.fields.get("offset").map(_.convertTo[Int])
        val unpaid = params.fields.get("unpaid").map(_.convertTo[Boolean]).getOrElse(false)
        val invoiceType = params.fields.get("type").map(_.convertTo[String])
        ListTransactionsRequest(from, until, limit, offset, unpaid, invoiceType)

      case LOOKUP_INVOICE =>
        val paymentHash = params.fields.get("payment_hash").map(_.convertTo[String])
        val invoice = params.fields.get("invoice").map(_.convertTo[String])
        LookupInvoiceRequest(paymentHash, invoice)

      case other =>
        throw new IllegalArgumentException(s"Unknown method: $other")
    }
  }

  /**
   * Format a successful response.
   */
  def formatSuccessResponse(resultType: String, result: JsObject): String = {
    JsObject(
      "result_type" -> JsString(resultType),
      "result" -> result
    ).compactPrint
  }

  /**
   * Format an error response.
   */
  def formatErrorResponse(resultType: String, code: String, message: String): String = {
    JsObject(
      "result_type" -> JsString(resultType),
      "error" -> JsObject(
        "code" -> JsString(code),
        "message" -> JsString(message)
      )
    ).compactPrint
  }

  // Response formatters for each method

  def formatPayInvoiceSuccess(preimage: String): String = {
    formatSuccessResponse(PAY_INVOICE, JsObject(
      "preimage" -> JsString(preimage)
    ))
  }

  def formatPayInvoiceError(code: String, message: String): String = {
    formatErrorResponse(PAY_INVOICE, code, message)
  }

  def formatMakeInvoiceSuccess(
    invoice: String,
    paymentHash: String,
    amount: Long,
    createdAt: Long,
    expiresAt: Long,
    description: Option[String]
  ): String = {
    val fields = scala.collection.mutable.Map[String, JsValue](
      "type" -> JsString("incoming"),
      "invoice" -> JsString(invoice),
      "payment_hash" -> JsString(paymentHash),
      "amount" -> JsNumber(amount),
      "created_at" -> JsNumber(createdAt),
      "expires_at" -> JsNumber(expiresAt)
    )
    description.foreach(d => fields("description") = JsString(d))

    formatSuccessResponse(MAKE_INVOICE, JsObject(fields.toMap))
  }

  def formatMakeInvoiceError(code: String, message: String): String = {
    formatErrorResponse(MAKE_INVOICE, code, message)
  }

  def formatGetBalanceSuccess(balanceMsat: Long): String = {
    formatSuccessResponse(GET_BALANCE, JsObject(
      "balance" -> JsNumber(balanceMsat)
    ))
  }

  def formatGetBalanceError(code: String, message: String): String = {
    formatErrorResponse(GET_BALANCE, code, message)
  }

  def formatGetInfoSuccess(
    alias: String,
    color: String,
    pubkey: String,
    network: String,
    blockHeight: Int,
    blockHash: String,
    methods: List[String]
  ): String = {
    formatSuccessResponse(GET_INFO, JsObject(
      "alias" -> JsString(alias),
      "color" -> JsString(color),
      "pubkey" -> JsString(pubkey),
      "network" -> JsString(network),
      "block_height" -> JsNumber(blockHeight),
      "block_hash" -> JsString(blockHash),
      "methods" -> JsArray(methods.map(JsString(_)).toVector)
    ))
  }

  def formatGetInfoError(code: String, message: String): String = {
    formatErrorResponse(GET_INFO, code, message)
  }

  def formatListTransactionsSuccess(transactions: List[TransactionInfo]): String = {
    val txArray = transactions.map { tx =>
      val fields = scala.collection.mutable.Map[String, JsValue](
        "type" -> JsString(tx.txType),
        "payment_hash" -> JsString(tx.paymentHash),
        "amount" -> JsNumber(tx.amount),
        "created_at" -> JsNumber(tx.createdAt)
      )
      tx.invoice.foreach(i => fields("invoice") = JsString(i))
      tx.preimage.foreach(p => fields("preimage") = JsString(p))
      tx.description.foreach(d => fields("description") = JsString(d))
      tx.feesPaid.foreach(f => fields("fees_paid") = JsNumber(f))
      tx.settledAt.foreach(s => fields("settled_at") = JsNumber(s))

      JsObject(fields.toMap)
    }

    formatSuccessResponse(LIST_TRANSACTIONS, JsObject(
      "transactions" -> JsArray(txArray.toVector)
    ))
  }

  def formatListTransactionsError(code: String, message: String): String = {
    formatErrorResponse(LIST_TRANSACTIONS, code, message)
  }

  def formatLookupInvoiceSuccess(tx: TransactionInfo): String = {
    val fields = scala.collection.mutable.Map[String, JsValue](
      "type" -> JsString(tx.txType),
      "payment_hash" -> JsString(tx.paymentHash),
      "amount" -> JsNumber(tx.amount),
      "created_at" -> JsNumber(tx.createdAt)
    )
    tx.invoice.foreach(i => fields("invoice") = JsString(i))
    tx.preimage.foreach(p => fields("preimage") = JsString(p))
    tx.description.foreach(d => fields("description") = JsString(d))
    tx.feesPaid.foreach(f => fields("fees_paid") = JsNumber(f))
    tx.settledAt.foreach(s => fields("settled_at") = JsNumber(s))

    formatSuccessResponse(LOOKUP_INVOICE, JsObject(fields.toMap))
  }

  def formatLookupInvoiceError(code: String, message: String): String = {
    formatErrorResponse(LOOKUP_INVOICE, code, message)
  }

  def formatNotImplementedError(method: String): String = {
    formatErrorResponse(method, ErrorCode.NOT_IMPLEMENTED, s"Method $method is not implemented")
  }
}


// Request types

sealed trait NWCRequest {
  def method: String
}

case class PayInvoiceRequest(
  invoice: String,
  amount: Option[Long] = None // Optional amount override for zero-amount invoices
) extends NWCRequest {
  val method = NWCProtocol.PAY_INVOICE
}

case class MakeInvoiceRequest(
  amount: Long, // Amount in millisatoshis
  description: Option[String] = None,
  descriptionHash: Option[String] = None,
  expiry: Option[Long] = None // Seconds until expiry
) extends NWCRequest {
  val method = NWCProtocol.MAKE_INVOICE
}

case class GetBalanceRequest() extends NWCRequest {
  val method = NWCProtocol.GET_BALANCE
}

case class GetInfoRequest() extends NWCRequest {
  val method = NWCProtocol.GET_INFO
}

case class ListTransactionsRequest(
  from: Option[Long] = None,
  until: Option[Long] = None,
  limit: Option[Int] = None,
  offset: Option[Int] = None,
  unpaid: Boolean = false,
  invoiceType: Option[String] = None // "incoming" or "outgoing"
) extends NWCRequest {
  val method = NWCProtocol.LIST_TRANSACTIONS
}

case class LookupInvoiceRequest(
  paymentHash: Option[String] = None,
  invoice: Option[String] = None
) extends NWCRequest {
  val method = NWCProtocol.LOOKUP_INVOICE
}


// Response types

case class TransactionInfo(
  txType: String, // "incoming" or "outgoing"
  paymentHash: String,
  amount: Long, // millisatoshis
  createdAt: Long, // unix timestamp
  invoice: Option[String] = None,
  preimage: Option[String] = None,
  description: Option[String] = None,
  feesPaid: Option[Long] = None, // millisatoshis
  settledAt: Option[Long] = None // unix timestamp
)
