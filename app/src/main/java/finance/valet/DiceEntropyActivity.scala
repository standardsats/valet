package finance.valet

import android.os.Bundle
import android.view.Gravity
import android.widget.{LinearLayout, TextView}
import com.google.android.material.button.{MaterialButton, MaterialButtonToggleGroup}
import com.ornach.nobobutton.NoboButton
import finance.valet.R.{id, layout, string}
import scodec.bits.ByteVector

import java.security.MessageDigest

object DiceEntropyActivity {
  /**
    * Hashing the fixed-length roll sequence makes the resulting BIP39 entropy
    * exactly 128 or 256 bits without introducing a base-6 conversion bias.
    */
  def entropy(rolls: Seq[Int], bits: Int): ByteVector = {
    require(bits == 128 || bits == 256)
    require(rolls.nonEmpty && rolls.forall(roll => roll >= 1 && roll <= 6))
    val digest = MessageDigest.getInstance(if (bits == 128) "SHA-256" else "SHA-512")
    val input = rolls.map(roll => (roll - 1).toByte).toArray
    ByteVector.view(digest.digest(input).take(bits / 8))
  }
}

class DiceEntropyActivity extends BaseActivity { me =>
  private[this] var bits = 128
  private[this] var rolls = Vector.empty[Int]
  private[this] lazy val counter = findViewById(id.diceEntropyCounter).asInstanceOf[TextView]
  private[this] lazy val pad = findViewById(id.dicePad).asInstanceOf[LinearLayout]
  private[this] lazy val create = findViewById(id.diceEntropyCreate).asInstanceOf[NoboButton]
  private[this] lazy val bitSelector = findViewById(id.diceEntropyBits).asInstanceOf[MaterialButtonToggleGroup]

  private def requiredRolls: Int = if (bits == 128) 50 else 100

  override def START(state: Bundle): Unit = {
    setContentView(layout.activity_dice_entropy)
    bitSelector.check(id.diceEntropy128)
    bitSelector.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener {
      override def onButtonChecked(group: MaterialButtonToggleGroup, checkedId: Int, checked: Boolean): Unit =
        if (checked) {
          bits = if (checkedId == id.diceEntropy256) 256 else 128
          clearRolls()
        }
    })
    buildPad()
    findViewById(id.diceEntropyClear).setOnClickListener(onButtonTap(clearRolls()))
    create.setOnClickListener(onButtonTap(createWallet()))
    updateView()
  }

  private def buildPad(): Unit = {
    pad.removeAllViews()
    (1 to 6).grouped(3).foreach { rowRolls =>
      val row = new LinearLayout(me)
      row.setGravity(Gravity.CENTER)
      row.setOrientation(LinearLayout.HORIZONTAL)
      rowRolls.foreach { roll =>
        val button = new MaterialButton(me)
        button.setText(Array('⚀', '⚁', '⚂', '⚃', '⚄', '⚅')(roll - 1).toString)
        button.setTextSize(28)
        button.setContentDescription(getString(string.dice_face).format(roll))
        button.setOnClickListener(onButtonTap {
          if (rolls.size < requiredRolls) {
            rolls :+= roll
            updateView()
          }
        })
        row.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
      }
      pad.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
  }

  private def clearRolls(): Unit = {
    rolls = Vector.empty
    updateView()
  }

  private def updateView(): Unit = {
    if (counter != null) counter.setText(getString(string.dice_entropy_counter).format(rolls.size, requiredRolls))
    if (create != null) create.setEnabled(rolls.size == requiredRolls)
  }

  private def createWallet(): Unit = {
    if (rolls.size != requiredRolls) {
      WalletApp.app.quickToast(string.dice_entropy_incomplete)
    } else {
      val entropy = DiceEntropyActivity.entropy(rolls, bits)
      val words = getAssets.open("bip39_english_wordlist.txt")
      val wordList = scala.io.Source.fromInputStream(words, "UTF-8").getLines.toArray
      val mnemonic = fr.acinq.bitcoin.MnemonicCode.toMnemonics(entropy, wordList)
      create.setEnabled(false)
      runInFutureProcessOnUI(SetupActivity.fromMnemonics(mnemonic, me), onFail)(_ => me exitTo ClassNames.hubActivityClass)
    }
  }
}
