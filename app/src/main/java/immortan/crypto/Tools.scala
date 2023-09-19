package immortan.crypto

import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.nio.charset.StandardCharsets
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.Psbt.KeyPathWithMaster
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.GenerateTxResponse
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.crypto.ChaCha20Poly1305
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.transactions.CommitmentSpec
import immortan.Ticker
import immortan.crypto.Noise.KeyPair
import immortan.crypto.Tools.runAnd
import immortan.utils.{FeeRatesInfo, ThrottledWork}
import okhttp3.{OkHttpClient, Request, ResponseBody}
import rx.lang.scala.Observable
import scodec.bits.ByteVector

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.implicitConversions


object Tools {
  type Bytes = Array[Byte]
  type Fiat2Btc = Map[String, Double]
  final val SEPARATOR = " "
  final val PERCENT = "%"

  private[this] val okHttpClient =
    (new OkHttpClient.Builder)
      .connectTimeout(15, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build

  def get(url: String): ResponseBody = {
    val request = (new Request.Builder).url(url).get
    okHttpClient.newCall(request.build).execute.body
  }

  def trimmed(inputText: String): String = inputText.trim.take(144)

  def none: PartialFunction[Any, Unit] = { case _ => }

  def runAnd[T](result: T)(action: Any): T = result

  implicit class Any2Some[T](underlying: T) {
    def asLeft: Left[T, Nothing] = Left(underlying)
    def asRight: Right[Nothing, T] = Right(underlying)
    def asSome: Option[T] = Some(underlying)
    def asList: List[T] = List(underlying)
  }

  implicit class IterableOfTuple2[T, V](underlying: Iterable[ (T, V) ] = Nil) {
    def secondItems: Iterable[V] = underlying.map { case (_, secondItem) => secondItem }
    def firstItems: Iterable[T] = underlying.map { case (firstItem, _) => firstItem }
  }

  implicit class ThrowableOps(error: Throwable) {
    def stackTraceAsString: String = {
      val stackTraceWriter = new java.io.StringWriter
      error printStackTrace new java.io.PrintWriter(stackTraceWriter)
      stackTraceWriter.toString
    }
  }

  def ratio(bigger: MilliSatoshi, lesser: MilliSatoshi): Double =
    scala.util.Try(bigger.toLong)
      .map(lesser.toLong * 10000D / _)
      .map(_.toLong / 100D)
      .getOrElse(0D)

  def mapKeys[K, V, K1](items: mutable.Map[K, V], mapper: K => K1, defVal: V): mutable.Map[K1, V] =
    items.map { case (key, value) => mapper(key) -> value } withDefaultValue defVal

  def memoize[In <: Object, Out <: Object](fun: In => Out): LoadingCache[In, Out] = {
    val loader = new CacheLoader[In, Out] { override def load(key: In): Out = fun apply key }
    CacheBuilder.newBuilder.expireAfterAccess(7, TimeUnit.DAYS).maximumSize(2000).build[In, Out](loader)
  }

  def hostedNodesCombined(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) pubkey1 ++ pubkey2 else pubkey2 ++ pubkey1
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector, ticker: Ticker): ByteVector32 = {
    val nodesCombined = hostedNodesCombined(pubkey1, pubkey2)
    val tickerBytes = ticker.tag.getBytes(StandardCharsets.UTF_8)
    Crypto.sha256(nodesCombined ++ ByteVector(tickerBytes))
  }

  def hostedShortChanId(pubkey1: ByteVector, pubkey2: ByteVector, ticker: Ticker): Long = {
    val tickerBytes = ticker.tag.getBytes(StandardCharsets.UTF_8)
    val hash = Crypto.sha256(hostedNodesCombined(pubkey1, pubkey2) ++ ByteVector(tickerBytes))
    val stream = new ByteArrayInputStream(hash.toArray)
    def getChunk: Long = Protocol.uint64(stream, ByteOrder.BIG_ENDIAN)
    List.fill(8)(getChunk).sum
  }

  def mkFakeLocalEdge(from: PublicKey, toPeer: PublicKey): GraphEdge = {
    // Augments a graph with local edge corresponding to our local channel
    // Parameters do not matter except that it must point to real peer

    val zeroCltvDelta = CltvExpiryDelta(0)
    val randomShortChannelId = secureRandom.nextLong
    val fakeDesc = ChannelDesc(randomShortChannelId, from, to = toPeer)
    val fakeHop = ExtraHop(from, randomShortChannelId, MilliSatoshi(0L), 0L, zeroCltvDelta)
    GraphEdge(updExt = RouteCalculation.toFakeUpdate(fakeHop), desc = fakeDesc)
  }

  // Defines whether updated feerate exceeds a given threshold
  def newFeerate(info1: FeeRatesInfo, spec: CommitmentSpec, threshold: Double): Option[FeeratePerKw] = {
    val newFeerate = info1.onChainFeeConf.feeEstimator.getFeeratePerKw(info1.onChainFeeConf.feeTargets.commitmentBlockTarget)
    if (spec.feeratePerKw.max(newFeerate).toLong.toDouble / spec.feeratePerKw.min(newFeerate).toLong > threshold) Some(newFeerate) else None
  }

  def randomKeyPair: KeyPair = {
    val pk: PrivateKey = randomKey
    KeyPair(pk.publicKey.value, pk.value)
  }

  def chaChaEncrypt(key: ByteVector32, nonce: ByteVector, data: ByteVector): ByteVector = {
    val (ciphertext, mac) = ChaCha20Poly1305.encrypt(key, nonce, data, ByteVector.empty)
    mac ++ nonce ++ ciphertext // 16b + 12b + variable size
  }

  def chaChaDecrypt(key: ByteVector32, data: ByteVector): scala.util.Try[ByteVector] = scala.util.Try {
    ChaCha20Poly1305.decrypt(key, nonce = data drop 16 take 12, ciphertext = data drop 28, ByteVector.empty, mac = data take 16)
  }

  def prepareBip84Psbt(response: GenerateTxResponse, masterFingerprint: Long): Psbt = {
    // We ONLY support BIP84 watching wallets so all inputs have witnesses
    val psbt1 = Psbt(response.tx)

    // Provide info about inputs
    val psbt2 = response.tx.txIn.foldLeft(psbt1) { case (psbt, txIn) =>
      val parentTransaction = response.data.transactions(txIn.outPoint.txid)
      val utxoPubKey = response.data.publicScriptMap(parentTransaction.txOut(txIn.outPoint.index.toInt).publicKeyScript)
      val derivationPath = Map(KeyPathWithMaster(masterFingerprint, utxoPubKey.path) -> utxoPubKey.publicKey).map(_.swap)
      psbt.updateWitnessInputTx(parentTransaction, txIn.outPoint.index.toInt, derivationPaths = derivationPath).get
    }

    // Provide info about our change output
    response.tx.txOut.zipWithIndex.foldLeft(psbt2) { case (psbt, txOut ~ index) =>
      response.data.publicScriptChangeMap.get(txOut.publicKeyScript) map { changeKey =>
        val changeKeyPathWithMaster = KeyPathWithMaster(masterFingerprint, changeKey.path)
        val derivationPath = Map(changeKeyPathWithMaster -> changeKey.publicKey).map(_.swap)
        psbt.updateWitnessOutput(index, derivationPaths = derivationPath).get
      } getOrElse psbt
    }
  }

  def extractBip84Tx(psbt: Psbt): scala.util.Try[Transaction] = {
    // We ONLY support BIP84 watching wallets so all inputs have witnesses
    psbt.extract orElse psbt.inputs.zipWithIndex.foldLeft(psbt) { case (psbt1, input ~ index) =>
      val witness = (Script.witnessPay2wpkh _).tupled(input.partialSigs.head)
      psbt1.finalizeWitnessInput(index, witness).get
    }.extract
  }

  def obtainPsbt(ur: UR): scala.util.Try[Psbt] = scala.util.Try {
    val rawPsbt = ur.decodeFromRegistry.asInstanceOf[CryptoPSBT]
    ByteVector.view(rawPsbt.getPsbt)
  } flatMap Psbt.read

  object ~ {
    // Useful for matching nested Tuple2 with less noise
    def unapply[A, B](t2: (A, B) /* Got a tuple */) = Some(t2)
  }
}

trait CanBeShutDown {
  def becomeShutDown: Unit
}

trait CanBeRepliedTo {
  def process(reply: Any): Unit
}

abstract class StateMachine[T] { me =>
  def become(freshData: T, freshState: Int): StateMachine[T] = {
    // Update state, data and return itself for easy chaining operations
    state = freshState
    data = freshData
    me
  }

  def doProcess(change: Any): Unit
  var TOTAL_INTERVAL_SECONDS: Long = 60
  var secondsLeft: Long = _
  var state: Int = -1
  var data: T = _

  lazy val delayedCMDWorker: ThrottledWork[String, Long] =
    new ThrottledWork[String, Long] {
      def work(cmd: String): Observable[Long] =
        Observable.interval(1.second).doOnSubscribe {
          secondsLeft = TOTAL_INTERVAL_SECONDS
        }

      def process(cmd: String, tickUpdateInterval: Long): Unit = {
        secondsLeft = TOTAL_INTERVAL_SECONDS - (tickUpdateInterval + 1)
        if (secondsLeft <= 0L) runAnd(unsubscribeCurrentWork)(me doProcess cmd)
      }
    }
}
