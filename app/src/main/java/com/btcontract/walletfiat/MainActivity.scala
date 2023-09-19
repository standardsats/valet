package com.btcontract.walletfiat

import java.io.{File, FileInputStream}

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import BaseActivity.StringOps
import com.btcontract.walletfiat.R.string._
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.blockchain.EclairWallet._
import fr.acinq.eclair.blockchain.electrum.db.SigningWallet
import immortan.LNParams
import immortan.crypto.Tools.{none, runAnd}
import immortan.utils.InputParser
import org.bitcoinj.wallet._
import org.ndeftools.Message
import org.ndeftools.util.activity.NfcReaderActivity

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}


object ClassNames {
  val chanActivityClass: Class[ChanActivity] = classOf[ChanActivity]
  val statActivityClass: Class[StatActivity] = classOf[StatActivity]
  val qrSplitActivityClass: Class[QRSplitActivity] = classOf[QRSplitActivity]
  val qrChainActivityClass: Class[QRChainActivity] = classOf[QRChainActivity]
  val qrInvoiceActivityClass: Class[QRInvoiceActivity] = classOf[QRInvoiceActivity]
  val coinControlActivityClass: Class[CoinControlActivity] = classOf[CoinControlActivity]

  val settingsActivityClass: Class[SettingsActivity] = classOf[SettingsActivity]
  val remotePeerActivityClass: Class[RemotePeerActivity] = classOf[RemotePeerActivity]
  val mainActivityClass: Class[MainActivity] = classOf[MainActivity]
  val hubActivityClass: Class[HubActivity] = classOf[HubActivity]
}

class MainActivity extends NfcReaderActivity with BaseActivity { me =>
  lazy val legacyWalletFile = new File(getFilesDir, "Bitcoin.wallet")
  override def PREINIT(state: Bundle): Unit = INIT(state)

  override def INIT(state: Bundle): Unit = {
    setContentView(R.layout.frag_linear_layout)
    NotificationManagerCompat.from(me).cancelAll
    initNfc(state)
  }

  // NFC AND SHARE

  // This method is always run when `onResume` event is fired, should be a starting point for all subsequent checks
  def readNdefMessage(msg: Message): Unit = runInFutureProcessOnUI(InputParser recordValue ndefMessageString(msg), proceed)(proceed)

  override def onNoNfcIntentFound: Unit = {
    val processIntent = (getIntent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
    val dataOpt = Seq(getIntent.getDataString, getIntent getStringExtra Intent.EXTRA_TEXT).find(data => null != data)
    if (processIntent) runInFutureProcessOnUI(dataOpt foreach InputParser.recordValue, proceed)(proceed) else proceed(null)
  }

  def onNfcStateEnabled: Unit = none
  def onNfcStateDisabled: Unit = none
  def onNfcFeatureNotFound: Unit = none
  def onNfcStateChange(ok: Boolean): Unit = none
  def readEmptyNdefMessage: Unit = proceed(null)
  def readNonNdefMessage: Unit = proceed(null)

  def proceed(empty: Any): Unit = WalletApp.isAlive match {
    case false => runAnd(WalletApp.makeAlive)(me proceed null)
    case true if LNParams.isOperational => me exitTo ClassNames.hubActivityClass
    case true if legacyWalletFile.exists => (new EnsureLegacy).makeAttempt
    case true => (new EnsureSeed).makeAttempt
  }

  // Tor and auth

  trait Step {
    def makeAttempt: Unit
  }

  class EnsureSeed extends Step {
    def makeAttempt: Unit = WalletApp.extDataBag.tryGetSecret match {
      case Failure(_: android.database.CursorIndexOutOfBoundsException) =>
        // Record is not present at all, this is probaby a fresh wallet
        me exitTo classOf[SetupActivity]

      case Failure(reason) =>
        // Notify user about it
        throw reason

      case Success(secret) =>
        WalletApp.makeOperational(secret)
        me exitTo ClassNames.hubActivityClass
    }
  }

  class EnsureLegacy extends Step {
    private def restoreLegacyWallet = {
      val stream = new FileInputStream(legacyWalletFile)
      val proto: Protos.Wallet = try WalletProtobufSerializer.parseToProto(stream) finally stream.close
      (new WalletProtobufSerializer).readWallet(org.bitcoinj.params.MainNetParams.get, null, proto)
    }

    private def decrypt(wallet: Wallet, pass: String) = Try {
      val scrypt: org.bitcoinj.crypto.KeyCrypter = wallet.getKeyCrypter
      wallet.getKeyChainSeed.decrypt(scrypt, pass, scrypt deriveKey pass)
    }

    def makeAttempt: Unit = {
      val (container, extraInputLayout, extraInput) = singleInputPopup
      val builder = titleBodyAsViewBuilder(title = null, body = container)
      mkCheckForm(proceed, none, builder, dialog_ok, dialog_cancel)
      extraInputLayout.setHint(password)
      showKeys(extraInput)

      def proceed(alert: AlertDialog): Unit = runAnd(alert.dismiss) {
        val core = SigningWallet(walletType = BIP32, isRemovable = true)
        decrypt(restoreLegacyWallet, extraInput.getText.toString) map { seed =>
          SetupActivity.fromMnemonics(seed.getMnemonicCode.asScala.toList, host = me)
          val wallet = LNParams.chainWallets.makeSigningWalletParts(core, Satoshi(0L), core.walletType)
          LNParams.chainWallets = LNParams.chainWallets.withFreshWallet(wallet)
          me exitTo ClassNames.hubActivityClass
          legacyWalletFile.delete
        } getOrElse makeAttempt
      }
    }
  }
}
