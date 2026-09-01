package finance.valet.sheets

import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import android.os.Bundle
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{ImageButton, TextView}
import androidx.appcompat.view.ContextThemeWrapper
import finance.valet.{BaseActivity, WalletApp}
import finance.valet.BaseActivity
import finance.valet.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.{BarcodeFormat, DecodeHintType}
import com.journeyapps.barcodescanner.{BarcodeCallback, BarcodeResult, BarcodeView, CameraPreview, DefaultDecoderFactory}
import com.journeyapps.barcodescanner.camera.CameraSettings
import com.sparrowwallet.hummingbird.registry.{CryptoAccount, CryptoHDKey}
import com.sparrowwallet.hummingbird.{ResultType, UR, URDecoder}
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.{ByteVector32, Protocol}
import immortan.crypto.Tools._
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.InputParser
import scodec.bits.ByteVector
import spray.json._

import scala.collection.JavaConverters._
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}


trait HasBarcodeReader extends BarcodeCallback {
  var lastAttempt: Long = 0L
  var barcodeReader: BarcodeView = _
  var instruction: TextView = _
}

trait HasUrDecoder extends HasBarcodeReader {
  val decoder: URDecoder = new URDecoder
  def onError(error: String)
  def onUR(ur: UR): Unit

  def handleUR(part: String): Unit = {
    val partAdded = Try(decoder receivePart part).getOrElse(false)
    if (!partAdded && System.currentTimeMillis - lastAttempt > 2000) {
      WalletApp.app.quickToast(R.string.error_nothing_useful)
      lastAttempt = System.currentTimeMillis
    }

    val pct = decoder.getEstimatedPercentComplete
    val pctAsFractionOf100 = (pct * 100).floor.toLong
    if (pct > 0D) instruction.setText(s"$pctAsFractionOf100%")

    for {
      result <- Option(decoder.getResult)
      isOK = result.resultType == ResultType.SUCCESS
    } if (isOK) onUR(result.ur) else onError(result.error)
  }
}

abstract class ScannerBottomSheet(host: BaseActivity) extends BottomSheetDialogFragment with HasBarcodeReader {
  private var barcodeDecoded = false
  private val noQrHint = new Runnable {
    override def run: Unit = if (!barcodeDecoded && isAdded) WalletApp.app.quickToast(R.string.error_scan_no_qr)
  }

  def resumeBarcodeReader: Unit = Option(barcodeReader).foreach { reader =>
    barcodeDecoded = false
    reader.removeCallbacks(noQrHint)
    reader.decodeContinuous(this)
    reader.resume
    reader.postDelayed(noQrHint, 8000L)
  }

  def pauseBarcodeReader: Unit = Option(barcodeReader).foreach { reader =>
    reader.removeCallbacks(noQrHint)
    reader.setTorch(false)
    reader.pause
    Option(flashlight).foreach { button =>
      button.setImageResource(R.drawable.flashlight_off)
      button.setTag(R.drawable.flashlight_off)
    }
  }

  def markBarcodeDecoded: Unit = {
    barcodeDecoded = true
    Option(barcodeReader).foreach(_.removeCallbacks(noQrHint))
  }

  private def stopBarcodeReader: Unit = Option(barcodeReader).foreach { reader =>
    reader.removeCallbacks(noQrHint)
    reader.stopDecoding
  }

  override def onDestroy: Unit = {
    stopBarcodeReader
    super.onDestroy
  }

  override def onDestroyView: Unit = {
    pauseBarcodeReader
    stopBarcodeReader
    barcodeReader = null
    flashlight = null
    instruction = null
    super.onDestroyView
  }

  override def onResume: Unit = {
    super.onResume
    resumeBarcodeReader
  }

  override def onStop: Unit = {
    pauseBarcodeReader
    super.onStop
  }

  var flashlight: ImageButton = _

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, state: Bundle): View = {
    val contextThemeWrapper = new ContextThemeWrapper(host, R.style.AppTheme)
    val inflatorExt = inflater.cloneInContext(contextThemeWrapper)
    inflatorExt.inflate(R.layout.sheet_scanner, container, false)
  }

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    instruction = view.findViewById(R.id.instruction).asInstanceOf[TextView]
    barcodeReader = view.findViewById(R.id.reader).asInstanceOf[BarcodeView]
    flashlight = view.findViewById(R.id.flashlight).asInstanceOf[ImageButton]
    flashlight.setTag(R.drawable.flashlight_off)
    flashlight setOnClickListener host.onButtonTap(toggleTorch)

    val decodeHints = new java.util.HashMap[DecodeHintType, AnyRef]
    decodeHints.put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
    barcodeReader.setDecoderFactory(new DefaultDecoderFactory(
      java.util.Collections.singletonList[BarcodeFormat](BarcodeFormat.QR_CODE), decodeHints, null, 0))
    barcodeReader.setMarginFraction(0.05d)

    val cameraSettings: CameraSettings = barcodeReader.getCameraSettings
    cameraSettings.setAutoFocusEnabled(true)
    cameraSettings.setContinuousFocusEnabled(true)
    cameraSettings.setMeteringEnabled(true)
    cameraSettings.setBarcodeSceneModeEnabled(true)
    barcodeReader.setCameraSettings(cameraSettings)

    barcodeReader.addStateListener(new CameraPreview.StateListener {
      override def previewSized(): Unit = none
      override def previewStarted(): Unit = none
      override def previewStopped(): Unit = none
      override def cameraError(error: Exception): Unit = onCameraError(error)
      override def cameraClosed(): Unit = none
    })

    val readerFrame = view.findViewById(R.id.readerFrame).asInstanceOf[View]
    readerFrame.post(new Runnable { override def run: Unit = resizeReaderFrame(readerFrame) })
  }

  private def resizeReaderFrame(readerFrame: View): Unit = {
    val metrics = host.getResources.getDisplayMetrics
    val density = metrics.density
    val width = readerFrame.getWidth
    if (width > 0) {
      val sideMargin = (24D * density).toInt
      val availableWidth = Math.max(1, width - sideMargin)
      val availableHeight = Math.max(1, (metrics.heightPixels * 0.7D).toInt)
      val layoutParams = readerFrame.getLayoutParams
      layoutParams.height = Math.min(availableWidth, availableHeight)
      readerFrame.setLayoutParams(layoutParams)
    }
  }

  def onCameraError(error: Exception): Unit = {
    Option(barcodeReader).foreach(_.removeCallbacks(noQrHint))
    WalletApp.app.quickToast(R.string.error_camera_unavailable)
    try {
      if (isAdded) dismiss
    } catch none
  }

  def toggleTorch: Unit = {
    if (flashlight.getTag != R.drawable.flashlight_on) {
      flashlight.setImageResource(R.drawable.flashlight_on)
      flashlight.setTag(R.drawable.flashlight_on)
      barcodeReader.setTorch(true)
    } else {
      flashlight.setImageResource(R.drawable.flashlight_off)
      flashlight.setTag(R.drawable.flashlight_off)
      barcodeReader.setTorch(false)
    }
  }
}

class OnceBottomSheet(host: BaseActivity, instructionOpt: Option[String], onScan: Runnable) extends ScannerBottomSheet(host) {
  def failedScan(error: Throwable): Unit = WalletApp.app.quickToast(error)
  def successfulScan(result: Any): Unit = runAnd(dismiss)(onScan.run)

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    super.onViewCreated(view, savedState)

    instructionOpt foreach { instructionText =>
      host.setVis(isVisible = true, instruction)
      instruction.setText(instructionText)
    }
  }

  override def barcodeResult(scanningResult: BarcodeResult): Unit = {
    val now = System.currentTimeMillis
    for {
      text <- Option(scanningResult.getText).map(_.trim).filter(_.nonEmpty) if now - lastAttempt > 2000
    } {
      markBarcodeDecoded
      lastAttempt = now
      host.runInFutureProcessOnUI(InputParser.recordValue(text), failedScan)(successfulScan)
    }
  }
}

trait PairingData {
  val bip84FullPathPrefix = KeyPath(hardened(84L) :: hardened(0L) :: hardened(0L) :: Nil)
  val bip84PathPrefix = KeyPath(hardened(84L) :: hardened(0L) :: Nil)
  val masterFingerprint: Option[Long] = None
  val bip84XPub: ExtendedPublicKey
}

case class ZPubPairingData(zPubText: String) extends PairingData {
  val (_, bip84XPub) = ExtendedPublicKey.decode(zPubText, bip84PathPrefix)
}

case class HWBytesPairingData(urBytes: Bytes) extends PairingData {
  val charBuffer: JsObject = StandardCharsets.UTF_8.newDecoder.decode(ByteBuffer wrap urBytes).toString.parseJson.asJsObject
  val (_, bip84XPub) = ExtendedPublicKey.decode(json2String(charBuffer.fields("bip84").asJsObject fields "xpub"), bip84PathPrefix)
  val (_, masterXPub) = ExtendedPublicKey.decode(json2String(charBuffer fields "xpub"), KeyPath.Root)
  override val masterFingerprint: Option[Long] = fingerprint(masterXPub).asSome
}

case class HWAccountPairingData(urAccount: CryptoAccount) extends PairingData {
  private implicit def bytesToByteVector(bytes: Bytes): ByteVector = ByteVector.view(bytes)
  private implicit def arrayToLongFingerprint(fingerPrint: Bytes): Long = Protocol.uint32(urAccount.getMasterFingerprint, ByteOrder.BIG_ENDIAN)
  private def isBip84AccountKey(key: CryptoHDKey): Boolean = null != key && null != key.getOrigin && !key.isPrivateKey && KeyPath(key.getOrigin.getPath) == bip84FullPathPrefix
  override val masterFingerprint: Option[Long] = Some(urAccount.getMasterFingerprint)

  val bip84XPub: ExtendedPublicKey = urAccount.getOutputDescriptors.asScala.map(_.getHdKey).filter(isBip84AccountKey).map { hdKey =>
    ExtendedPublicKey(hdKey.getKey, ByteVector32(hdKey.getChainCode), hdKey.getOrigin.getDepth, KeyPath(hdKey.getOrigin.getPath), hdKey.getParentFingerprint)
  }.head
}

class URBottomSheet(host: BaseActivity, onPairData: PairingData => Unit) extends ScannerBottomSheet(host) with HasUrDecoder {
  override def barcodeResult(res: BarcodeResult): Unit = {
    markBarcodeDecoded
    if (res.getText.toLowerCase startsWith "zpub") onZPub(res.getText) else handleUR(res.getText)
  }
  override def onError(error: String): Unit = host.onFail(error)

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    super.onViewCreated(view, savedState)

    val tip = host.getString(R.string.settings_hw_zpub_tip)
    host.setVis(isVisible = true, instruction)
    instruction.setText(tip)
  }

  def onZPub(zPubText: String): Unit = {
    scala.util.Try(ZPubPairingData apply zPubText) match {
      case Failure(why) => host.onFail(why)
      case Success(data) => onPairData(data)
    }

    dismiss
  }

  override def onUR(ur: UR): Unit = {
    scala.util.Try(ur.decodeFromRegistry) map {
      case urBytes: Bytes => HWBytesPairingData(urBytes)
      case urAccount: CryptoAccount => HWAccountPairingData(urAccount)
      case _ => throw new RuntimeException(host getString R.string.error_nothing_useful)
    } match {
      case Failure(why) => host.onFail(why)
      case Success(data) => onPairData(data)
    }

    dismiss
  }
}
