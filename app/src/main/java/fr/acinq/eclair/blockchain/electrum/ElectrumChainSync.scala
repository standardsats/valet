package fr.acinq.eclair.blockchain.electrum

import akka.actor.{ActorRef, FSM, PoisonPill}
import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import fr.acinq.eclair.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.GetHeaders
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.{DISCONNECTED, RUNNING, SYNCING, WAITING_FOR_TIP}
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb

import scala.util.{Failure, Success, Try}


object ElectrumChainSync {
  case class ChainSyncStarted(localTip: Long, remoteTip: Long)
  case class ChainSyncEnded(localTip: Long)

  val genesisHeaders: Map[ByteVector32, BlockHeader] = List(Block.LivenetGenesisBlock, Block.Testnet3GenesisBlock,
    Block.Testnet4GenesisBlock, Block.SignetGenesisBlock, Block.RegtestGenesisBlock).map(block => block.hash -> block.header).toMap

  def genesisChain(chainHash: ByteVector32): Blockchain = Blockchain.fromGenesisBlock(chainHash, genesisHeaders(chainHash))

  // Saved headers make new checkpoints and the tip, so a start does not download them again
  // A chain without checkpoints in assets (regtest, a custom signet) starts from its genesis block until it has saved headers
  // If anything at all goes wrong, the chain starts from the checkpoints in assets or from the genesis block
  def loadChain(chainHash: ByteVector32, assetCheckpoints: Vector[CheckPoint], headerDb: HeaderDb): Blockchain = {
    val checkpoints = Try(CheckPoint.extend(assetCheckpoints, headerDb)) getOrElse assetCheckpoints

    if (checkpoints.isEmpty) {
      val headers = headerDb.getHeaders(startHeight = 1, maxCount = Int.MaxValue)
      Try apply Blockchain.addHeaders(genesisChain(chainHash), 1, headers) getOrElse genesisChain(chainHash)
    } else {
      val blockchain = Blockchain.fromCheckpoints(checkpoints = checkpoints, chainhash = chainHash)
      val headers = headerDb.getHeaders(startHeight = checkpoints.size * RETARGETING_PERIOD, maxCount = Int.MaxValue)
      Try apply Blockchain.addHeadersChunk(blockchain, checkpoints.size * RETARGETING_PERIOD, headers) getOrElse blockchain
    }
  }
}

class ElectrumChainSync(client: ActorRef, headerDb: HeaderDb, chainHash: ByteVector32) extends FSM[ElectrumWallet.State, Blockchain] {

  // Read once: Mutinynet has a large checkpoints file
  val assetCheckpoints: Vector[CheckPoint] = CheckPoint.loadFromChainHash(chainHash)
  private var restartedFromGenesis = false

  // Regtest or a custom signet can be reset by its operator, then the saved headers belong to an old chain
  // Only the server tells the chain, so the saved headers go and the chain starts again from the genesis block
  def mayRestartFromGenesis(blockchain: Blockchain): Boolean = !restartedFromGenesis && assetCheckpoints.isEmpty && (blockchain.checkpoints.nonEmpty || blockchain.height > 0)

  def restartFromGenesis(remoteHeight: Int): State = {
    restartedFromGenesis = true
    log.warning(s"Saved headers do not match the server chain at height $remoteHeight, chain starts again from genesis")
    headerDb.removeAllHeaders
    context.system.eventStream publish ElectrumChainSync.ChainSyncStarted(localTip = 0L, remoteHeight)
    client ! ElectrumClient.GetHeaders(1, RETARGETING_PERIOD)
    goto(SYNCING) using ElectrumChainSync.genesisChain(chainHash)
  }

  client ! ElectrumClient.AddStatusListener(self)

  startWith(DISCONNECTED, ElectrumChainSync.loadChain(chainHash, assetCheckpoints, headerDb))

  when(DISCONNECTED) {
    case Event(_: ElectrumClient.ElectrumReady, blockchain) =>
      // A later connection may belong to a freshly reset chain, but bad chunks on this connection get only one retry.
      restartedFromGenesis = false
      client ! ElectrumClient.HeaderSubscription(self)
      goto(WAITING_FOR_TIP) using blockchain
  }

  when(WAITING_FOR_TIP) {
    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) if response.height < blockchain.height && mayRestartFromGenesis(blockchain) =>
      restartFromGenesis(response.height)

    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) if response.height < blockchain.height =>
      goto(DISCONNECTED) replying PoisonPill

    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) if mayRestartFromGenesis(blockchain) && blockchain.bestchain.nonEmpty &&
      response.height == blockchain.height && response.header != blockchain.tip.header =>
      restartFromGenesis(response.height)

    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) if blockchain.bestchain.isEmpty =>
      context.system.eventStream publish ElectrumChainSync.ChainSyncStarted(blockchain.height, response.height)
      client ! ElectrumClient.GetHeaders(blockchain.checkpoints.size * RETARGETING_PERIOD, RETARGETING_PERIOD)
      goto(SYNCING)

    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) if response.header == blockchain.tip.header =>
      context.system.eventStream publish ElectrumChainSync.ChainSyncEnded(blockchain.height)
      context.system.eventStream publish blockchain
      goto(RUNNING)

    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) =>
      context.system.eventStream publish ElectrumChainSync.ChainSyncStarted(blockchain.height, response.height)
      client ! ElectrumClient.GetHeaders(blockchain.tip.height + 1, RETARGETING_PERIOD)
      goto(SYNCING)
  }

  when(SYNCING) {
    case Event(response: ElectrumClient.GetHeadersResponse, blockchain) if response.headers.isEmpty =>
      context.system.eventStream publish ElectrumChainSync.ChainSyncEnded(blockchain.height)
      context.system.eventStream publish blockchain
      goto(RUNNING)

    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), blockchain) =>
      val blockchain1Try = Try apply Blockchain.addHeaders(blockchain, start, headers)

      blockchain1Try match {
        case Success(blockchain1) =>
          val (blockchain2, chunks) = Blockchain.optimize(blockchain1)
          client ! ElectrumClient.GetHeaders(blockchain2.tip.height + 1, RETARGETING_PERIOD)
          log.info(s"Got new headers chunk at ${blockchain2.tip.height}, requesting next chunk")
          // A chain which is scanned from genesis has nothing to persist while it is shorter than MAX_REORG
          if (chunks.nonEmpty) headerDb.addHeaders(chunks.map(_.header), chunks.head.height)
          goto(SYNCING) using blockchain2

        case Failure(_) if mayRestartFromGenesis(blockchain) =>
          restartFromGenesis(start + headers.size - 1)

        case Failure(error) =>
          log.error("Electrum peer sent bad headers", error)
          goto(DISCONNECTED) replying PoisonPill
      }

    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), _) =>
      log.debug(s"Ignoring header $header at $height while syncing")
      stay
  }

  when(RUNNING) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), blockchain) if blockchain.tip.header != header =>
      val difficultyOk = Blockchain.getDifficulty(blockchain, height, headerDb).forall(header.bits.==)
      val blockchain1Try = Try apply Blockchain.addHeader(blockchain, height, header)

      blockchain1Try match {
        case Success(blockchain1) if difficultyOk =>
          val (blockchain2, chunks) = Blockchain.optimize(blockchain1)
          if (chunks.nonEmpty) headerDb.addHeaders(chunks.map(_.header), chunks.head.height)
          log.info(s"Got new chain tip ${header.blockId} at $height")
          context.system.eventStream publish blockchain2
          stay using blockchain2

        case _ =>
          log.error("Electrum peer sent bad headers")
          stay replying PoisonPill
      }

    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), blockchain) =>
      val blockchain1Try = Try apply Blockchain.addHeaders(blockchain, start, headers)

      blockchain1Try match {
        case Success(blockchain1) =>
          headerDb.addHeaders(headers, start)
          context.system.eventStream publish blockchain1
          stay using blockchain1

        case _ =>
          log.error("Electrum peer sent bad headers")
          stay replying PoisonPill
      }

    case Event(ElectrumWallet.ChainFor(target), blockchain) =>
      target ! blockchain
      stay
  }

  whenUnhandled {
    case Event(getHeaders: GetHeaders, _) =>
      client ! getHeaders
      stay

    case Event(ElectrumClient.ElectrumDisconnected, _) =>
      goto(DISCONNECTED)
  }

  initialize
}
