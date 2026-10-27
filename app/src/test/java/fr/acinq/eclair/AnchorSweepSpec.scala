package fr.acinq.eclair

import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.{Block, ByteVector32, OutPoint, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.{EclairWallet, TxConfirmedAt}
import fr.acinq.eclair.blockchain.electrum.ElectrumWalletType
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.RemoteCommitPublished
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Scripts.toRemoteDelayed
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, TxOwner}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import scodec.bits._

class AnchorSweepSpec {
  val master: ExtendedPrivateKey = generate(ByteVector.fill(32)(1))
  val ewt: ElectrumWalletType = ElectrumWalletType.makeSigningType(EclairWallet.BIP84, master, Block.SignetGenesisBlock.hash)

  @Test
  def walletFindsPrivateKeysOfItsOwnKeys: Unit = {
    val receiveKey = derivePublicKey(ewt.accountMaster, 7L).publicKey
    val changeKey = derivePublicKey(ewt.changeMaster, 3L).publicKey
    assertEquals(Some(receiveKey), ewt.privateKeyFor(receiveKey, maxIndex = 100L).map(_.publicKey))
    assertEquals(Some(changeKey), ewt.privateKeyFor(changeKey, maxIndex = 100L).map(_.publicKey))
    assertEquals(None, ewt.privateKeyFor(receiveKey, maxIndex = 5L))
    assertEquals(None, ElectrumWalletType.makeSigningType(EclairWallet.BIP84, generate(ByteVector.fill(32)(2)), Block.SignetGenesisBlock.hash).privateKeyFor(receiveKey, maxIndex = 100L))
  }

  @Test
  def anchorToRemoteOutputIsSweptWithWalletKey: Unit = {
    val paymentBasepoint = derivePublicKey(ewt.accountMaster, 2L).publicKey
    val anchor = TxOut(Satoshi(330), Script.pay2wsh(hex"51"))
    val toRemote = TxOut(Satoshi(247716L), Script.pay2wsh(toRemoteDelayed(paymentBasepoint)))
    val commitTx = Transaction(version = 2, txIn = TxIn(OutPoint(ByteVector32.Zeroes, 0), ByteVector.empty, 0L) :: Nil, txOut = anchor :: toRemote :: Nil, lockTime = 0)
    val walletScript = Script.write(Script.pay2wpkh(derivePublicKey(ewt.accountMaster, 3L).publicKey))

    val info = Transactions.makeClaimRemoteDelayedOutputTx(commitTx, Satoshi(546L), paymentBasepoint, walletScript, FeeratePerKw(Satoshi(2500L))).right.get
    val privateKey = ewt.privateKeyFor(paymentBasepoint, maxIndex = 100L).get
    val signed = Transactions.addSigs(info, Transactions.sign(info, privateKey, TxOwner.Local, AnchorOutputsCommitmentFormat))

    assertTrue(Transactions.checkSpendable(signed).isSuccess)
    assertEquals(OutPoint(commitTx, 1), signed.tx.txIn.head.outPoint)
    // The to_remote output of an anchor channel has a 1-block CSV, the watcher delays publishing until the commit tx confirms
    assertEquals(1L, signed.tx.txIn.head.sequence)
    assertEquals(walletScript, signed.tx.txOut.head.publicKeyScript)
    assertTrue(signed.tx.txOut.head.amount < toRemote.amount)
  }

  @Test
  def newPreimageKeepsPublishedMainClaim: Unit = {
    def tx(lockTime: Long) = Transaction(version = 2, txIn = TxIn(OutPoint(ByteVector32.Zeroes, 0), ByteVector.empty, 0L) :: Nil, txOut = Nil, lockTime = lockTime)
    val (commitTx, publishedClaim, recomputedClaim, htlcSuccess) = (tx(0), tx(1), tx(2), tx(3))
    val spent = Map(OutPoint(commitTx, 1) -> TxConfirmedAt(blockHeight = 100, publishedClaim))

    val published = RemoteCommitPublished(commitTx, Some(publishedClaim), claimHtlcSuccessTxs = Nil, claimHtlcTimeoutTxs = Nil, irrevocablySpent = spent)
    val recomputed = RemoteCommitPublished(commitTx, Some(recomputedClaim), claimHtlcSuccessTxs = htlcSuccess :: Nil, claimHtlcTimeoutTxs = Nil)
    val merged = Closing.withNewHtlcClaims(published, recomputed)

    assertEquals(Some(publishedClaim), merged.claimMainOutputTx)
    assertEquals(spent, merged.irrevocablySpent)
    assertEquals(htlcSuccess :: Nil, merged.claimHtlcSuccessTxs)
    // No claim before (no key then): the new claim is used
    assertEquals(Some(recomputedClaim), Closing.withNewHtlcClaims(published.copy(claimMainOutputTx = None), recomputed).claimMainOutputTx)
  }
}
