package immortan

import java.util.concurrent.atomic.AtomicLong

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.util.Timeout
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin._
import fr.acinq.eclair.Features._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.blockchain.electrum.db.{CompleteChainWalletInfo, SigningWallet, WatchingWallet}
import fr.acinq.eclair.channel.{ChannelKeys, LocalParams, PersistentChannelData}
import fr.acinq.eclair.router.ChannelUpdateExt
import fr.acinq.eclair.router.Router.{PublicChannel, RouterConf}
import fr.acinq.eclair.transactions.{DirectedHtlc, RemoteFulfill}
import fr.acinq.eclair.wire._
import immortan.SyncMaster.ShortChanIdSet
import immortan.crypto.CanBeShutDown
import immortan.crypto.Noise.KeyPair
import immortan.crypto.Tools._
import immortan.sqlite._
import immortan.utils._
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.util.Try


object LNParams {
  val blocksPerDay: Int = 144 // On average we can expect this many blocks per day
  val ncFulfillSafetyBlocks: Int = 36 // Force-close and redeem revealed incoming payment on chain if NC peer stalls state update and this many blocks are left until expiration
  val hcFulfillSafetyBlocks: Int = 72 // Offer to publish revealed incoming payment preimage on chain if HC peer stalls state update and this many blocks are left until expiration
  val cltvRejectThreshold: Int = hcFulfillSafetyBlocks + 36 // Reject incoming payment right away if CLTV expiry is closer than this to current chain tip when HTLC arrives
  val incomingFinalCltvExpiry: CltvExpiryDelta = CltvExpiryDelta(hcFulfillSafetyBlocks + 72) // Ask payer to set final CLTV expiry to current chain tip + this many blocks

  val failedChanRecoveryMsec: Double = 600000D // Failed-at-amount channels are fully recovered and their original capacity can be tried again after this much time

  val maxCltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(2016) // A relative expiry of the whole route can not exceed this much blocks
  val maxToLocalDelay: CltvExpiryDelta = CltvExpiryDelta(2016) // We ask peer to delay their payment for this long in case of force-close
  val maxFundingSatoshis: Satoshi = Satoshi(10000000000L) // Proposed channels of capacity more than this are not allowed
  val maxReserveToFundingRatio: Double = 0.02 // %
  val maxNegotiationIterations: Int = 20
  val maxChainConnectionsCount: Int = 3
  val maxAcceptedHtlcs: Int = 483
  val maxInChannelHtlcs: Int = 10
  val maxHoldSecs: Long = 600L

  val maxOffChainFeeRatio: Double = 0.01 // We are OK with paying up to this % of LN fee relative to payment amount
  val maxOffChainFeeAboveRatio: MilliSatoshi = MilliSatoshi(10000L) // For small amounts we always accept fee up to this

  val shouldSendUpdateFeerateDiff = 5.0
  val shouldRejectPaymentFeerateDiff = 20.0
  val shouldForceClosePaymentFeerateDiff = 50.0

  val ourRoutingCltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144 * 2) // We will reserve this many blocks for our incoming routed HTLC
  val minRoutingCltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144 * 3) // Ask relayer to set CLTV expiry delta to at least this many blocks

  val minInvoiceExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(18) // If payee does not provide an explicit relative CLTV this is what we use by default
  val minForceClosableIncomingHtlcAmountToFeeRatio = 4 // When incoming HTLC gets (nearly) expired, how much higher than trim threshold should it be for us to force-close
  val minForceClosableOutgoingHtlcAmountToFeeRatio = 5 // When peer sends a suspiciously low feerate, how much higher than trim threshold should our outgoing HTLC be for us to force-close
  val minPayment: MilliSatoshi = MilliSatoshi(1000L) // We can neither send nor receive LN payments which are below this value
  val minChanDustLimit: Satoshi = Satoshi(354L)
  val minDepthBlocks: Int = 3

  // Variables to be assigned at runtime

  var secret: WalletSecret = _
  var chainHash: ByteVector32 = _
  var chainWallets: WalletExt = _
  var connectionProvider: ConnectionProvider = _
  var logBag: SQLiteLog = _
  var cm: ChannelMaster = _

  var ourInit: Init = _
  var routerConf: RouterConf = _
  var syncParams: SyncParams = _
  var fiatRates: FiatRates = _
  var feeRates: FeeRates = _

  var trampoline: TrampolineOn = TrampolineOn(minPayment, Long.MaxValue.msat, feeProportionalMillionths = 1000L, exponent = 0.0, logExponent = 0.0, minRoutingCltvExpiryDelta)

  val blockCount: AtomicLong = new AtomicLong(0L)

  def isOperational: Boolean =
    null != chainHash && null != secret && null != chainWallets && connectionProvider != null &&
      null != syncParams && null != trampoline && null != fiatRates && null != feeRates && null != cm &&
      null != cm.inProcessors && null != cm.sendTo && null != logBag && null != routerConf && null != ourInit

  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val system: ActorSystem = ActorSystem("immortan-actor-system")
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.Implicits.global

  def createInit: Init = {
    val networks: InitTlv = InitTlv.Networks(chainHash :: Nil)
    val tlvStream: TlvStream[InitTlv] = TlvStream(networks)

    Init(Features(
      (ChannelRangeQueries, FeatureSupport.Optional),
      (ChannelRangeQueriesExtended, FeatureSupport.Optional),
      (BasicMultiPartPayment, FeatureSupport.Optional),
      (VariableLengthOnion, FeatureSupport.Optional),
      (ShutdownAnySegwit, FeatureSupport.Optional),
      (StaticRemoteKey, FeatureSupport.Optional),
      (AnchorOutputs, FeatureSupport.Optional),
      (AnchorOutputsZeroFeeHtlcTx, FeatureSupport.Optional),
      (ChannelType, FeatureSupport.Optional),
      (ScidAlias, FeatureSupport.Optional),
      (DataLossProtect, FeatureSupport.Optional),
      (HostedChannels, FeatureSupport.Optional),
      (PaymentSecret, FeatureSupport.Optional),
      (Wumbo, FeatureSupport.Optional)
    ), tlvStream)
  }

  // We make sure force-close pays directly to our local wallet always
  def makeChannelParams(isFunder: Boolean, fundingAmount: Satoshi): LocalParams = {
    val walletPubKey = Await.result(chainWallets.lnWallet.getReceiveAddresses, atMost = 40.seconds).keys.head.publicKey
    makeChannelParams(Script.write(Script.pay2wpkh(walletPubKey).toList), walletPubKey, isFunder, fundingAmount)
  }

  // We make sure that funder and fundee key path end differently
  def makeChannelParams(defaultFinalScriptPubkey: ByteVector, walletStaticPaymentBasepoint: PublicKey, isFunder: Boolean, fundingAmount: Satoshi): LocalParams =
    makeChannelParams(defaultFinalScriptPubkey, walletStaticPaymentBasepoint, isFunder, ChannelKeys.newKeyPath(isFunder), fundingAmount)

  // Note: we set local maxHtlcValueInFlightMsat to channel capacity to simplify calculations
  def makeChannelParams(defFinalScriptPubkey: ByteVector, walletStaticPaymentBasepoint: PublicKey, isFunder: Boolean, keyPath: DeterministicWallet.KeyPath, fundingAmount: Satoshi): LocalParams =
    LocalParams(ChannelKeys.fromPath(secret.keys.master, keyPath), minChanDustLimit, UInt64(fundingAmount.toMilliSatoshi.toLong), channelReserve = (fundingAmount * 0.001).max(minChanDustLimit),
      minPayment, maxToLocalDelay, maxInChannelHtlcs, isFunder, defFinalScriptPubkey, walletStaticPaymentBasepoint)

  def currentBlockDay: Long = blockCount.get / blocksPerDay

  def isPeerSupports(theirInit: Init)(feature: Feature with InitFeature): Boolean = Features.canUseFeature(ourInit.features, theirInit.features, feature)

  def loggedActor(childProps: Props, childName: String): ActorRef = system actorOf Props(classOf[LoggingSupervisor], childProps, childName)

  def addressToPubKeyScript(address: String): ByteVector = Script write addressToPublicKeyScript(address, chainHash)

  def isMainnet: Boolean = chainHash == Block.LivenetGenesisBlock.hash
}

case class WalletExt(wallets: List[ElectrumEclairWallet], catcher: ActorRef, sync: ActorRef, pool: ActorRef, watcher: ActorRef, params: WalletParameters) extends CanBeShutDown { me =>

  lazy val lnWallet: ElectrumEclairWallet = wallets.find(_.isBuiltIn).get

  lazy val usableWallets: List[ElectrumEclairWallet] = wallets.filter(wallet => wallet.isBuiltIn || wallet.hasFingerprint)

  def findByPubKey(pub: PublicKey): Option[ElectrumEclairWallet] = wallets.find(_.ewt.xPub.publicKey == pub)

  def makeSigningWalletParts(core: SigningWallet, lastBalance: Satoshi, label: String): ElectrumEclairWallet = {
    val ewt = ElectrumWalletType.makeSigningType(tag = core.walletType, master = LNParams.secret.keys.master, chainHash = LNParams.chainHash)
    val walletRef = LNParams.loggedActor(Props(classOf[ElectrumWallet], pool, sync, params, ewt), core.walletType + "-signing-wallet")
    val infoNoPersistent = CompleteChainWalletInfo(core, data = ByteVector.empty, lastBalance, label, isCoinControlOn = false)
    ElectrumEclairWallet(walletRef, ewt, infoNoPersistent)
  }

  def makeWatchingWallet84Parts(core: WatchingWallet, lastBalance: Satoshi, label: String): ElectrumEclairWallet = {
    val ewt: ElectrumWallet84 = new ElectrumWallet84(secrets = None, xPub = core.xPub, chainHash = LNParams.chainHash)
    val walletRef = LNParams.loggedActor(Props(classOf[ElectrumWallet], pool, sync, params, ewt), core.walletType + "-watching-wallet")
    val infoNoPersistent = CompleteChainWalletInfo(core, data = ByteVector.empty, lastBalance, label, isCoinControlOn = false)
    ElectrumEclairWallet(walletRef, ewt, infoNoPersistent)
  }

  def withFreshWallet(eclairWallet: ElectrumEclairWallet): WalletExt = {
    params.walletDb.addChainWallet(eclairWallet.info, params.emptyPersistentDataBytes, eclairWallet.ewt.xPub.publicKey)
    eclairWallet.walletRef ! params.emptyPersistentDataBytes
    sync ! ElectrumWallet.ChainFor(eclairWallet.walletRef)
    copy(wallets = eclairWallet :: wallets)
  }

  def withoutWallet(wallet: ElectrumEclairWallet): WalletExt = {
    require(wallet.info.core.isRemovable, "Wallet is not removable")
    params.walletDb.remove(pub = wallet.ewt.xPub.publicKey)
    params.txDb.removeByPub(xPub = wallet.ewt.xPub)
    val wallets1 = wallets diff List(wallet)
    wallet.walletRef ! PoisonPill
    copy(wallets = wallets1)
  }

  def withNewLabel(label: String)(wallet1: ElectrumEclairWallet): WalletExt = {
    require(!wallet1.isBuiltIn, "Can not re-label a default built in chain wallet")
    def sameXPub(wallet: ElectrumEclairWallet): Boolean = wallet.ewt.xPub == wallet1.ewt.xPub
    params.walletDb.updateLabel(label, pub = wallet1.ewt.xPub.publicKey)
    me.modify(_.wallets.eachWhere(sameXPub).info.label).setTo(label)
  }

  override def becomeShutDown: Unit = {
    val actors = List(catcher, sync, pool, watcher)
    val allActors = wallets.map(_.walletRef) ++ actors
    allActors.foreach(_ ! PoisonPill)
  }
}

class SyncParams {
  val satm: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02cd1b7bc418fac2dc99f0ba350d60fa6c45fde5ab6017ee14df6425df485fb1dd"), NodeAddress.unresolved(80, host = 134, 209, 228, 207), "SATM")
  val sts: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02208879ee204651619a51f6e48e159da645f1acbf417b3b399358ea7784df066f"), NodeAddress.unresolved(9735, host = 157, 230, 113, 217), "StS")

  val bCashIsTrash: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0298f6074a454a1f5345cb2a7c6f9fce206cd0bf675d177cdbf0ca7508dd28852f"), NodeAddress.unresolved(9735, host = 73, 119, 255, 56), "bCashIsTrash")
  val silentBob: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02e9046555a9665145b0dbd7f135744598418df7d61d3660659641886ef1274844"), NodeAddress.unresolved(9735, host = 31, 16, 52, 37), "SilentBob")
  val acinq: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.unresolved(9735, host = 34, 239, 230, 56), "ACINQ")
  val localNode: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"035912832c3eea544dc1c1bd4569f3f1f4ef58887c4df88fa17a899c84f093e3e6"), NodeAddress.unresolved(56175, host = 192, 168, 2, 11), "localnode")

  val syncNodes: Set[RemoteNodeInfo] = Set(satm, sts, bCashIsTrash, silentBob, acinq, localNode)
  val phcSyncNodes: Set[RemoteNodeInfo] = Set.empty // Set(satm, sts) - disabled until later

  val maxPHCCapacity: MilliSatoshi = MilliSatoshi(100000000000000L) // PHC can not be larger than 1000 BTC
  val minPHCCapacity: MilliSatoshi = MilliSatoshi(1000000000L) // PHC can not be smaller than 0.01 BTC
  val minNormalChansForPHC = 5 // How many normal chans a node must have to be eligible for PHCs
  val maxPHCPerNode = 3 // How many PHCs a node can have in total

  val minCapacity: MilliSatoshi = MilliSatoshi(750000000L) // 750k sat
  val maxNodesToSyncFrom = 1 // How many disjoint peers to use for majority sync
  val acceptThreshold = 0 // ShortIds and updates are accepted if confirmed by more than this peers
  val messagesToAsk = 400 // Ask for this many messages from peer before they say this chunk is done
  val chunksToWait = 4 // Wait for at least this much chunk iterations from any peer before recording results
}

class TestNet4SyncParams extends SyncParams {
  val atomiq: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"024c6e1edd12f0792d0c1ddda3abc6e2fde6bf89f2848e00cf8d6a58fabb6c3ab6"), NodeAddress.unresolved(9735, host = 81, 17, 102, 136), "Atomiq")
  val noname0: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0264fa477e2bb4e8c1eadf20eb4408c28026be7b640be3532b8f292bb719268f84"), NodeAddress.unresolved(9735, host = 86, 104, 228, 145), "Noname0")
  val noname1: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"020d84fb6ad938545c15633b38db9e3d6dc34205295359611ae817fa0c417066f7"), NodeAddress.unresolved(9735, host = 54, 252, 10, 243), "Noname1")
  val noname2: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02f0bf82f730d2e68453cc612c3e7ca5e021eaa1ead8250a6380c551d1d43bdc1b"), NodeAddress.unresolved(9735, host = 109, 123, 236, 96), "Noname2")
  override val localNode: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"022b2053758559dbfd4dee6b89067cd17d37ec7e26edb2f7b58baac501add4b72b"), NodeAddress.unresolved(9735, host = 192, 168, 2, 11), "localhost")
  override val syncNodes: Set[RemoteNodeInfo] = Set(atomiq, noname0, noname1, noname2)
  override val phcSyncNodes: Set[RemoteNodeInfo] = Set.empty
  override val minCapacity: MilliSatoshi = MilliSatoshi(1000000L)
  override val minNormalChansForPHC = 1
  override val maxNodesToSyncFrom = 1
  override val acceptThreshold = 0
}

class TestNet3SyncParams extends SyncParams {
  val voltage: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02cf71da3f277c2a30a348dfced77a9b7d81fb578c2b9117967100352051626b84"), NodeAddress.unresolved(20402, host = 54, 214, 32, 132), "Voltage")
  val endurance: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"), NodeAddress.unresolved(9735, host = 13, 248, 222, 197), "Endurance")

  // nodes from Electrum repo
  val node1: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"038863cf8ab91046230f561cd5b386cbff8309fa02e3f0c3ed161a3aeb64a643b9"), NodeAddress.unresolved(9735, host = 203, 132, 95, 10), "Node 1")
  val node2: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03236a685d30096b26692dce0cf0fa7c8528bdf61dbf5363a3ef6d5c92733a3016"), NodeAddress.unresolved(9734, host = 50, 116, 3, 223), "Node 2")
  val node3: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03d5e17a3c213fe490e1b0c389f8cfcfcea08a29717d50a9f453735e0ab2a7c003"), NodeAddress.unresolved(9735, host = 3, 16, 119, 191), "Node 3")
  val node4: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"), NodeAddress.unresolved(9735, host = 34, 250, 234, 192), "Node 4")
  val node5: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0260d9119979caedc570ada883ff614c6efb93f7f7382e25d73ecbeba0b62df2d7"), NodeAddress.unresolved(9735, host = 88, 99, 209, 230), "Node 5")
  val node6: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"023ea0a53af875580899da0ab0a21455d9c19160c4ea1b7774c9d4be6810b02d2c"), NodeAddress.unresolved(9735, host = 160, 16, 233, 215), "Node 6")
  val node7: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0269a94e8b32c005e4336bfb743c08a6e9beb13d940d57c479d95c8e687ccbdb9f"), NodeAddress.unresolved(9735, host = 197, 155, 6, 173), "Node 7")
  val node8: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"030f0bf260acdbd3edcad84d7588ec7c5df4711e87e6a23016f989b8d3a4147230"), NodeAddress.unresolved(9735, host = 163, 172, 94, 64), "Node 8")
  val node9: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02312627fdf07fbdd7e5ddb136611bdde9b00d26821d14d94891395452f67af248"), NodeAddress.unresolved(9735, host = 23, 237, 77, 12), "Node 9")
  val node10: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02ae2f22b02375e3e9b4b4a2db4f12e1b50752b4062dbefd6e01332acdaf680379"), NodeAddress.unresolved(9735, host = 197, 155, 6, 172), "Node 10")
  val node11: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"034fe52e98a0e9d3c21b767e1b371881265d8c7578c21f5afd6d6438da10348b36"), NodeAddress.unresolved(9740, host = 23, 239, 23, 44), "Node 11")

  // IPv6 nodes handled using fromParts
  val node12: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02889be42fc32093d2dcbfa59369df262e3577b333d8a45e5859dcdd6a4139839a"), NodeAddress.fromParts("2a09:8280:1::42:a6f3", 9735), "Node 12")
  val node13: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"021713d5331898c206b57c4f7d40635079de9a97d97782646f31dac18a53f2d979"), NodeAddress.fromParts("2a09:8280:1::15:a57c", 9735), "Node 13")

  override val localNode: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"022b2053758559dbfd4dee6b89067cd17d37ec7e26edb2f7b58baac501add4b72b"), NodeAddress.unresolved(9735, host = 192, 168, 2, 11), "localhost")

  override val syncNodes: Set[RemoteNodeInfo] = Set(
    endurance, localNode, voltage,
    node1, node2, node3, node4, node5, node6,
    node7, node8, node9, node10, node11, node12, node13, localNode
  )

  override val phcSyncNodes: Set[RemoteNodeInfo] = Set.empty
  override val minCapacity: MilliSatoshi = MilliSatoshi(10000000L)
  override val minNormalChansForPHC = 1
  override val maxNodesToSyncFrom = 1
  override val acceptThreshold = 0
}

class SignetSyncParams extends SyncParams {
  // nodes from Electrum repo
  val node1: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02357a375a846279fc1e8413f5e182652a125e5f6a4f4653bffabebb8177a6d8aa"), NodeAddress.unresolved(9735, host = 34, 68, 95, 152), "Signet Node 1")
  val node2: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0305061295fa30847df41ae6ee809b560e78d65c2a7337a41c725ea3920b65e08a"), NodeAddress.unresolved(9735, host = 34, 124, 125, 201), "Signet Node 2")
  val node3: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"027554f8d4d99a43cf1b49d274f698ee5045273cd377206eba62ea308b4386a4fa"), NodeAddress.unresolved(9735, host = 35, 247, 14, 99), "Signet Node 3")
  val node4: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0244bb7ba2392ab2d493ad04ad4afcd482ca44a2bfe5b42bcc830bfe00e5b08082"), NodeAddress.unresolved(9735, host = 34, 138, 100, 228), "Signet Node 4")
  val node5: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03adf6efe5346d455172c750a655b07fb85be4f50f5b555f9f91a853a6b448c3bf"), NodeAddress.unresolved(9735, host = 34, 74, 81, 232), "Signet Node 5")
  val node6: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03ea42c9408a73dabdcb5655e2923956d132fbb25cb71e7c00a29e10c73e937e64"), NodeAddress.unresolved(9735, host = 34, 138, 237, 159), "Signet Node 6")
  val node7: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"024d899b60d5de58e8d66af042445323a48b6962d6c667c033802421dc49abc232"), NodeAddress.unresolved(9735, host = 34, 75, 211, 29), "Signet Node 7")
  val node8: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02e8430ba207ce87bd2d4ab36497b9eac10e6d5d86f9fda8aa270c48877e0a8259"), NodeAddress.unresolved(9735, host = 34, 73, 252, 102), "Signet Node 8")
  val node9: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0265ed138065b84d6b9448f9e0a2fd4ceb63fef08efe1dfc949a63d5d43110e4c0"), NodeAddress.unresolved(39735, host = 175, 45, 182, 145), "Signet Node 9")
  val node10: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0307238136c48cd35084c4efadc486143a7e8a7acd8ff8ac053fdab4efabc551c4"), NodeAddress.unresolved(9735, host = 104, 244, 73, 68), "Signet Node 10")
  val node11: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"020ee56ff81d12d17d5d3eea5306a8982a5763522ca73e0e220ce282030543c90c"), NodeAddress.unresolved(44149, host = 84, 247, 50, 180), "Signet Node 11")

  // Note: NodeAddress.unresolved(port, host = a,b,c,d) is strictly for IPv4. For domains, fromParts is typically used.
  val signetEclair: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0271cf3881e6eadad960f47125434342e57e65b98a78afa99f9b4191c02dd7ab3b"), NodeAddress.fromParts("signet-eclair.wakiyamap.dev", 9735), "Signet Eclair")

  // Preserved exactly from your TestNet3SyncParams
  override val localNode: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"022b2053758559dbfd4dee6b89067cd17d37ec7e26edb2f7b58baac501add4b72b"), NodeAddress.unresolved(9735, host = 192, 168, 2, 11), "localhost")

  override val syncNodes: Set[RemoteNodeInfo] = Set(
    node1, node2, node3, node4, node5, node6,
    node7, node8, node9, node10, node11, signetEclair, localNode
  )

  override val phcSyncNodes: Set[RemoteNodeInfo] = Set.empty
  override val minCapacity: MilliSatoshi = MilliSatoshi(10000000L)
  override val minNormalChansForPHC = 1
  override val maxNodesToSyncFrom = 1
  override val acceptThreshold = 0
}

class RegtestSyncParams extends SyncParams {
  override val syncNodes: Set[RemoteNodeInfo] = Set.empty
  override val phcSyncNodes: Set[RemoteNodeInfo] = Set.empty
  override val minCapacity: MilliSatoshi = MilliSatoshi(1000L)
  override val minNormalChansForPHC = 1
  override val maxNodesToSyncFrom = 1
  override val acceptThreshold = 0
}

// Important: LNParams.secret must be defined
case class RemoteNodeInfo(nodeId: PublicKey, address: NodeAddress, alias: String) {
  lazy val nodeSpecificExtendedKey: DeterministicWallet.ExtendedPrivateKey = LNParams.secret.keys.ourFakeNodeIdKey(nodeId)
  lazy val nodeSpecificPair: KeyPairAndPubKey = KeyPairAndPubKey(KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value), nodeId)
  lazy val nodeSpecificPrivKey: PrivateKey = nodeSpecificExtendedKey.privateKey
  lazy val nodeSpecificPubKey: PublicKey = nodeSpecificPrivKey.publicKey
  def safeAlias: RemoteNodeInfo = copy(alias = alias take 24)
}

case class WalletSecret(keys: LightningNodeKeys, mnemonic: List[String], seed: ByteVector)
case class UpdateAddHtlcExt(theirAdd: UpdateAddHtlc, remoteInfo: RemoteNodeInfo)
case class SwapInStateExt(state: SwapInState, nodeId: PublicKey)

// Interfaces

trait NetworkBag {
  def addChannelAnnouncement(ca: ChannelAnnouncement, newSqlPQ: PreparedQuery)
  def addChannelUpdateByPosition(cu: ChannelUpdate, newSqlPQ: PreparedQuery, updSqlPQ: PreparedQuery)
  // When adding an excluded channel we disregard an update position: channel as a whole is always excluded
  def addExcludedChannel(shortId: Long, untilStamp: Long, newSqlPQ: PreparedQuery)
  def removeChannelUpdate(shortId: Long, killSqlPQ: PreparedQuery)

  def addChannelUpdateByPosition(cu: ChannelUpdate)
  def removeChannelUpdate(shortId: Long)

  def listChannelAnnouncements: Iterable[ChannelAnnouncement]
  def listChannelUpdates: Iterable[ChannelUpdateExt]
  def listChannelsWithOneUpdate: ShortChanIdSet
  def listExcludedChannels: Set[Long]

  def incrementScore(cu: ChannelUpdateExt)
  def getRoutingData: Map[Long, PublicChannel]
  def removeGhostChannels(ghostIds: ShortChanIdSet, oneSideIds: ShortChanIdSet)
  def processCompleteHostedData(pure: CompleteHostedRoutingData)
  def processPureData(data: PureRoutingData)
}

// Bag of stored payments and successful relays

trait PaymentBag {
  def getPreimage(hash: ByteVector32): Try[ByteVector32]
  def setPreimage(paymentHash: ByteVector32, preimage: ByteVector32)
  def addRelayedPreimageInfo(fullTag: FullPaymentTag, preimage: ByteVector32, relayed: MilliSatoshi, earned: MilliSatoshi)

  def addSearchablePayment(search: String, paymentHash: ByteVector32)
  def searchPayments(rawSearchQuery: String): RichCursor

  def replaceOutgoingPayment(prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction],
                             finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc,
                             chainFee: MilliSatoshi, seenAt: Long)

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc)

  def getPaymentInfo(paymentHash: ByteVector32): Try[PaymentInfo]
  def removePaymentInfo(paymentHash: ByteVector32)

  def updDescription(description: PaymentDescription, paymentHash: ByteVector32)
  def updOkIncoming(receivedAmount: MilliSatoshi, paymentHash: ByteVector32)
  def updOkOutgoing(fulfill: RemoteFulfill, fee: MilliSatoshi)
  def updAbortedOutgoing(paymentHash: ByteVector32)

  def listRecentRelays(limit: Int): RichCursor
  def listRecentPayments(limit: Int): RichCursor
  def listPendingSecrets: Iterable[ByteVector32]

  def paymentSummary: Try[PaymentSummary]
  def relaySummary: Try[RelaySummary]

  def toRelayedPreimageInfo(rc: RichCursor): RelayedPreimageInfo
  def toPaymentInfo(rc: RichCursor): PaymentInfo
}

trait DataBag {
  def putSecret(secret: WalletSecret)
  def tryGetSecret: Try[WalletSecret]

  def putFiatRatesInfo(data: FiatRatesInfo)
  def tryGetFiatRatesInfo: Try[FiatRatesInfo]

  def putFeeRatesInfo(data: FeeRatesInfo)
  def tryGetFeeRatesInfo: Try[FeeRatesInfo]

  def putReport(paymentHash: ByteVector32, report: String)
  def tryGetReport(paymentHash: ByteVector32): Try[String]

  def putBranding(nodeId: PublicKey, branding: HostedChannelBranding)
  def tryGetBranding(nodeId: PublicKey): Try[HostedChannelBranding]

  def putSwapInState(nodeId: PublicKey, state: SwapInState)
  def tryGetSwapInState(nodeId: PublicKey): Try[SwapInStateExt]
}

object ChannelBag {
  case class Hash160AndCltv(hash160: ByteVector, cltvExpiry: CltvExpiry)
}

trait ChannelBag {
  val db: DBInterface
  def all: Iterable[PersistentChannelData]
  def put(data: PersistentChannelData): PersistentChannelData
  def delete(channelId: ByteVector32)

  def htlcInfos(commitNumer: Long): Iterable[ChannelBag.Hash160AndCltv]
  def putHtlcInfo(sid: Long, commitNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry)
  def putHtlcInfos(htlcs: Seq[DirectedHtlc], sid: Long, commitNumber: Long)
  def rmHtlcInfos(sid: Long)

  def channelTxFeesSummary: Try[ChannelTxFeesSummary]
  def addChannelTxFee(feePaid: Satoshi, idenitifer: String, tag: String)
}
