package immortan

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools.{Any2Some, hostedChanId}
import immortan.utils.Rational
import scodec.bits.ByteVector


case class WaitRemoteHostedReply(remoteInfo: RemoteNodeInfo, refundScriptPubKey: ByteVector, secret: ByteVector, ticker: Ticker) extends ChannelData

case class WaitRemoteHostedStateUpdate(remoteInfo: RemoteNodeInfo, hc: HostedCommits) extends ChannelData

object HostedCommits {
  /// Defines a increase value for the channel capacity if we trying to increase it due the margin request
  val marginCapacityFactor: Double = 1.5
}

case class HostedCommits(remoteInfo: RemoteNodeInfo, localSpec: CommitmentSpec, lastCrossSignedState: LastCrossSignedState,
                         nextLocalUpdates: List[UpdateMessage], nextRemoteUpdates: List[UpdateMessage], updateOpt: Option[ChannelUpdate], postErrorOutgoingResolvedIds: Set[Long],
                         localError: Option[Fail], remoteError: Option[Fail], resizeProposal: Option[ResizeChannel] = None, marginProposal: Option[MarginChannel] = None, overrideProposal: Option[StateOverride] = None,
                         extParams: List[ExtParams] = Nil, startedAt: Long = System.currentTimeMillis, currentHostRate: MilliSatoshi = 0.msat) extends PersistentChannelData with Commitments { me =>

  lazy val error: Option[Fail] = localError.orElse(remoteError)

  lazy val nextTotalLocal: Long = lastCrossSignedState.localUpdates + nextLocalUpdates.size

  lazy val nextTotalRemote: Long = lastCrossSignedState.remoteUpdates + nextRemoteUpdates.size

  lazy val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  lazy val channelId: ByteVector32 = hostedChanId(remoteInfo.nodeSpecificPubKey.value, remoteInfo.nodeId.value, lastCrossSignedState.initHostedChannel.ticker)

  lazy val allOutgoing: Set[UpdateAddHtlc] = {
    val allOutgoingAdds = localSpec.outgoingAdds ++ nextLocalSpec.outgoingAdds
    allOutgoingAdds.filterNot(add => postErrorOutgoingResolvedIds contains add.id)
  }

  lazy val crossSignedIncoming: Set[UpdateAddHtlcExt] = for (theirAdd <- localSpec.incomingAdds) yield UpdateAddHtlcExt(theirAdd, remoteInfo)

  lazy val revealedFulfills: Set[LocalFulfill] = getPendingFulfills(Helpers extractRevealedPreimages nextLocalUpdates)

  lazy val maxSendInFlight: MilliSatoshi = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat.toMilliSatoshi

  lazy val minSendable: MilliSatoshi = lastCrossSignedState.initHostedChannel.htlcMinimumMsat

  lazy val availableForReceive: MilliSatoshi = nextLocalSpec.toRemote

  // Calculation from constant equation s1 / f1 = s2 / f2
  lazy val availableForSend: MilliSatoshi = reserveSats
  lazy val trueAvailableForSend: MilliSatoshi = MilliSatoshi(math round currentHostRate.toLong.toDouble / lastCrossSignedState.rate.toLong.toDouble * reserveSats.toLong.toDouble)

  lazy val reserveSats: MilliSatoshi = nextLocalSpec.toLocal

  lazy val fiatValue: Double = reserveSats.toLong.toDouble / lastCrossSignedState.rate.toLong.toDouble

  lazy val capacity: MilliSatoshi = lastCrossSignedState.initHostedChannel.channelCapacityMsat

  lazy val currentRate: MilliSatoshi = lastCrossSignedState.rate

  override def ourBalance: MilliSatoshi = availableForSend

  def averageRate(oldSats: MilliSatoshi, newSats: MilliSatoshi, oldRate: MilliSatoshi, newRate: MilliSatoshi): MilliSatoshi = {
    val f1 = 1.0 / oldRate.toLong.toDouble
    val f2 = 1.0 / newRate.toLong.toDouble
    val s1 = oldSats.toLong.toDouble
    val s2 = newSats.toLong.toDouble
    val d2 = s2 - s1
    if (s1 == 0 && s2 == 0) {
      println(s"averageRate: s1 and s2 are zero, so using server rate ${oldRate}")
      oldRate
    } else {
      val invRate = (s1 * f1 + d2 * f2) / s2
      println(s"averageRate: f1=${f1}, f2=${f2}, s1=${s1}, s2=${s2}, d2 = ${s2 - s1}, invRate = ${invRate}")
      MilliSatoshi(math round (1/invRate))
    }
  }

  def nextFiatMargin(newRate: MilliSatoshi) : MilliSatoshi = {
    MilliSatoshi((fiatValue * newRate.toLong.toDouble).round)
  }

  def nextMarginCapacity(newMargin: MilliSatoshi) : MilliSatoshi = {
    MilliSatoshi((HostedCommits.marginCapacityFactor * capacity.toLong.toDouble).round)
  }

  def nextMarginResize(newRate: MilliSatoshi) : Option[HC_CMD_MARGIN] = {
    if (currentHostRate.toLong > 0) {
      val newMargin = nextFiatMargin(newRate)
      if (newMargin > reserveSats) {
        val newCapacity = if (newMargin > capacity) {
          nextMarginCapacity(newMargin)
        } else {
          capacity
        }
        Some(HC_CMD_MARGIN(newCapacity.truncateToSatoshi, newRate))
      } else {
        None
      }
    } else {
      None
    }
  }

  def nextLocalUnsignedLCSSWithRate(blockDay: Long, newRate: MilliSatoshi): LastCrossSignedState = {
    val avgRate = averageRate(lastCrossSignedState.localBalanceMsat, nextLocalSpec.toLocal, lastCrossSignedState.rate, newRate)
    nextLocalUnsignedLCSS(blockDay).copy(rate = avgRate)
  }

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState =
    LastCrossSignedState(lastCrossSignedState.isHost, lastCrossSignedState.refundScriptPubKey, lastCrossSignedState.initHostedChannel,
      blockDay = blockDay, localBalanceMsat = nextLocalSpec.toLocal, remoteBalanceMsat = nextLocalSpec.toRemote, rate = lastCrossSignedState.rate, nextTotalLocal, nextTotalRemote,
      nextLocalSpec.incomingAdds.toList.sortBy(_.id), nextLocalSpec.outgoingAdds.toList.sortBy(_.id), localSigOfRemote = ByteVector64.Zeroes,
      remoteSigOfLocal = ByteVector64.Zeroes)

  def addLocalProposal(update: UpdateMessage): HostedCommits = copy(nextLocalUpdates = nextLocalUpdates :+ update)
  def addRemoteProposal(update: UpdateMessage): HostedCommits = copy(nextRemoteUpdates = nextRemoteUpdates :+ update)

  type UpdatedHCAndAdd = (HostedCommits, UpdateAddHtlc)
  def sendAdd(cmd: CMD_ADD_HTLC, blockHeight: Long): Either[LocalReject, UpdatedHCAndAdd] = {
    val completeAdd = cmd.incompleteAdd.copy(channelId = channelId, id = nextTotalLocal + 1)
    val commits1 = addLocalProposal(completeAdd)

    if (cmd.payload.amount < minSendable) return ChannelNotAbleToSend(cmd.incompleteAdd).asLeft
    if (CltvExpiry(blockHeight) >= cmd.cltvExpiry) return InPrincipleNotSendable(cmd.incompleteAdd).asLeft
    if (LNParams.maxCltvExpiryDelta.toCltvExpiry(blockHeight) < cmd.cltvExpiry) return InPrincipleNotSendable(cmd.incompleteAdd).asLeft
    if (commits1.nextLocalSpec.outgoingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) return ChannelNotAbleToSend(cmd.incompleteAdd).asLeft
    if (commits1.allOutgoing.foldLeft(0L.msat)(_ + _.amountMsat) > maxSendInFlight) return ChannelNotAbleToSend(cmd.incompleteAdd).asLeft
    if (commits1.nextLocalSpec.toLocal < 0L.msat) return ChannelNotAbleToSend(cmd.incompleteAdd).asLeft
    Right(commits1, completeAdd)
  }

  def receiveAdd(add: UpdateAddHtlc): HostedCommits = {
    val commits1: HostedCommits = addRemoteProposal(add)
    // We do not check whether total incoming amount exceeds maxHtlcValueInFlightMsat becase we always accept up to channel capacity
    if (commits1.nextLocalSpec.incomingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw ChannelTransitionFail(channelId, add)
    if (commits1.nextLocalSpec.toRemote < 0L.msat) throw ChannelTransitionFail(channelId, add)
    if (add.id != nextTotalRemote + 1) throw ChannelTransitionFail(channelId, add)
    commits1
  }

  // Relaxed constraints for receiveng preimages over HCs: we look at nextLocalSpec, not localSpec
  def makeRemoteFulfill(fulfill: UpdateFulfillHtlc): RemoteFulfill = nextLocalSpec.findOutgoingHtlcById(fulfill.id) match {
    case Some(ourAdd) if ourAdd.add.paymentHash != fulfill.paymentHash => throw ChannelTransitionFail(channelId, fulfill)
    case _ if postErrorOutgoingResolvedIds.contains(fulfill.id) => throw ChannelTransitionFail(channelId, fulfill)
    case Some(ourAdd) => RemoteFulfill(ourAdd.add, fulfill.paymentPreimage)
    case None => throw ChannelTransitionFail(channelId, fulfill)
  }

  def withResize(resize: ResizeChannel): HostedCommits =
    me.modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(resize.newCapacityMsatU64)
      .modify(_.lastCrossSignedState.initHostedChannel.channelCapacityMsat).setTo(resize.newCapacity.toMilliSatoshi)
      .modify(_.localSpec.toRemote).using(_ + resize.newCapacity - lastCrossSignedState.initHostedChannel.channelCapacityMsat)
      .modify(_.resizeProposal).setTo(None)

  def withMargin(margin: MarginChannel): HostedCommits =
    me.modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(margin.newCapacityMsatU64)
      .modify(_.lastCrossSignedState.initHostedChannel.channelCapacityMsat).setTo(margin.newCapacity.toMilliSatoshi)
      .modify(_.localSpec.toRemote).using(_ => margin.newCapacity - margin.newLocalBalance(lastCrossSignedState))
      .modify(_.localSpec.toLocal).using(_ => margin.newLocalBalance(lastCrossSignedState))
      .modify(_.lastCrossSignedState.rate).setTo(margin.newRate)
      .modify(_.marginProposal).setTo(None)
}