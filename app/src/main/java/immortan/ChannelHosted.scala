package immortan

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.{ByteVector64, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.HashToPreimage
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import immortan.Channel._
import immortan.ErrorCodes._
import immortan.crypto.Tools._
import immortan.fsm.PreimageCheck
import scodec.bits.ByteVector

import scala.collection.mutable


object ChannelHosted {
  def make(initListeners: Set[ChannelListener], hostedData: HostedCommits, bag: ChannelBag): ChannelHosted = new ChannelHosted {
    def SEND(msgs: LightningMessage*): Unit = CommsTower.sendMany(msgs.map(LightningMessageCodecs.prepareNormal), hostedData.remoteInfo.nodeSpecificPair)

    def STORE(hostedData: PersistentChannelData): PersistentChannelData = bag.put(hostedData)

    listeners = initListeners
    doProcess(hostedData)
  }

  def restoreCommits(localLCSS: LastCrossSignedState, remoteInfo: RemoteNodeInfo): HostedCommits = {
    val inFlightHtlcs = localLCSS.incomingHtlcs.map(IncomingHtlc) ++ localLCSS.outgoingHtlcs.map(OutgoingHtlc)
    HostedCommits(remoteInfo.safeAlias, CommitmentSpec(feeratePerKw = FeeratePerKw(0L.sat), localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat, inFlightHtlcs.toSet),
      localLCSS, nextLocalUpdates = Nil, nextRemoteUpdates = Nil, updateOpt = None, postErrorOutgoingResolvedIds = Set.empty, localError = None, remoteError = None)
  }
}

abstract class ChannelHosted extends Channel { me =>
  //var externalPaymentListeners: mutable.Set[ExternalPaymentListener] = mutable.Set.empty

  def isOutOfSync(blockDay: Long): Boolean = math.abs(blockDay - LNParams.currentBlockDay) > 1

  def doProcess(change: Any): Unit = Tuple3(data, change, state) match {
    case (wait: WaitRemoteHostedReply, CMD_SOCKET_ONLINE, WAIT_FOR_INIT) =>
      me SEND InvokeHostedChannel(LNParams.chainHash, wait.refundScriptPubKey, wait.secret, wait.ticker)
      BECOME(wait, WAIT_FOR_ACCEPT)


    case (WaitRemoteHostedReply(remoteInfo, refundScriptPubKey, _, _), init: InitHostedChannel, WAIT_FOR_ACCEPT) =>
      if (init.initialClientBalanceMsat > init.channelCapacityMsat) throw new RuntimeException(s"Their init balance for us=${init.initialClientBalanceMsat}, is larger than capacity")
      if (UInt64(100000000L) > init.maxHtlcValueInFlightMsat) throw new RuntimeException(s"Their max value in-flight=${init.maxHtlcValueInFlightMsat}, is too low")
      if (init.htlcMinimumMsat > 546000L.msat) throw new RuntimeException(s"Their minimal payment size=${init.htlcMinimumMsat}, is too high")
      if (init.maxAcceptedHtlcs < 1) throw new RuntimeException("They can accept too few in-flight payments")
      // TODO: add server fiat rate check here !!!

      val lcss = LastCrossSignedState(isHost = false, refundScriptPubKey, init, LNParams.currentBlockDay, init.initialClientBalanceMsat,
        init.channelCapacityMsat - init.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L, rate = init.initialRate, incomingHtlcs = Nil, outgoingHtlcs = Nil,
        localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(remoteInfo.nodeSpecificPrivKey)

      val localHalfSignedHC = ChannelHosted.restoreCommits(lcss, remoteInfo)
      BECOME(WaitRemoteHostedStateUpdate(remoteInfo, localHalfSignedHC), WAIT_FOR_ACCEPT)
      SEND(localHalfSignedHC.lastCrossSignedState.stateUpdate)


    case (WaitRemoteHostedStateUpdate(_, localHalfSignedHC), remoteSU: StateUpdate, WAIT_FOR_ACCEPT) =>
      val localCompleteLCSS = localHalfSignedHC.lastCrossSignedState.copy(rate = remoteSU.rate, remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      val isRightRemoteUpdateNumber = localHalfSignedHC.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
      val isRightLocalUpdateNumber = localHalfSignedHC.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
      val isRemoteSigOk = localCompleteLCSS.verifyRemoteSig(localHalfSignedHC.remoteInfo.nodeId)
      val askBrandingInfo = AskBrandingInfo(localHalfSignedHC.channelId)
      val isBlockDayWrong = isOutOfSync(remoteSU.blockDay)

      if (isBlockDayWrong) throw new RuntimeException("Their blockday is wrong")
      if (!isRemoteSigOk) throw new RuntimeException("Their signature is wrong")
      if (!isRightLocalUpdateNumber) throw new RuntimeException("Their local update number is wrong")
      if (!isRightRemoteUpdateNumber) throw new RuntimeException("Their remote update number is wrong")
      StoreBecomeSend(localHalfSignedHC.copy(lastCrossSignedState = localCompleteLCSS), OPEN, askBrandingInfo)


    case (wait: WaitRemoteHostedReply, remoteLCSS: LastCrossSignedState, WAIT_FOR_ACCEPT) =>
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(wait.remoteInfo.nodeSpecificPubKey)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(wait.remoteInfo.nodeId)
      val hc = ChannelHosted.restoreCommits(remoteLCSS.reverse, wait.remoteInfo)
      val askBrandingInfo = AskBrandingInfo(hc.channelId)

      if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
      else if (!isLocalSigOk) localSuspend(hc, ERR_HOSTED_WRONG_LOCAL_SIG)
      else {
        // We have expected InitHostedChannel but got LastCrossSignedState so this channel exists already
        // make sure our signature match and if so then become OPEN using host supplied state data
        StoreBecomeSend(hc, OPEN, hc.lastCrossSignedState, askBrandingInfo)
        // Remote LCSS could contain pending incoming
        events.notifyResolvers
      }

    // CHANNEL IS ESTABLISHED

    case (hc: HostedCommits, CurrentBlockCount(tip), OPEN | SLEEPING) =>
      // Keep in mind that we may have many outgoing HTLCs which have the same preimage
      val sentExpired = hc.allOutgoing.filter(tip > _.cltvExpiry.underlying).groupBy(_.paymentHash)
      val hasReceivedRevealedExpired = hc.revealedFulfills.exists(tip > _.theirAdd.cltvExpiry.underlying)

      if (hasReceivedRevealedExpired) {
        // We have incoming payments for which we have revealed a preimage but they are still unresolved and completely expired
        // unless we have published a preimage on chain we can not prove we have revealed a preimage in time at this point
        // at the very least it makes sense to halt further usage of this potentially malicious channel
        localSuspend(hc, ERR_HOSTED_MANUAL_SUSPEND)
      }

      if (sentExpired.nonEmpty) {
        val checker = new PreimageCheck {
          override def onComplete(hash2preimage: HashToPreimage): Unit = {
            val settledOutgoingHtlcIds: Iterable[Long] = sentExpired.values.flatten.map(_.id)
            val (fulfilled, failed) = sentExpired.values.flatten.partition(add => hash2preimage contains add.paymentHash)
            localSuspend(hc.modify(_.postErrorOutgoingResolvedIds).using(_ ++ settledOutgoingHtlcIds), ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC)
            for (add <- fulfilled) events fulfillReceived RemoteFulfill(theirPreimage = hash2preimage(add.paymentHash), ourAdd = add)
            for (add <- failed) events addRejectedLocally InPrincipleNotSendable(localAdd = add)
            // There will be no state update
            events.notifyResolvers
          }
        }

        // Our peer might have published a preimage on chain instead of directly sending it to us
        // if it turns out that preimage is not present on chain at this point we can safely fail an HTLC
        checker process PreimageCheck.CMDStart(sentExpired.keySet, LNParams.syncParams.phcSyncNodes)
      }


    case (hc: HostedCommits, theirAdd: UpdateAddHtlc, OPEN) if hc.error.isEmpty =>
      val theirAddExt = UpdateAddHtlcExt(theirAdd, hc.remoteInfo)
      BECOME(hc.receiveAdd(theirAdd), OPEN)
      events addReceived theirAddExt


    case (hc: HostedCommits, msg: UpdateFailHtlc, OPEN) if hc.error.isEmpty => receiveHtlcFail(hc, msg, msg.id)
    case (hc: HostedCommits, msg: UpdateFailMalformedHtlc, OPEN) if hc.error.isEmpty => receiveHtlcFail(hc, msg, msg.id)


    case (hc: HostedCommits, msg: UpdateFulfillHtlc, OPEN | SLEEPING) if hc.error.isEmpty =>
      val remoteFulfill = hc.makeRemoteFulfill(msg)
      BECOME(hc.addRemoteProposal(msg), state)
      events fulfillReceived remoteFulfill


    case (hc: HostedCommits, msg: UpdateFulfillHtlc, OPEN | SLEEPING) if hc.error.isDefined =>
      // We may get into error state with this HTLC not expired yet so they may fulfill it afterwards
      val hc1 = hc.modify(_.postErrorOutgoingResolvedIds).using(_ + msg.id)
      // This will throw if HTLC has already been settled post-error
      val remoteFulfill = hc.makeRemoteFulfill(msg)
      BECOME(hc1.addRemoteProposal(msg), state)
      events fulfillReceived remoteFulfill
      // There will be no state update
      events.notifyResolvers


    case (hc: HostedCommits, CMD_SIGN, OPEN) if (hc.nextLocalUpdates.nonEmpty || hc.resizeProposal.isDefined || hc.marginProposal.isDefined) && hc.error.isEmpty =>
      val hc1 = hc.marginProposal.map(hc.withMargin).getOrElse(hc.resizeProposal.map(hc.withResize).getOrElse(hc))
      val nextLocalLCSS = hc1.nextLocalUnsignedLCSS(LNParams.currentBlockDay)
      SEND(nextLocalLCSS.withLocalSigOfRemote(hc.remoteInfo.nodeSpecificPrivKey).stateUpdate)


    // First attempt a normal state update, then a resized state update if original signature check fails and we have a pending resize proposal
    case (hc: HostedCommits, remoteSU: StateUpdate, OPEN) if (remoteSU.localSigOfRemoteLCSS != hc.lastCrossSignedState.remoteSigOfLocal) && hc.error.isEmpty =>
      attemptStateUpdate(remoteSU, hc)

    case (hc: HostedCommits, msg: ReplyCurrentRate, OPEN) =>
      println(s"Got new server rate ${msg.rate}")
      val hc1 = hc.copy(currentHostRate = msg.rate)
      STORE(hc1)
      BECOME(hc1, state)
      hc1.nextMarginResize(hc1.currentHostRate) match {
        case Some(cmd) => {
          println(s"Trying to send margin request for capacity=${cmd.newCapacity} and rate=${cmd.newRate} when current balance is ${hc1.reserveSats} and old rate ${hc1.currentRate}")
          process(cmd)
        }
        case None => ()
      }
      events.notifyResolvers

//    case (hc: HostedCommits, msg: ProposeInvoice, OPEN) =>
//      println(s"Got poposal with description ${msg.description} and invoice: ${msg.invoice}")
//      for (lst <- externalPaymentListeners) lst.onPaymentRequest(msg.description, msg.invoice)

    case (hc: HostedCommits, CMD_HOSTED_QUERY_RATE(), OPEN) =>
      println("Requesting server rate")
      SEND(QueryCurrentRate())

    case (hc: HostedCommits, cmd: CMD_ADD_HTLC, OPEN | SLEEPING) =>
      hc.sendAdd(cmd, blockHeight = LNParams.blockCount.get) match {
        case _ if hc.error.isDefined => events addRejectedLocally ChannelNotAbleToSend(cmd.incompleteAdd)
        case _ if SLEEPING == state => events addRejectedLocally ChannelOffline(cmd.incompleteAdd)
        case Left(reason) => events addRejectedLocally reason

        case Right(hc1 ~ updateAddHtlcMsg) =>
          StoreBecomeSend(hc1, OPEN, updateAddHtlcMsg)
          process(CMD_SIGN)
      }


    case (_, cmd: CMD_ADD_HTLC, _) =>
      // Instruct upstream to skip this channel in such a state
      val reason = ChannelNotAbleToSend(cmd.incompleteAdd)
      events addRejectedLocally reason


    // Fulfilling is allowed even in error state
    // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
    case (hc: HostedCommits, cmd: CMD_FULFILL_HTLC, OPEN) if hc.nextLocalSpec.findIncomingHtlcById(cmd.theirAdd.id).isDefined =>
      val msg = UpdateFulfillHtlc(hc.channelId, cmd.theirAdd.id, cmd.preimage)
      StoreBecomeSend(hc.addLocalProposal(msg), OPEN, msg)


    // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
    case (hc: HostedCommits, cmd: CMD_FAIL_HTLC, OPEN) if hc.nextLocalSpec.findIncomingHtlcById(cmd.theirAdd.id).isDefined && hc.error.isEmpty =>
      val msg = OutgoingPaymentPacket.buildHtlcFailure(cmd, theirAdd = cmd.theirAdd)
      StoreBecomeSend(hc.addLocalProposal(msg), OPEN, msg)


    // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
    case (hc: HostedCommits, cmd: CMD_FAIL_MALFORMED_HTLC, OPEN) if hc.nextLocalSpec.findIncomingHtlcById(cmd.theirAdd.id).isDefined && hc.error.isEmpty =>
      val msg = UpdateFailMalformedHtlc(hc.channelId, cmd.theirAdd.id, cmd.onionHash, cmd.failureCode)
      StoreBecomeSend(hc.addLocalProposal(msg), OPEN, msg)


    case (hc: HostedCommits, CMD_SOCKET_ONLINE, SLEEPING) =>
      val origRefundPubKey = hc.lastCrossSignedState.refundScriptPubKey
      val ticker = hc.lastCrossSignedState.initHostedChannel.ticker
      val invokeMsg = InvokeHostedChannel(LNParams.chainHash, origRefundPubKey, ByteVector.empty, ticker)
      SEND(hc.error getOrElse invokeMsg)


    case (hc: HostedCommits, CMD_SOCKET_OFFLINE, OPEN) => BECOME(hc, SLEEPING)

    case (hc: HostedCommits, _: InitHostedChannel, SLEEPING) => SEND(hc.lastCrossSignedState)

    case (hc: HostedCommits, remoteLCSS: LastCrossSignedState, SLEEPING) if hc.error.isEmpty => attemptInitResync(hc, remoteLCSS)

    case (hc: HostedCommits, remoteInfo: RemoteNodeInfo, _) if hc.remoteInfo.nodeId == remoteInfo.nodeId => StoreBecomeSend(hc.copy(remoteInfo = remoteInfo.safeAlias), state)


    case (hc: HostedCommits, update: ChannelUpdate, OPEN | SLEEPING) if hc.updateOpt.forall(_.core != update.core) && hc.error.isEmpty =>
      val shortIdMatches = hostedShortChanId(hc.remoteInfo.nodeSpecificPubKey.value, hc.remoteInfo.nodeId.value, hc.lastCrossSignedState.initHostedChannel.ticker) == update.shortChannelId
      if (shortIdMatches) StoreBecomeSend(hc.copy(updateOpt = update.asSome), state)


    case (hc: HostedCommits, cmd: HC_CMD_RESIZE, OPEN | SLEEPING) if hc.resizeProposal.isEmpty && hc.error.isEmpty =>
      val capacitySat = hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat.truncateToSatoshi
      val resize = ResizeChannel(capacitySat + cmd.delta).sign(hc.remoteInfo.nodeSpecificPrivKey)
      StoreBecomeSend(hc.copy(resizeProposal = resize.asSome, marginProposal = None), state, resize)
      process(CMD_SIGN)

    case (hc: HostedCommits, cmd: HC_CMD_MARGIN, OPEN | SLEEPING) if hc.marginProposal.isEmpty && hc.error.isEmpty =>
      val margin = MarginChannel(cmd.newCapacity, cmd.newRate).sign(hc.remoteInfo.nodeSpecificPrivKey)
      StoreBecomeSend(hc.copy(marginProposal = margin.asSome, resizeProposal = None), state, margin)
      process(CMD_SIGN)

    case (hc: HostedCommits, resize: ResizeChannel, OPEN | SLEEPING) if hc.resizeProposal.isEmpty && hc.error.isEmpty =>
      // Can happen if we have sent a resize earlier, but then lost channel data and restored from their
      val isLocalSigOk: Boolean = resize.verifyClientSig(hc.remoteInfo.nodeSpecificPubKey)
      if (isLocalSigOk) StoreBecomeSend(hc.copy(resizeProposal = resize.asSome), state)
      else localSuspend(hc, ERR_HOSTED_INVALID_RESIZE)

    case (hc: HostedCommits, margin: MarginChannel, OPEN | SLEEPING) if hc.marginProposal.isEmpty && hc.error.isEmpty =>
      // Can happen if we have sent a margin resize earlier, but then lost channel data and restored from their
      val isLocalSigOk: Boolean = margin.verifyClientSig(hc.remoteInfo.nodeSpecificPubKey)
      if (isLocalSigOk) StoreBecomeSend(hc.copy(marginProposal = margin.asSome), state)
      else localSuspend(hc, ERR_HOSTED_INVALID_MARGIN)

    case (hc: HostedCommits, remoteSO: StateOverride, OPEN | SLEEPING) if hc.error.isDefined && !hc.overrideProposal.contains(remoteSO) =>
      StoreBecomeSend(hc.copy(overrideProposal = remoteSO.asSome), state)


    case (hc: HostedCommits, cmd @ CMD_HOSTED_STATE_OVERRIDE(remoteSO), OPEN | SLEEPING) if hc.error.isDefined =>
      val overriddenLocalBalance = hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat
      val completeLocalLCSS = hc.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil, localBalanceMsat = overriddenLocalBalance,
        remoteBalanceMsat = remoteSO.localBalanceMsat, rate = remoteSO.rate, localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates, blockDay = remoteSO.blockDay,
        remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS).withLocalSigOfRemote(hc.remoteInfo.nodeSpecificPrivKey)

      val isRemoteSigOk = completeLocalLCSS.verifyRemoteSig(hc.remoteInfo.nodeId)
      val hc1 = ChannelHosted.restoreCommits(completeLocalLCSS, hc.remoteInfo)

      if (completeLocalLCSS.localBalanceMsat < 0L.msat) throw CMDException("Override impossible: new local balance is larger than capacity", cmd)
      if (remoteSO.localUpdates < hc.lastCrossSignedState.remoteUpdates) throw CMDException("Override impossible: new local update number from remote host is wrong", cmd)
      if (remoteSO.remoteUpdates < hc.lastCrossSignedState.localUpdates) throw CMDException("Override impossible: new remote update number from remote host is wrong", cmd)
      if (remoteSO.blockDay < hc.lastCrossSignedState.blockDay) throw CMDException("Override impossible: new override blockday from remote host is not acceptable", cmd)
      if (!isRemoteSigOk) throw CMDException("Override impossible: new override signature from remote host is wrong", cmd)
      StoreBecomeSend(hc1, OPEN, completeLocalLCSS.stateUpdate)
      rejectOverriddenOutgoingAdds(hc, hc1)
      // We may have pendig incoming
      events.notifyResolvers

    case (hc: HostedCommits, remote: Fail, WAIT_FOR_ACCEPT | OPEN) if hc.remoteError.isEmpty =>
      StoreBecomeSend(data1 = hc.copy(remoteError = remote.asSome), OPEN)
      throw RemoteErrorException(ErrorExt extractDescription remote)


    case (_, remote: Fail, _) =>
      // Convert remote error to local exception, it will be dealt with upstream
      throw RemoteErrorException(ErrorExt extractDescription remote)


    case (null, wait: WaitRemoteHostedReply, -1) => super.become(wait, WAIT_FOR_INIT)
    case (null, hc: HostedCommits, -1) => super.become(hc, SLEEPING)
    case _ =>
  }

  def rejectOverriddenOutgoingAdds(hc: HostedCommits, hc1: HostedCommits): Unit =
    for (add <- hc.allOutgoing -- hc1.allOutgoing) events addRejectedLocally InPrincipleNotSendable(add)

  def localSuspend(hc: HostedCommits, errCode: String): Unit = {
    val localError = Fail(data = ByteVector.fromValidHex(errCode), channelId = hc.channelId)
    println(s"Local suspending due the ${localError}")
    if (hc.localError.isEmpty) StoreBecomeSend(hc.copy(localError = localError.asSome), state, localError)
  }

  def attemptInitResync(hc: HostedCommits, remoteLCSS: LastCrossSignedState): Unit = {
    val hc1 = hc.resizeProposal.filter(_ isRemoteResized remoteLCSS).map(hc.withResize).getOrElse(hc) // They may have a resized LCSS
    val hc2 = hc1.marginProposal.filter(_ isRemoteMargined remoteLCSS).map(hc1.withMargin).getOrElse(hc1) // They may have a margined LCSS
    val weAreEven = hc2.lastCrossSignedState.remoteUpdates == remoteLCSS.localUpdates && hc2.lastCrossSignedState.localUpdates == remoteLCSS.remoteUpdates
    val weAreAhead = hc2.lastCrossSignedState.remoteUpdates > remoteLCSS.localUpdates || hc2.lastCrossSignedState.localUpdates > remoteLCSS.remoteUpdates
    val isLocalSigOk = remoteLCSS.verifyRemoteSig(hc2.remoteInfo.nodeSpecificPubKey)
    val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(hc2.remoteInfo.nodeId)

    if (!isRemoteSigOk) localSuspend(hc2, ERR_HOSTED_WRONG_REMOTE_SIG)
    else if (!isLocalSigOk) localSuspend(hc2, ERR_HOSTED_WRONG_LOCAL_SIG)
    else if (weAreAhead || weAreEven) {
      SEND(List(hc.lastCrossSignedState) ++ hc2.resizeProposal ++ hc2.marginProposal ++ hc2.nextLocalUpdates:_*)
      // Forget about their unsigned updates, they are expected to resend
      BECOME(hc2.copy(nextRemoteUpdates = Nil), OPEN)
      // There will be no state update
      events.notifyResolvers
    } else {
      val localUpdatesAcked = remoteLCSS.remoteUpdates - hc2.lastCrossSignedState.localUpdates
      val remoteUpdatesAcked = remoteLCSS.localUpdates - hc2.lastCrossSignedState.remoteUpdates

      val remoteUpdatesAccounted = hc2.nextRemoteUpdates take remoteUpdatesAcked.toInt
      val localUpdatesAccounted = hc2.nextLocalUpdates take localUpdatesAcked.toInt
      val localUpdatesLeftover = hc2.nextLocalUpdates drop localUpdatesAcked.toInt

      val hc3 = hc2.copy(nextLocalUpdates = localUpdatesAccounted, nextRemoteUpdates = remoteUpdatesAccounted)
      val syncedLCSS = hc3.nextLocalUnsignedLCSS(remoteLCSS.blockDay).copy(localSigOfRemote = remoteLCSS.remoteSigOfLocal, remoteSigOfLocal = remoteLCSS.localSigOfRemote)

      if (syncedLCSS.reverse == remoteLCSS) {
        // We have fallen behind a bit but have all the data required to successfully synchronize such that an updated state is reached
        val hc4 = hc3.copy(lastCrossSignedState = syncedLCSS, localSpec = hc3.nextLocalSpec, nextLocalUpdates = localUpdatesLeftover, nextRemoteUpdates = Nil)
        StoreBecomeSend(hc4, OPEN, List(syncedLCSS) ++ hc3.resizeProposal ++ hc3.marginProposal ++ localUpdatesLeftover:_*)
      } else {
        // We are too far behind, restore from their future data, nothing else to do
        val hc4 = ChannelHosted.restoreCommits(remoteLCSS.reverse, hc3.remoteInfo)
        StoreBecomeSend(hc4, OPEN, remoteLCSS.reverse)
        rejectOverriddenOutgoingAdds(hc2, hc4)
      }

      // There will be no state update
      events.notifyResolvers
    }
  }

  def attemptStateUpdate(remoteSU: StateUpdate, hc: HostedCommits): Unit = {
    println(s"PLGN FC, attemptStateUpdate with ${remoteSU}")
    println(s"Channel old rate is ${hc.lastCrossSignedState.rate}")
    println(s"New server rate is ${remoteSU.rate}")
    val lcss1 = hc.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(rate = remoteSU.rate, remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS).withLocalSigOfRemote(hc.remoteInfo.nodeSpecificPrivKey)
    println(s"New channel rate is ${lcss1.rate}")
    val hc1 = hc.copy(lastCrossSignedState = lcss1, localSpec = hc.nextLocalSpec, nextLocalUpdates = Nil, nextRemoteUpdates = Nil)
    val isRemoteSigOk = lcss1.verifyRemoteSig(hc.remoteInfo.nodeId)
    val isBlockDayWrong = isOutOfSync(remoteSU.blockDay)

    if (isBlockDayWrong) {
      disconnectAndBecomeSleeping(hc)
    } else if (remoteSU.remoteUpdates < lcss1.localUpdates) {
      // Persist unsigned remote updates to use them on re-sync
      // we do not update runtime data because ours is newer one
      process(CMD_SIGN)
      me STORE hc
    } else if (!isRemoteSigOk) {
      hc.marginProposal.map(hc.withMargin) match {
        case Some(marginHC) => attemptStateUpdate(remoteSU, marginHC)
        case None => hc.resizeProposal.map(hc.withResize) match {
          case Some(resizedHC) => attemptStateUpdate(remoteSU, resizedHC)
          case None => localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
        }
      }
    } else {
      val remoteRejects: Seq[RemoteReject] = hc.nextRemoteUpdates.collect {
        case fail: UpdateFailHtlc => RemoteUpdateFail(fail, hc.localSpec.findOutgoingHtlcById(fail.id).get.add)
        case malform: UpdateFailMalformedHtlc => RemoteUpdateMalform(malform, hc.localSpec.findOutgoingHtlcById(malform.id).get.add)
      }

      StoreBecomeSend(hc1, OPEN, lcss1.stateUpdate)
      for (reject <- remoteRejects) events addRejectedRemotely reject
      events.notifyResolvers
    }
  }

  def receiveHtlcFail(hc: HostedCommits, msg: UpdateMessage, id: Long): Unit =
    hc.localSpec.findOutgoingHtlcById(id) match {
      case None if hc.nextLocalSpec.findOutgoingHtlcById(id).isDefined => disconnectAndBecomeSleeping(hc)
      case _ if hc.postErrorOutgoingResolvedIds.contains(id) => throw ChannelTransitionFail(hc.channelId, msg)
      case None => throw ChannelTransitionFail(hc.channelId, msg)
      case _ => BECOME(hc.addRemoteProposal(msg), OPEN)
    }

  def disconnectAndBecomeSleeping(hc: HostedCommits): Unit = {
    // Could have implemented a more involved partially-signed LCSS resolution
    // but for now we will just disconnect and resolve on reconnect if it gets too busy
    CommsTower.workers.get(hc.remoteInfo.nodeSpecificPair).foreach(_.disconnect)
    StoreBecomeSend(hc, SLEEPING)
  }
}
