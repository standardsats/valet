package fr.acinq.eclair

import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import akka.actor.{Actor, ActorSystem, Props}
import fr.acinq.eclair.blockchain.electrum.{ElectrumChainSync, ElectrumClient}
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.collection.mutable
import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

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

  @Test
  def badChunkAfterProgressRestartsOnlyOnce: Unit = {
    val system = ActorSystem("chain-restart-test")
    val messages = new LinkedBlockingQueue[Any]
    val wipes = new AtomicInteger
    val db = new MemoryHeaderDb {
      override def removeAllHeaders: Unit = { super.removeAllHeaders; wipes.incrementAndGet(); () }
    }
    val client = system.actorOf(Props(new Actor {
      def receive: Receive = {
        case _: ElectrumClient.AddStatusListener => ()
        case message => messages.put(message)
      }
      override def postStop(): Unit = { messages.put("disconnected"); () }
    }))
    val sync = system.actorOf(Props(new ElectrumChainSync(client, db, regtestHash)))
    val headers = mine(2)
    def next(): Any = messages.poll(10, TimeUnit.SECONDS)
    def beginConnection(): Unit = {
      sync.tell(ElectrumClient.ElectrumReady(2, headers.last, new InetSocketAddress("127.0.0.1", 50001)), client)
      assertTrue(next().isInstanceOf[ElectrumClient.HeaderSubscription])
      sync.tell(ElectrumClient.HeaderSubscriptionResponse(2, headers.last), client)
    }
    try {
      beginConnection()
      for (_ <- 1 to 2) {
        assertEquals(ElectrumClient.GetHeaders(1, 2016), next())
        sync.tell(ElectrumClient.GetHeadersResponse(1, headers.take(1), 2016), client)
        assertEquals(ElectrumClient.GetHeaders(2, 2016), next())
        sync.tell(ElectrumClient.GetHeadersResponse(2, List(headers.last.copy(hashPreviousBlock = ByteVector32.One)), 2016), client)
      }
      assertEquals("disconnected", next())
      assertEquals(1, wipes.get())
    } finally {
      system.shutdown()
      system.awaitTermination(10.seconds)
    }
  }
}
