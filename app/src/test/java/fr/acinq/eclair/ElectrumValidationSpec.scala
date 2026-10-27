package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.JsonRPCResponse
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.transactions.Scripts
import org.json4s.JsonAST.JString
import org.json4s.native.JsonMethods
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test
import scodec.bits._

class ElectrumValidationSpec {
  // 4 version + 1 + 41 input + 1 + 13 output + 4 locktime = 64 bytes, the same size as an inner node of a merkle proof
  val tx64: Transaction = Transaction(version = 1, txIn = TxIn(OutPoint(ByteVector32.One, 0), ByteVector.empty, 0xffffffffL) :: Nil, txOut = TxOut(Satoshi(1000L), hex"51515151") :: Nil, lockTime = 0L)

  def merkleJson(height: Int, merkle: Seq[ByteVector32], pos: Int): JsonRPCResponse =
    JsonRPCResponse(JsonMethods.parse(s"""{"block_height":$height,"merkle":[${merkle.map(hash => "\"" + hash.toHex + "\"").mkString(",")}],"pos":$pos}"""), None, "0")

  @Test
  def transactionWithRequestedTxidIsAccepted: Unit = {
    val response = parseJsonResponse(GetTransaction(tx64.txid), JsonRPCResponse(JString(Transaction.write(tx64).toHex), None, "0"))
    assertEquals(GetTransactionResponse(tx64, None), response)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def transactionWithOtherTxidIsRejected: Unit =
    parseJsonResponse(GetTransaction(ByteVector32.One), JsonRPCResponse(JString(Transaction.write(tx64).toHex), None, "0"))

  val (leaf, sibling) = (ByteVector32(ByteVector.fill(32)(0x22)), ByteVector32(ByteVector.fill(32)(0x33)))

  @Test
  def merkleProofForRequestedHeightIsAccepted: Unit = {
    val response = parseJsonResponse(GetMerkle(leaf, 100), merkleJson(100, sibling :: Nil, 0))
    assertEquals(GetMerkleResponse(leaf, sibling :: Nil, 100, 0), response)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def merkleProofForOtherHeightIsRejected: Unit =
    parseJsonResponse(GetMerkle(leaf, 100), merkleJson(101, sibling :: Nil, 0))

  @Test
  def merkleProofWithTransactionAsInnerNodeIsRejected: Unit = {
    val raw = Transaction.write(tx64)
    assertEquals(64L, raw.size)
    assertTrue(readsAsTransaction(raw))
    assertFalse(readsAsTransaction(ByteVector.fill(64)(0x11)))

    // A fake leaf is the second half of a real 64-byte tx and its sibling is the first half: their parent is the real tx, so the proof leads to a real root
    val fakeTxid = ByteVector32(raw.drop(32)).reverse
    val firstHalf = ByteVector32(raw.take(32)).reverse
    assertEquals(tx64.hash, GetMerkleResponse(fakeTxid, firstHalf :: Nil, 100, 1).root)
    assertTrue(scala.util.Try(parseJsonResponse(GetMerkle(fakeTxid, 100), merkleJson(100, firstHalf :: Nil, 1))).isFailure)
  }

  @Test
  def watcherAcceptsOnlyValidFundingSpend: Unit = {
    val (priv1, priv2) = (PrivateKey(ByteVector32(ByteVector.fill(32)(1))), PrivateKey(ByteVector32(ByteVector.fill(32)(2))))
    val redeemScript = Script.write(Scripts.multiSig2of2(priv1.publicKey, priv2.publicKey))
    val fundingScript = Script.write(Script pay2wsh redeemScript)
    val amount = Satoshi(100000L)

    val unsigned = Transaction(version = 2, txIn = TxIn(OutPoint(ByteVector32.One, 0), ByteVector.empty, 0L) :: Nil, txOut = TxOut(Satoshi(99000L), Script pay2wpkh priv1.publicKey) :: Nil, lockTime = 0L)
    def sig(key: PrivateKey) = Crypto.der2compact(Transaction.signInput(unsigned, 0, redeemScript, SIGHASH_ALL, amount, SigVersion.SIGVERSION_WITNESS_V0, key))
    val signed = unsigned.updateWitness(0, Scripts.witness2of2(sig(priv1), sig(priv2), priv1.publicKey, priv2.publicKey))
    val forged = unsigned.updateWitness(0, Scripts.witness2of2(sig(priv1), sig(priv1), priv1.publicKey, priv2.publicKey))

    assertTrue(ElectrumWatcher.spendsOutput(signed, 0, fundingScript, amount))
    assertFalse(ElectrumWatcher.spendsOutput(forged, 0, fundingScript, amount))
    assertFalse(ElectrumWatcher.spendsOutput(unsigned, 0, fundingScript, amount))
    // Segwit signatures commit to the amount of a spent output
    assertFalse(ElectrumWatcher.spendsOutput(signed, 0, fundingScript, amount + Satoshi(1L)))
  }

  @Test
  def listedReplacementWinsAndUnlistedTxIsDropped: Unit = {
    val ewt = ElectrumWalletType.makeSigningType(EclairWallet.BIP84, generate(ByteVector.fill(32)(1)), Block.SignetGenesisBlock.hash)
    val receiveScript = Script.write(ewt.computePublicKeyScript(derivePublicKey(ewt.accountMaster, 0L).publicKey))
    val scriptHash = computeScriptHash(receiveScript)

    // An incoming payment and its fee bump: both pay to a receive address, neither has a change output
    def payment(amount: Long) = Transaction(version = 2, txIn = TxIn(OutPoint(ByteVector32.One, 0), ByteVector.empty, 0L) :: Nil, txOut = TxOut(Satoshi(amount), receiveScript) :: Nil, lockTime = 0L)
    val (original, replacement) = (payment(10000L), payment(9000L))

    def data(listed: Transaction) = ElectrumData(ewt, Blockchain(ewt.chainHash, checkpoints = Vector.empty, headersMap = Map.empty, bestchain = Vector.empty),
      accountKeys = Vector.empty, changeKeys = Vector.empty, excludedOutPoints = Nil, status = Map.empty, transactions = Map(original.txid -> original, replacement.txid -> replacement),
      overriddenPendingTxids = Map.empty, history = Map(scriptHash -> List(TransactionHistoryItem(0, listed.txid))), proofs = Map.empty).withOverridingTxids

    assertEquals(Map(original.txid -> replacement.txid), data(listed = replacement).overriddenPendingTxids)
    assertEquals(Map(replacement.txid -> original.txid), data(listed = original).overriddenPendingTxids)

    assertTrue(data(listed = replacement).isUnlisted(original.txid))
    assertFalse(data(listed = replacement).isUnlisted(replacement.txid))
    // While a history is being refreshed, nothing is reported as dropped
    assertFalse(data(listed = replacement).copy(pendingHistoryRequests = Set(scriptHash)).isUnlisted(original.txid))
    // A confirmed tx is never dropped
    assertFalse(data(listed = replacement).copy(proofs = Map(original.txid -> GetMerkleResponse(original.txid, Nil, 1, 0))).isUnlisted(original.txid))
  }
}
