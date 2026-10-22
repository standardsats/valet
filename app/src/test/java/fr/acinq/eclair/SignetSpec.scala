package fr.acinq.eclair

import fr.acinq.bitcoin.{Block, ByteVector32, OP_0, OP_DUP, OP_PUSHDATA}
import fr.acinq.eclair.blockchain.electrum.Blockchain
import fr.acinq.eclair.payment.Bolt11Invoice
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import scodec.bits._

import scala.util.Try

class SignetSpec {
  @Test
  def signetGenesisBlockIsKnown: Unit =
    assertEquals(ByteVector32.fromValidHex("00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"), Block.SignetGenesisBlock.blockId)

  @Test
  def invoicePrefixesAreMatchedLongestFirst: Unit = {
    def prefixOf(hrp: String): Option[String] = Bolt11Invoice.prefixesLongestFirst.find(hrp.startsWith)
    assertEquals(Some("lnbcrt"), prefixOf("lnbcrt2500u"))
    assertEquals(Some("lntbs"), prefixOf("lntbs2500u"))
    assertEquals(Some("lntb"), prefixOf("lntb2500u"))
    assertEquals(Some("lnbc"), prefixOf("lnbc2500u"))
    assertEquals("lntbs", Bolt11Invoice.prefixes(Block.SignetGenesisBlock.hash))
  }

  @Test
  def testnetAddressesWorkOnSignet: Unit = {
    val p2wpkh = addressToPublicKeyScript("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", Block.SignetGenesisBlock.hash)
    assertEquals(OP_0 :: OP_PUSHDATA(hex"751e76e8199196d454941c45d1b3a323f1433bd6") :: Nil, p2wpkh)
    assertEquals(OP_DUP, addressToPublicKeyScript("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn", Block.SignetGenesisBlock.hash).head)
    assertTrue(Try(addressToPublicKeyScript("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", Block.SignetGenesisBlock.hash)).isFailure)
  }

  @Test
  def customSignetIsScannedFromGenesis: Unit = {
    val blockchain = Blockchain.fromGenesisBlock(Block.SignetGenesisBlock.hash, Block.SignetGenesisBlock.header)
    assertEquals(0, blockchain.height)
    assertEquals(Block.SignetGenesisBlock.header, blockchain.tip.header)
    assertTrue(Try(Blockchain.fromGenesisBlock(Block.SignetGenesisBlock.hash, Block.RegtestGenesisBlock.header)).isFailure)
  }
}
