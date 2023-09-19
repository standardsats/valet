package immortan.utils

import com.google.common.base.CharMatcher
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Bech32, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.wire.NodeAddress
import immortan.crypto.Tools
import immortan.crypto.Tools.Any2Some
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.uri.Uri
import immortan.{LNParams, PaymentAction, RemoteNodeInfo, Ticker}
import rx.lang.scala.Observable
import scodec.bits.ByteVector
import spray.json._

import scala.util.Try


object LNUrl {
  def fromIdentifier(identifier: String): LNUrl = {
    val (user, domain) = identifier.splitAt(identifier indexOf '@')
    val isOnionDomain: Boolean = domain.endsWith(NodeAddress.onionSuffix)
    if (isOnionDomain) LNUrl(s"http://$domain/.well-known/lnurlp/$user")
    else LNUrl(s"https://$domain/.well-known/lnurlp/$user")
  }

  def fromBech32(bech32url: String): LNUrl = {
    val Tuple3(_, dataBody, _) = Bech32.decode(bech32url)
    val request = new String(Bech32.five2eight(dataBody), "UTF-8")
    LNUrl(request)
  }

  def checkHost(host: String): Uri = Uri.parse(host) match { case uri =>
    val isOnion = host.startsWith("http://") && uri.getHost.endsWith(NodeAddress.onionSuffix)
    val isSSLPlain = host.startsWith("https://") && !uri.getHost.endsWith(NodeAddress.onionSuffix)
    require(isSSLPlain || isOnion, "URI is neither Plain/HTTPS nor Onion/HTTP request")
    uri
  }

  def guardResponse(raw: String): String = {
    val parseAttempt = Try(raw.parseJson.asJsObject.fields)
    val hasErrorDescription = parseAttempt.map(_ apply "reason").map(json2String)
    val hasError = parseAttempt.map(_ apply "status").map(json2String).filter(_.toUpperCase == "ERROR")
    if (hasErrorDescription.isSuccess) throw new Exception(s"Error from vendor: ${hasErrorDescription.get}")
    else if (hasError.isSuccess) throw new Exception(s"Error from vendor: no description provided")
    else if (parseAttempt.isFailure) throw new Exception(s"Invalid json from vendor: $raw")
    raw
  }

  def level2DataResponse(bld: Uri.Builder): Observable[String] = Rx.ioQueue.map { _ =>
    guardResponse(LNParams.connectionProvider.get(bld.build.toString).string)
  }
}

case class LNUrl(request: String) {
  val uri: Uri = LNUrl.checkHost(request)
  val warnUri: String = uri.getHost.map { char =>
    if (CharMatcher.ascii matches char) char.toString
    else s"<b>[$char]</b>"
  }.mkString

  lazy val k1: Try[String] = Try(uri getQueryParameter "k1")
  lazy val isAuth: Boolean = Try(uri.getQueryParameter("tag").toLowerCase == "login").getOrElse(false)
  lazy val authAction: String = Try(uri.getQueryParameter("action").toLowerCase).getOrElse("login")

  lazy val fastWithdrawAttempt: Try[WithdrawRequest] = Try {
    require(uri.getQueryParameter("tag") equals "withdrawRequest")
    WithdrawRequest(uri.getQueryParameter("callback"), uri.getQueryParameter("k1"),
      uri.getQueryParameter("maxWithdrawable").toLong, uri.getQueryParameter("defaultDescription"),
      uri.getQueryParameter("minWithdrawable").toLong.asSome)
  }

  def level1DataResponse: Observable[LNUrlData] = Rx.ioQueue.map { _ =>
    to[LNUrlData](LNParams.connectionProvider.get(uri.toString).string)
  }
}

sealed trait LNUrlData

sealed trait CallbackLNUrlData extends LNUrlData {

  val callbackUri: Uri = LNUrl.checkHost(callback)

  def callback: String
}

// LNURL-CHANNEL

sealed trait HasRemoteInfo {
  val remoteInfo: RemoteNodeInfo
  def cancel: Unit = Tools.none
}

case class HasRemoteInfoWrap(remoteInfo: RemoteNodeInfo) extends HasRemoteInfo

case class NormalChannelRequest(uri: String, callback: String, k1: String) extends CallbackLNUrlData with HasRemoteInfo {

  def requestChannel: Observable[String] = LNUrl.level2DataResponse {
    callbackUri.buildUpon.appendQueryParameter("k1", k1).appendQueryParameter("private", "1")
      .appendQueryParameter("remoteid", remoteInfo.nodeSpecificPubKey.toString)
  }

  override def cancel: Unit = LNUrl.level2DataResponse {
    callbackUri.buildUpon.appendQueryParameter("k1", k1).appendQueryParameter("cancel", "1")
      .appendQueryParameter("remoteid", remoteInfo.nodeSpecificPubKey.toString)
  }.foreach(Tools.none, Tools.none)

  val InputParser.nodeLink(nodeKey, hostAddress, portNumber) = uri

  val pubKey: PublicKey = PublicKey.fromBin(ByteVector fromValidHex nodeKey)

  val address: NodeAddress = NodeAddress.fromParts(hostAddress, portNumber.toInt)

  val remoteInfo: RemoteNodeInfo = RemoteNodeInfo(pubKey, address, hostAddress)
}

case class HostedChannelRequest(uri: String, alias: Option[String], k1: String, ticker: Ticker) extends LNUrlData with HasRemoteInfo {

  val secret: ByteVector32 = ByteVector32.fromValidHex(k1)

  val InputParser.nodeLink(nodeKey, hostAddress, portNumber) = uri

  val pubKey: PublicKey = PublicKey(ByteVector fromValidHex nodeKey)

  val address: NodeAddress = NodeAddress.fromParts(hostAddress, portNumber.toInt)

  val remoteInfo: RemoteNodeInfo = RemoteNodeInfo(pubKey, address, hostAddress)
}

// LNURL-WITHDRAW

case class WithdrawRequest(callback: String, k1: String, maxWithdrawable: Long, defaultDescription: String,
                           minWithdrawable: Option[Long], balance: Option[Long] = None, balanceCheck: Option[String] = None,
                           payLink: Option[String] = None) extends CallbackLNUrlData { me =>

  def requestWithdraw(ext: PaymentRequestExt): Observable[String] = LNUrl.level2DataResponse {
    callbackUri.buildUpon.appendQueryParameter("pr", ext.raw).appendQueryParameter("k1", k1)
  }

  val minCanReceive: MilliSatoshi = minWithdrawable.map(_.msat).getOrElse(LNParams.minPayment).max(LNParams.minPayment)

  val nextWithdrawRequestOpt: Option[LNUrl] = balanceCheck.map(LNUrl.apply)

  val relatedPayLinkOpt: Option[LNUrl] = payLink.map(LNUrl.apply)

  val descriptionOpt: Option[String] = Some(defaultDescription).map(Tools.trimmed).filter(_.nonEmpty)

  require(minCanReceive <= maxWithdrawable.msat, s"$maxWithdrawable is less than min $minCanReceive")
}

// LNURL-PAY

case class LNUrlAuthSpec(host: String, k1: ByteVector32) {
  val linkingPrivKey: Crypto.PrivateKey = LNParams.secret.keys.makeLinkingKey(host)
  val linkingPubKey: String = linkingPrivKey.publicKey.toString

  def signature: ByteVector64 = Crypto.sign(k1, linkingPrivKey)
  def derSignatureHex: String = Crypto.compact2der(signature).toHex
}

object PayRequest {
  type TagAndContent = Vector[JsValue]
}

case class ExpectedAuth(k1: ByteVector32, isMandatory: Boolean) {
  def getRecord(host: String): List[String] = LNUrlAuthSpec(host, k1) match { case spec =>
    List("application/lnurl-auth", spec.linkingPubKey.toString, spec.derSignatureHex)
  }
}

case class ExpectedIds(wantsAuth: Option[ExpectedAuth], wantsRandomKey: Boolean)

case class PayRequestMeta(records: PayRequest.TagAndContent) {

  val texts: Vector[String] = records.collect { case JsArray(JsString("text/plain") +: JsString(txt) +: _) => txt }

  val emails: Vector[String] = records.collect { case JsArray(JsString("text/email") +: JsString(email) +: _) => email }

  val identities: Vector[String] = records.collect { case JsArray(JsString("text/identifier") +: JsString(identifier) +: _) => identifier }

  val textPlain: String = Tools.trimmed(texts.head)

  val imageBase64s: Seq[String] = for {
    JsArray(JsString("image/png;base64" | "image/jpeg;base64") +: JsString(image) +: _) <- records
    _ = require(image.length <= 136536, s"Image is too big, length=${image.length}, max=136536")
  } yield image

  def queryText(domain: String): String = {
    val ids = emails.headOption.orElse(identities.headOption).getOrElse(new String)
    val tokenizedDomain = domain.replace('.', ' ')
    s"$ids $textPlain $tokenizedDomain"
  }
}

case class PayRequest(callback: String, maxSendable: Long, minSendable: Long, metadata: String, commentAllowed: Option[Int] = None) extends CallbackLNUrlData {

  def metaDataHash: ByteVector32 = Crypto.sha256(ByteVector view metadata.getBytes)

  val meta: PayRequestMeta = PayRequestMeta(metadata.parseJson.asInstanceOf[JsArray].elements)

  private[this] val identifiers = meta.emails ++ meta.identities
  require(identifiers.forall(id => InputParser.identifier.findFirstMatchIn(id).isDefined), "text/email or text/identity format is wrong")
  require(meta.imageBase64s.size <= 1, "There can be at most one image/png;base64 or image/jpeg;base64 entry in metadata")
  require(identifiers.size <= 1, "There can be at most one text/email or text/identity entry in metadata")
  require(meta.texts.size == 1, "There must be exactly one text/plain entry in metadata")
  require(minSendable <= maxSendable, s"max=$maxSendable while min=$minSendable")
}

case class PayRequestFinal(successAction: Option[PaymentAction], disposable: Option[Boolean], pr: String) extends LNUrlData {

  lazy val prExt: PaymentRequestExt = PaymentRequestExt.fromUri(pr)

  val isThrowAway: Boolean = disposable.getOrElse(true)
}