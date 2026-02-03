package finance.valet.nwc

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.Tools._
import okhttp3._
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.util.Try


/**
 * Nostr relay WebSocket client for NWC.
 *
 * Handles:
 * - WebSocket connection to relay
 * - Subscription to NWC events (kind 23194)
 * - Publishing response events (kind 23195)
 * - Automatic reconnection
 */
class NostrRelay(
  relayUrl: String,
  walletPrivateKey: PrivateKey,
  walletPublicKey: PublicKey,
  listener: NostrRelayListener
) {

  private var webSocket: WebSocket = _
  private var isConnected: Boolean = false
  private var shouldReconnect: Boolean = true
  private val subscriptionId: String = java.util.UUID.randomUUID().toString.take(8)

  private val client: OkHttpClient = new OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

  private val webSocketListener = new WebSocketListener {
    override def onOpen(ws: WebSocket, response: Response): Unit = {
      isConnected = true
      listener.onConnected()
      subscribeToNWCRequests()
    }

    override def onMessage(ws: WebSocket, text: String): Unit = {
      handleMessage(text)
    }

    override def onClosing(ws: WebSocket, code: Int, reason: String): Unit = {
      ws.close(1000, null)
    }

    override def onClosed(ws: WebSocket, code: Int, reason: String): Unit = {
      isConnected = false
      listener.onDisconnected(reason)
      if (shouldReconnect) {
        scheduleReconnect()
      }
    }

    override def onFailure(ws: WebSocket, t: Throwable, response: Response): Unit = {
      isConnected = false
      listener.onError(t.getMessage)
      if (shouldReconnect) {
        scheduleReconnect()
      }
    }
  }

  /**
   * Connect to the relay.
   */
  def connect(): Unit = {
    shouldReconnect = true
    val request = new Request.Builder()
      .url(relayUrl)
      .build()
    webSocket = client.newWebSocket(request, webSocketListener)
  }

  /**
   * Disconnect from the relay.
   */
  def disconnect(): Unit = {
    shouldReconnect = false
    if (webSocket != null) {
      webSocket.close(1000, "Client closing")
    }
  }

  /**
   * Subscribe to NWC request events (kind 23194) addressed to our pubkey.
   */
  private def subscribeToNWCRequests(): Unit = {
    val walletPubkeyHex = NWCCrypto.pubkeyToNostrHex(walletPublicKey)

    // REQ message: ["REQ", subscription_id, filter]
    // Filter for kind 23194 (NWC request) tagged to our pubkey
    val filter = JsObject(
      "kinds" -> JsArray(JsNumber(23194)),
      "#p" -> JsArray(JsString(walletPubkeyHex))
    )

    val reqMessage = JsArray(
      JsString("REQ"),
      JsString(subscriptionId),
      filter
    ).compactPrint

    webSocket.send(reqMessage)
  }

  /**
   * Handle incoming message from relay.
   */
  private def handleMessage(text: String): Unit = {
    Try {
      val json = text.parseJson.asInstanceOf[JsArray]
      val elements = json.elements

      elements.head match {
        case JsString("EVENT") =>
          // ["EVENT", subscription_id, event]
          if (elements.size >= 3) {
            val event = elements(2).asJsObject
            handleEvent(event)
          }

        case JsString("OK") =>
          // ["OK", event_id, success, message]
          if (elements.size >= 4) {
            val eventId = elements(1).convertTo[String]
            val success = elements(2).convertTo[Boolean]
            val message = elements(3).convertTo[String]
            listener.onPublishResult(eventId, success, message)
          }

        case JsString("NOTICE") =>
          // ["NOTICE", message]
          if (elements.size >= 2) {
            val message = elements(1).convertTo[String]
            listener.onNotice(message)
          }

        case JsString("EOSE") =>
          // End of stored events - can be ignored
          ()

        case _ =>
          // Unknown message type
          ()
      }
    }.recover {
      case e: Exception =>
        listener.onError(s"Failed to parse message: ${e.getMessage}")
    }
  }

  /**
   * Handle a Nostr event.
   */
  private def handleEvent(event: JsObject): Unit = {
    Try {
      val fields = event.fields
      val id = fields("id").convertTo[String]
      val pubkey = fields("pubkey").convertTo[String]
      val createdAt = fields("created_at").convertTo[Long]
      val kind = fields("kind").convertTo[Int]
      val tags = fields("tags").asInstanceOf[JsArray].elements.map { tag =>
        tag.asInstanceOf[JsArray].elements.map(_.convertTo[String]).toList
      }.toList
      val content = fields("content").convertTo[String]
      val sig = fields("sig").convertTo[String]

      if (kind == 23194) {
        // NWC Request event - decrypt and process
        val senderPubkey = NWCCrypto.nostrHexToPubkey(pubkey)
        NWCCrypto.decrypt(content, walletPrivateKey, senderPubkey) match {
          case scala.util.Success(decryptedContent) =>
            listener.onNWCRequest(
              eventId = id,
              senderPubkey = pubkey,
              content = decryptedContent,
              createdAt = createdAt
            )
          case scala.util.Failure(e) =>
            listener.onError(s"Failed to decrypt NWC request: ${e.getMessage}")
        }
      }
    }.recover {
      case e: Exception =>
        listener.onError(s"Failed to handle event: ${e.getMessage}")
    }
  }

  /**
   * Publish a NWC response event (kind 23195).
   *
   * @param recipientPubkey The pubkey to send the response to (hex format)
   * @param requestEventId The event ID of the request we're responding to
   * @param content The response content (will be encrypted)
   * @return The event ID if successful
   */
  def publishResponse(recipientPubkey: String, requestEventId: String, content: String): Option[String] = {
    if (!isConnected) {
      listener.onError("Cannot publish: not connected to relay")
      return None
    }

    Try {
      val recipientKey = NWCCrypto.nostrHexToPubkey(recipientPubkey)
      val encryptedContent = NWCCrypto.encrypt(content, walletPrivateKey, recipientKey)
      val walletPubkeyHex = NWCCrypto.pubkeyToNostrHex(walletPublicKey)
      val createdAt = System.currentTimeMillis() / 1000

      // Tags: [["p", recipient_pubkey], ["e", request_event_id]]
      val tags = JsArray(
        JsArray(JsString("p"), JsString(recipientPubkey)),
        JsArray(JsString("e"), JsString(requestEventId))
      )

      // Serialize for event ID: [0, pubkey, created_at, kind, tags, content]
      val serialized = JsArray(
        JsNumber(0),
        JsString(walletPubkeyHex),
        JsNumber(createdAt),
        JsNumber(23195),
        tags,
        JsString(encryptedContent)
      ).compactPrint

      val eventId = NWCCrypto.computeEventId(serialized)
      val signature = NWCCrypto.signEvent(eventId, walletPrivateKey)

      val event = JsObject(
        "id" -> JsString(eventId.toHex),
        "pubkey" -> JsString(walletPubkeyHex),
        "created_at" -> JsNumber(createdAt),
        "kind" -> JsNumber(23195),
        "tags" -> tags,
        "content" -> JsString(encryptedContent),
        "sig" -> JsString(signature.toHex)
      )

      val eventMessage = JsArray(JsString("EVENT"), event).compactPrint
      webSocket.send(eventMessage)

      Some(eventId.toHex)
    }.recover {
      case e: Exception =>
        listener.onError(s"Failed to publish response: ${e.getMessage}")
        None
    }.getOrElse(None)
  }

  /**
   * Schedule a reconnection attempt.
   */
  private def scheduleReconnect(): Unit = {
    new Thread {
      override def run(): Unit = {
        Thread.sleep(5000) // Wait 5 seconds before reconnecting
        if (shouldReconnect) {
          connect()
        }
      }
    }.start()
  }

  def isActive: Boolean = isConnected
}


/**
 * Listener for Nostr relay events.
 */
trait NostrRelayListener {
  def onConnected(): Unit
  def onDisconnected(reason: String): Unit
  def onError(message: String): Unit
  def onNotice(message: String): Unit
  def onPublishResult(eventId: String, success: Boolean, message: String): Unit

  /**
   * Called when an NWC request is received.
   *
   * @param eventId The Nostr event ID
   * @param senderPubkey The sender's pubkey (hex)
   * @param content The decrypted request content (JSON)
   * @param createdAt Unix timestamp of the event
   */
  def onNWCRequest(eventId: String, senderPubkey: String, content: String, createdAt: Long): Unit
}


/**
 * Companion object with utilities.
 */
object NostrRelay {
  val DEFAULT_RELAY = "wss://relay.damus.io"

  /**
   * Parse a NWC connection URL.
   * Format: nostr+walletconnect://<pubkey>?relay=<relay_url>&secret=<secret>
   */
  def parseConnectionUrl(url: String): Option[NWCConnectionInfo] = {
    Try {
      require(url.startsWith("nostr+walletconnect://"), "Invalid NWC URL scheme")

      val withoutScheme = url.stripPrefix("nostr+walletconnect://")
      val parts = withoutScheme.split("\\?", 2)
      require(parts.length == 2, "Missing query parameters")

      val pubkey = parts(0)
      val params = parts(1).split("&").map { param =>
        val kv = param.split("=", 2)
        kv(0) -> java.net.URLDecoder.decode(kv(1), "UTF-8")
      }.toMap

      NWCConnectionInfo(
        walletPubkey = pubkey,
        relay = params.getOrElse("relay", DEFAULT_RELAY),
        secret = params.get("secret")
      )
    }.toOption
  }

  /**
   * Generate a NWC connection URL.
   */
  def generateConnectionUrl(walletPubkey: String, relay: String, secret: String): String = {
    val encodedRelay = java.net.URLEncoder.encode(relay, "UTF-8")
    s"nostr+walletconnect://$walletPubkey?relay=$encodedRelay&secret=$secret"
  }
}


/**
 * Parsed NWC connection info.
 */
case class NWCConnectionInfo(
  walletPubkey: String,
  relay: String,
  secret: Option[String]
)
