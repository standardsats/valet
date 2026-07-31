package finance.valet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.{ArrayAdapter, LinearLayout}
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.transition.TransitionManager
import finance.valet.BaseActivity.StringOps
import finance.valet.R.string._
import finance.valet.utils.LocalBackup
import com.google.common.io.ByteStreams
import com.ornach.nobobutton.NoboButton
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.eclair.wire.CommonCodecs.nodeaddress
import fr.acinq.eclair.wire.{Domain, NodeAddress}
import immortan.crypto.Tools.{SEPARATOR, none, runAnd, ~}
import immortan.wire.ExtCodecs
import immortan.{LNParams, LightningNodeKeys, WalletSecret}
import scodec.bits.{BitVector, ByteVector}

import scala.util.{Failure, Success}


object SetupActivity {
  def fromMnemonics(mnemonics: List[String], host: BaseActivity): Unit = {
    val walletSeed = MnemonicCode.toSeed(mnemonics, passphrase = new String)
    val keys = LightningNodeKeys.makeFromSeed(seed = walletSeed.toArray)
    val secret = WalletSecret(keys, mnemonics, walletSeed)

    try {
      // Implant graph into db file from resources
      val snapshotName = LocalBackup.getGraphResourceName(LNParams.chainHash)
      val compressedPlainBytes = ByteStreams.toByteArray(host.getAssets open snapshotName)
      val plainBytes = ExtCodecs.compressedByteVecCodec.decode(BitVector view compressedPlainBytes)
      LocalBackup.copyPlainDataToDbLocation(host, WalletApp.dbFileNameGraph, plainBytes.require.value)
    } catch {
      case e: Throwable => println(s"Failed to read compressed graph due: $e")
    }

    WalletApp.extDataBag.putSecret(secret)
    WalletApp.makeOperational(secret)
  }
}

class SetupActivity extends BaseActivity { me =>
  private[this] lazy val activitySetupMain = findViewById(R.id.activitySetupMain).asInstanceOf[LinearLayout]
  private[this] lazy val restoreOptionsButton = findViewById(R.id.restoreOptionsButton).asInstanceOf[NoboButton]
  private[this] lazy val restoreOptions = findViewById(R.id.restoreOptions).asInstanceOf[LinearLayout]
  private[this] final val FILE_REQUEST_CODE = 112

  lazy private[this] val enforceTor = new SettingsHolder(me) {
    override def updateView: Unit = settingsCheck.setChecked(WalletApp.ensureTor)
    settingsTitle.setText(settings_ensure_tor)
    setVis(isVisible = false, settingsInfo)
    disableIfOldAndroid

    view setOnClickListener onButtonTap {
      putBoolAndUpdateView(WalletApp.ENSURE_TOR, !WalletApp.ensureTor)
    }
  }

  lazy private[this] val electrum: SettingsHolder = new SettingsHolder(me) {
    setVis(isVisible = false, settingsCheck)

    override def updateView: Unit = WalletApp.customElectrumAddress match {
      case Success(nodeAddress) => setTexts(settings_custom_electrum_enabled, nodeAddress.toString)
      case _ => setTexts(settings_custom_electrum_disabled, me getString settings_custom_electrum_disabled_tip)
    }

    view setOnClickListener onButtonTap {
      val (container, extraInputLayout, extraInput) = singleInputPopup
      val builder = titleBodyAsViewBuilder(getString(settings_custom_electrum_disabled).asDefView, container)
      mkCheckForm(alert => runAnd(alert.dismiss)(proceed), none, builder, dialog_ok, dialog_cancel)
      extraInputLayout.setHint(settings_custom_electrum_host_port)
      showKeys(extraInput)

      def proceed: Unit = {
        val input = extraInput.getText.toString.trim
        def saveAddress(address: String) = WalletApp.app.prefs.edit.putString(WalletApp.CUSTOM_ELECTRUM_ADDRESS, address)
        // Unlike the same setting in SettingsActivity there is no restart notice here: the
        // address is read by WalletApp.makeOperational, which only runs once setup finishes,
        // so a node chosen now is already in place for the very first connection.
        if (input.nonEmpty) runInFutureProcessOnUI(saveUnsafeElectrumAddress, onFail)(_ => updateView)
        else runAnd(saveAddress(new String).commit)(updateView)

        def saveUnsafeElectrumAddress: Unit = {
          val idx = input.lastIndexOf(':')
          require(idx > 0 && idx < input.length - 1, "Expected <host>:<port>")
          val hostOrIP ~ port = input.splitAt(idx)
          val nodeAddress = NodeAddress.fromParts(hostOrIP, port.tail.toInt, Domain)
          saveAddress(nodeaddress.encode(nodeAddress).require.toHex).commit
        }
      }
    }

    def setTexts(titleRes: Int, info: String): Unit = {
      settingsTitle.setText(titleRes)
      settingsInfo.setText(info)
    }
  }

  override def START(s: Bundle): Unit = {
    setContentView(R.layout.activity_setup)
    activitySetupMain.addView(enforceTor.view, 0)
    activitySetupMain.addView(electrum.view, 1)
    enforceTor.updateView
    electrum.updateView
  }

  private[this] lazy val englishWordList = {
    val rawData = getAssets.open("bip39_english_wordlist.txt")
    scala.io.Source.fromInputStream(rawData, "UTF-8").getLines.toArray
  }

  var proceedWithMnemonics: List[String] => Unit = mnemonics => {
    // Make sure this method can be run at most once (to not set runtime data twice) by replacing it with a noop method right away
    runInFutureProcessOnUI(SetupActivity.fromMnemonics(mnemonics, me), onFail)(_ => me exitTo ClassNames.hubActivityClass)
    TransitionManager.beginDelayedTransition(activitySetupMain)
    activitySetupMain.setVisibility(View.GONE)
    proceedWithMnemonics = none
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent): Unit =
    if (requestCode == FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && resultData != null) {
      val cipherBytes = ByteStreams.toByteArray(getContentResolver openInputStream resultData.getData)

      showMnemonicPopup(R.string.action_backup_present_title) { mnemonics =>
        val walletSeed = MnemonicCode.toSeed(mnemonics, passphrase = new String)
        LocalBackup.decryptBackup(ByteVector.view(cipherBytes), walletSeed) match {

          case Success(plainEssentialBytes) =>
            // We were able to decrypt a file, implant it into db location and proceed
            LocalBackup.copyPlainDataToDbLocation(me, WalletApp.dbFileNameEssential, plainEssentialBytes)
            // Delete user-selected backup file while we can here and make an app-owned backup shortly
            DocumentFile.fromSingleUri(me, resultData.getData).delete
            WalletApp.backupSaveWorker.replaceWork(true)
            proceedWithMnemonics(mnemonics)

          case Failure(exception) =>
            val msg = getString(R.string.error_could_not_decrypt)
            onFail(msg format exception.getMessage)
        }
      }
    }

  def createNewWallet(view: View): Unit = {
    val twelveWordsEntropy: ByteVector = fr.acinq.eclair.randomBytes(16)
    val mnemonic = MnemonicCode.toMnemonics(twelveWordsEntropy, englishWordList)
    proceedWithMnemonics(mnemonic)
  }

  def showRestoreOptions(view: View): Unit = {
    TransitionManager.beginDelayedTransition(activitySetupMain)
    restoreOptionsButton.setVisibility(View.GONE)
    restoreOptions.setVisibility(View.VISIBLE)
  }

  def useBackupFile(view: View): Unit = startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"), FILE_REQUEST_CODE)

  def useRecoveryPhrase(view: View): Unit = showMnemonicPopup(R.string.action_recovery_phrase_title)(proceedWithMnemonics)

  def showMnemonicPopup(title: Int)(onMnemonic: List[String] => Unit): Unit = {
    val mnemonicWrap = getLayoutInflater.inflate(R.layout.frag_mnemonic, null).asInstanceOf[LinearLayout]
    val recoveryPhrase = mnemonicWrap.findViewById(R.id.recoveryPhrase).asInstanceOf[com.hootsuite.nachos.NachoTextView]
    recoveryPhrase.addChipTerminator(' ', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
    recoveryPhrase.addChipTerminator(',', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
    recoveryPhrase.addChipTerminator('\n', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
    recoveryPhrase setAdapter new ArrayAdapter(me, android.R.layout.simple_list_item_1, englishWordList)
    // appcompat's own resource (#ff5a595b). It used to resolve through the app's R class
    // because library resources were merged into it; AGP 8 turns that off, so it has to
    // be addressed in appcompat's namespace. The framework's copy is private, not public.
    recoveryPhrase setDropDownBackgroundResource androidx.appcompat.R.color.button_material_dark

    def getMnemonicList: List[String] = {
      val mnemonic = recoveryPhrase.getText.toString.toLowerCase.trim
      val pureMnemonic = mnemonic.replaceAll("[^a-zA-Z0-9']+", SEPARATOR)
      pureMnemonic.split(SEPARATOR).toList
    }

    def proceed(alert: AlertDialog): Unit = try {
      MnemonicCode.validate(getMnemonicList, englishWordList)
      onMnemonic(getMnemonicList)
      alert.dismiss
    } catch {
      case exception: Throwable =>
        val msg = getString(R.string.error_wrong_phrase)
        onFail(msg format exception.getMessage)
    }

    val builder = titleBodyAsViewBuilder(getString(title).asDefView, mnemonicWrap)
    val alert = mkCheckForm(proceed, none, builder, R.string.dialog_ok, R.string.dialog_cancel)
    updatePopupButton(getPositiveButton(alert), isEnabled = false)

    recoveryPhrase addTextChangedListener onTextChange { _ =>
      updatePopupButton(getPositiveButton(alert), getMnemonicList.size > 11)
    }
  }
}
