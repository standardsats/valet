package finance.valet.nwc

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.Tools._
import scodec.bits.ByteVector

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


/**
 * NWC Connection Manager.
 *
 * Manages NWC connections:
 * - Creating new connections
 * - Deleting connections
 * - Starting/stopping relay listeners
 * - Routing incoming requests to handlers
 */
class NWCManager(database: NWCDatabase) {

  private val activeRelays = mutable.Map[String, NostrRelay]()
  private val listeners = mutable.Set[NWCManagerListener]()

  /**
   * Add a listener for NWC events.
   */
  def addListener(listener: NWCManagerListener): Unit = {
    listeners += listener
  }

  /**
   * Remove a listener.
   */
  def removeListener(listener: NWCManagerListener): Unit = {
    listeners -= listener
  }

  /**
   * Create a new NWC connection.
   *
   * @param name User-friendly name for this connection
   * @param connectionType Type of connection: "send" (full) or "receive" (limited)
   * @param relay Relay URL (default: wss://relay.damus.io)
   * @return The connection info including the connection URL
   */
  def createConnection(
    name: String,
    connectionType: String = NWCConnectionType.SEND,
    relay: String = NostrRelay.DEFAULT_RELAY
  ): Try[NWCConnection] = Try {
    // Generate a new keypair for this connection
    val (privateKey, publicKey) = NWCCrypto.generateKeyPair()

    // Generate a secret for the connection URL
    val secret = NWCCrypto.generateSecret()

    val id = java.util.UUID.randomUUID().toString
    val createdAt = System.currentTimeMillis()
    val walletPubkeyHex = NWCCrypto.pubkeyToNostrHex(publicKey)

    // Generate the connection URL
    val connectionUrl = NostrRelay.generateConnectionUrl(walletPubkeyHex, relay, secret.toHex)

    val connection = NWCConnection(
      id = id,
      name = name,
      walletPrivkey = privateKey.value,
      walletPubkey = walletPubkeyHex,
      secret = secret.toHex,
      relay = relay,
      createdAt = createdAt,
      connectionUrl = connectionUrl,
      connectionType = connectionType
    )

    // Save to database
    database.saveConnection(connection)

    // Start listening on this connection
    startConnection(connection)

    // Notify listeners
    listeners.foreach(_.onConnectionCreated(connection))

    connection
  }

  /**
   * Delete a connection.
   */
  def deleteConnection(id: String): Try[Unit] = Try {
    // Stop the relay if running
    stopConnection(id)

    // Remove from database
    database.deleteConnection(id)

    // Notify listeners
    listeners.foreach(_.onConnectionDeleted(id))
  }

  /**
   * Get all connections.
   */
  def listConnections(): List[NWCConnection] = {
    database.listConnections()
  }

  /**
   * Get a specific connection.
   */
  def getConnection(id: String): Option[NWCConnection] = {
    database.getConnection(id)
  }

  /**
   * Start all saved connections.
   */
  def startAll(): Unit = {
    listConnections().foreach(startConnection)
  }

  /**
   * Stop all connections.
   */
  def stopAll(): Unit = {
    activeRelays.keys.toList.foreach(stopConnection)
  }

  /**
   * Start a specific connection.
   */
  def startConnection(connection: NWCConnection): Unit = {
    if (activeRelays.contains(connection.id)) {
      return // Already running
    }

    val privateKey = PrivateKey(ByteVector32(ByteVector.fromValidHex(connection.walletPrivkey.toHex)))
    val publicKey = privateKey.publicKey

    val relayListener = new NostrRelayListener {
      override def onConnected(): Unit = {
        listeners.foreach(_.onConnectionStatusChanged(connection.id, connected = true))
      }

      override def onDisconnected(reason: String): Unit = {
        listeners.foreach(_.onConnectionStatusChanged(connection.id, connected = false))
      }

      override def onError(message: String): Unit = {
        listeners.foreach(_.onConnectionError(connection.id, message))
      }

      override def onNotice(message: String): Unit = {
        // Relay notices can be logged or ignored
      }

      override def onPublishResult(eventId: String, success: Boolean, message: String): Unit = {
        // Track publish results if needed
      }

      override def onNWCRequest(eventId: String, senderPubkey: String, content: String, createdAt: Long): Unit = {
        handleRequest(connection, eventId, senderPubkey, content)
      }
    }

    val relay = new NostrRelay(
      relayUrl = connection.relay,
      walletPrivateKey = privateKey,
      walletPublicKey = publicKey,
      listener = relayListener
    )

    activeRelays(connection.id) = relay
    relay.connect()
  }

  /**
   * Stop a specific connection.
   */
  def stopConnection(id: String): Unit = {
    activeRelays.get(id).foreach { relay =>
      relay.disconnect()
      activeRelays -= id
    }
  }

  /**
   * Check if a connection is active.
   */
  def isConnectionActive(id: String): Boolean = {
    activeRelays.get(id).exists(_.isActive)
  }

  /**
   * Handle an incoming NWC request.
   */
  private def handleRequest(
    connection: NWCConnection,
    eventId: String,
    senderPubkey: String,
    content: String
  ): Unit = {
    // Parse the request
    NWCProtocol.parseRequest(content) match {
      case Success(request) =>
        // Process the request and send response
        processRequest(connection, eventId, senderPubkey, request)

      case Failure(e) =>
        // Send error response
        sendResponse(connection, eventId, senderPubkey,
          NWCProtocol.formatErrorResponse("unknown", NWCProtocol.ErrorCode.OTHER, e.getMessage))
    }
  }

  /**
   * Process a parsed NWC request.
   * Enforces permissions based on connection type.
   */
  private def processRequest(
    connection: NWCConnection,
    requestEventId: String,
    senderPubkey: String,
    request: NWCRequest
  ): Unit = {
    request match {
      case req: GetBalanceRequest =>
        // Allowed for both send and receive connections
        handleGetBalance(connection, requestEventId, senderPubkey)

      case req: GetInfoRequest =>
        // Allowed for both, but returns different methods based on type
        handleGetInfo(connection, requestEventId, senderPubkey)

      case req: MakeInvoiceRequest =>
        // Allowed for both send and receive connections
        handleMakeInvoice(connection, requestEventId, senderPubkey, req)

      case req: PayInvoiceRequest =>
        // Only allowed for SEND connections
        if (connection.connectionType == NWCConnectionType.RECEIVE) {
          sendResponse(connection, requestEventId, senderPubkey,
            NWCProtocol.formatPayInvoiceError(
              NWCProtocol.ErrorCode.RESTRICTED,
              "This connection does not have permission to pay invoices"
            ))
        } else {
          handlePayInvoice(connection, requestEventId, senderPubkey, req)
        }

      case req: ListTransactionsRequest =>
        // Allowed for both send and receive connections
        handleListTransactions(connection, requestEventId, senderPubkey, req)

      case req: LookupInvoiceRequest =>
        // Allowed for both send and receive connections
        handleLookupInvoice(connection, requestEventId, senderPubkey, req)
    }
  }

  private def handleGetBalance(connection: NWCConnection, requestEventId: String, senderPubkey: String): Unit = {
    IMMORTANBridge.getBalance match {
      case Success(balance) =>
        sendResponse(connection, requestEventId, senderPubkey, NWCProtocol.formatGetBalanceSuccess(balance))
      case Failure(e) =>
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatGetBalanceError(NWCProtocol.ErrorCode.INTERNAL, e.getMessage))
    }
  }

  private def handleGetInfo(connection: NWCConnection, requestEventId: String, senderPubkey: String): Unit = {
    IMMORTANBridge.getInfo match {
      case Success(info) =>
        // Filter methods based on connection type
        val allowedMethods = if (connection.connectionType == NWCConnectionType.RECEIVE) {
          // Receive-only: exclude pay_invoice
          info.methods.filterNot(_ == NWCProtocol.PAY_INVOICE)
        } else {
          // Send: all methods allowed
          info.methods
        }
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatGetInfoSuccess(
            info.alias, info.color, info.pubkey, info.network,
            info.blockHeight, info.blockHash, allowedMethods
          ))
      case Failure(e) =>
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatGetInfoError(NWCProtocol.ErrorCode.INTERNAL, e.getMessage))
    }
  }

  private def handleMakeInvoice(
    connection: NWCConnection,
    requestEventId: String,
    senderPubkey: String,
    req: MakeInvoiceRequest
  ): Unit = {
    IMMORTANBridge.makeInvoice(req.amount, req.description, req.expiry) match {
      case Success(invoice) =>
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatMakeInvoiceSuccess(
            invoice.invoice, invoice.paymentHash, invoice.amount,
            invoice.createdAt, invoice.expiresAt, invoice.description
          ))
      case Failure(e) =>
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatMakeInvoiceError(NWCProtocol.ErrorCode.INTERNAL, e.getMessage))
    }
  }

  private def handlePayInvoice(
    connection: NWCConnection,
    requestEventId: String,
    senderPubkey: String,
    req: PayInvoiceRequest
  ): Unit = {
    // Pay invoice is async
    IMMORTANBridge.payInvoice(req.invoice, req.amount).onComplete {
      case Success(result) =>
        sendResponse(connection, requestEventId, senderPubkey, NWCProtocol.formatPayInvoiceSuccess(result.preimage))
      case Failure(e) =>
        val errorCode = if (e.getMessage.contains("Insufficient balance")) {
          NWCProtocol.ErrorCode.INSUFFICIENT_BALANCE
        } else {
          NWCProtocol.ErrorCode.PAYMENT_FAILED
        }
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatPayInvoiceError(errorCode, e.getMessage))
    }
  }

  private def handleListTransactions(
    connection: NWCConnection,
    requestEventId: String,
    senderPubkey: String,
    req: ListTransactionsRequest
  ): Unit = {
    IMMORTANBridge.listTransactions(
      req.limit.getOrElse(50),
      req.offset.getOrElse(0),
      req.unpaid,
      req.invoiceType
    ) match {
      case Success(txs) =>
        sendResponse(connection, requestEventId, senderPubkey, NWCProtocol.formatListTransactionsSuccess(txs))
      case Failure(e) =>
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatListTransactionsError(NWCProtocol.ErrorCode.INTERNAL, e.getMessage))
    }
  }

  private def handleLookupInvoice(
    connection: NWCConnection,
    requestEventId: String,
    senderPubkey: String,
    req: LookupInvoiceRequest
  ): Unit = {
    IMMORTANBridge.lookupInvoice(req.paymentHash, req.invoice) match {
      case Success(tx) =>
        sendResponse(connection, requestEventId, senderPubkey, NWCProtocol.formatLookupInvoiceSuccess(tx))
      case Failure(e) =>
        val errorCode = if (e.getMessage.contains("not found")) {
          NWCProtocol.ErrorCode.NOT_FOUND
        } else {
          NWCProtocol.ErrorCode.INTERNAL
        }
        sendResponse(connection, requestEventId, senderPubkey,
          NWCProtocol.formatLookupInvoiceError(errorCode, e.getMessage))
    }
  }

  /**
   * Send a response via the relay.
   */
  private def sendResponse(connection: NWCConnection, requestEventId: String, recipientPubkey: String, content: String): Unit = {
    activeRelays.get(connection.id).foreach { relay =>
      relay.publishResponse(recipientPubkey, requestEventId, content)
    }
  }
}


/**
 * Listener for NWC manager events.
 */
trait NWCManagerListener {
  def onConnectionCreated(connection: NWCConnection): Unit = {}
  def onConnectionDeleted(id: String): Unit = {}
  def onConnectionStatusChanged(id: String, connected: Boolean): Unit = {}
  def onConnectionError(id: String, message: String): Unit = {}
}


/**
 * NWC Connection type.
 * - Send: Full permissions (pay_invoice, make_invoice, get_balance, etc.)
 * - Receive: Limited permissions (make_invoice, get_balance - NO pay_invoice)
 */
object NWCConnectionType {
  val SEND = "send"       // Full permissions including spending
  val RECEIVE = "receive" // Receive-only, no spending
}

/**
 * NWC Connection data class.
 */
case class NWCConnection(
  id: String,
  name: String,
  walletPrivkey: ByteVector32,
  walletPubkey: String,
  secret: String,
  relay: String,
  createdAt: Long,
  connectionUrl: String,
  connectionType: String = NWCConnectionType.SEND // Default to full permissions
)
