package fr.acinq.eclair

import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import fr.acinq.eclair.blockchain.electrum.ElectrumChainSync
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.collection.mutable

class ChainLoadSpec {
  class MemoryHeaderDb extends HeaderDb {
    val headers: mutable.Map[Int, BlockHeader] = mutable.Map.empty
    def addHeaders(newHeaders: Seq[BlockHeader], startHeight: Int): Unit = for ((header, idx) <- newHeaders.zipWithIndex) headers.getOrElseUpdate(startHeight + idx, header)
    def getHeader(height: Int): Option[BlockHeader] = headers.get(height)
    def getHeader(blockHash: ByteVector32): Option[HeightAndHeader] = headers.collectFirst { case (height, header) if header.hash == blockHash => (height, header) }
    def getHeaders(startHeight: Int, maxCount: Int): Seq[BlockHeader] = headers.keys.filter(_ >= startHeight).toList.sorted.take(maxCount).map(headers)
    def getTip: Option[HeightAndHeader] = if (headers.isEmpty) None else Some(headers.maxBy(_._1))
    def removeAllHeaders: Unit = headers.clear
  }

  val regtestHash: ByteVector32 = Block.RegtestGenesisBlock.hash

  // Regtest proof of work is easy, about two tries for each header
  def mine(count: Int, from: BlockHeader = Block.RegtestGenesisBlock.header): Vector[BlockHeader] = (Vector(from) /: (1 to count)) { case (chain, idx) =>
    val template = BlockHeader(version = 4, hashPreviousBlock = chain.last.hash, hashMerkleRoot = ByteVector32.Zeroes, time = from.time + idx * 600L, bits = from.bits, nonce = 0)
    chain :+ Iterator.from(0).map(nonce => template.copy(nonce = nonce)).find(BlockHeader.checkProofOfWork).get
  }.tail

  def dbWith(headers: Seq[BlockHeader]): MemoryHeaderDb = {
    val db = new MemoryHeaderDb
    db.addHeaders(Block.RegtestGenesisBlock.header +: headers, startHeight = 0)
    db
  }

  @Test
  def emptyDbStartsFromGenesis: Unit = {
    val chain = ElectrumChainSync.loadChain(regtestHash, Vector.empty, new MemoryHeaderDb)
    assertEquals(0, chain.height)
    assertEquals(Block.RegtestGenesisBlock.header, chain.tip.header)
  }

  @Test
  def shortSavedChainIsAddedToGenesis: Unit = {
    val headers = mine(100)
    val chain = ElectrumChainSync.loadChain(regtestHash, Vector.empty, dbWith(headers))
    assertEquals(100, chain.height)
    assertEquals(headers.last, chain.tip.header)
    assertTrue(chain.checkpoints.isEmpty)
  }

  @Test
  def longSavedChainMakesCheckpoints: Unit = {
    val headers = mine(4500)
    val chain = ElectrumChainSync.loadChain(regtestHash, Vector.empty, dbWith(headers))
    assertEquals(4500, chain.height)
    assertEquals(headers.last, chain.tip.header)
    assertEquals(1, chain.checkpoints.size)
    assertEquals(headers(2014).hash, chain.checkpoints.head.hash)
  }

  @Test
  def savedHeadersOfAnotherChainGiveGenesis: Unit = {
    val otherChain = mine(50, from = Block.RegtestGenesisBlock.header.copy(nonce = 3))
    val chain = ElectrumChainSync.loadChain(regtestHash, Vector.empty, dbWith(otherChain))
    assertEquals(0, chain.height)
    assertEquals(Block.RegtestGenesisBlock.header, chain.tip.header)
  }
}
