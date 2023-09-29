/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256, verifySignature}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId, serializationResult}
import scodec.bits.{BitVector, ByteVector}
import shapeless.HNil

import scala.concurrent.duration._


object Announcements {

  def channelAnnouncementWitnessEncode(chainHash: ByteVector32, shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, bitcoinKey1: PublicKey, bitcoinKey2: PublicKey, features: Features, unknownFields: ByteVector): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelAnnouncementWitnessCodec.encode(features :: chainHash :: shortChannelId :: nodeId1 :: nodeId2 :: bitcoinKey1 :: bitcoinKey2 :: unknownFields :: HNil))))

  def nodeAnnouncementWitnessEncode(timestamp: Long, nodeId: PublicKey, rgbColor: Color, alias: String, features: Features, addresses: List[NodeAddress], unknownFields: ByteVector): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.nodeAnnouncementWitnessCodec.encode(features :: timestamp :: nodeId :: rgbColor :: alias :: addresses :: unknownFields :: HNil))))

  def channelUpdateWitnessEncode(chainHash: ByteVector32, shortChannelId: Long, timestamp: Long, messageFlags: Byte, channelFlags: Byte, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: Option[MilliSatoshi], unknownFields: ByteVector): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelUpdateWitnessCodec.encode(chainHash :: shortChannelId :: timestamp :: messageFlags :: channelFlags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: unknownFields :: HNil))))

  def generateChannelAnnouncementWitness(chainHash: ByteVector32, shortChannelId: Long, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey, remoteFundingKey: PublicKey, features: Features): ByteVector =
    if (isNode1(localNodeId, remoteNodeId)) {
      channelAnnouncementWitnessEncode(chainHash, shortChannelId, localNodeId, remoteNodeId, localFundingKey, remoteFundingKey, features, unknownFields = ByteVector.empty)
    } else {
      channelAnnouncementWitnessEncode(chainHash, shortChannelId, remoteNodeId, localNodeId, remoteFundingKey, localFundingKey, features, unknownFields = ByteVector.empty)
    }

  def signChannelAnnouncement(witness: ByteVector, key: PrivateKey): ByteVector64 = Crypto.sign(witness, key)

  def makeChannelAnnouncement(chainHash: ByteVector32, shortChannelId: Long, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey, remoteFundingKey: PublicKey, localNodeSignature: ByteVector64, remoteNodeSignature: ByteVector64, localBitcoinSignature: ByteVector64, remoteBitcoinSignature: ByteVector64): ChannelAnnouncement = {
    val (nodeId1, nodeId2, bitcoinKey1, bitcoinKey2, nodeSignature1, nodeSignature2, bitcoinSignature1, bitcoinSignature2) =
      if (isNode1(localNodeId, remoteNodeId)) {
        (localNodeId, remoteNodeId, localFundingKey, remoteFundingKey, localNodeSignature, remoteNodeSignature, localBitcoinSignature, remoteBitcoinSignature)
      } else {
        (remoteNodeId, localNodeId, remoteFundingKey, localFundingKey, remoteNodeSignature, localNodeSignature, remoteBitcoinSignature, localBitcoinSignature)
      }
    ChannelAnnouncement(
      nodeSignature1 = nodeSignature1,
      nodeSignature2 = nodeSignature2,
      bitcoinSignature1 = bitcoinSignature1,
      bitcoinSignature2 = bitcoinSignature2,
      shortChannelId = shortChannelId,
      nodeId1 = nodeId1,
      nodeId2 = nodeId2,
      bitcoinKey1 = bitcoinKey1,
      bitcoinKey2 = bitcoinKey2,
      features = Features.empty,
      chainHash = chainHash
    )
  }

  def isNode1(localNodeId: PublicKey, remoteNodeId: PublicKey): Boolean = LexicographicalOrdering.isLessThan(localNodeId.value, remoteNodeId.value)

  def isNode1(channelFlags: Byte): Boolean = (channelFlags & 1) == 0

  def isEnabled(channelFlags: Byte): Boolean = (channelFlags & 2) == 0

  def areSame(u1: ChannelUpdate, u2: ChannelUpdate): Boolean = u1.copy(signature = ByteVector64.Zeroes, timestamp = 0) == u2.copy(signature = ByteVector64.Zeroes, timestamp = 0)

  def makeMessageFlags(hasOptionChannelHtlcMax: Boolean): Byte = BitVector.bits(hasOptionChannelHtlcMax :: Nil).padLeft(8).toByte(signed = true)

  def makeChannelFlags(isNode1: Boolean, enable: Boolean): Byte = BitVector.bits(!enable :: !isNode1 :: Nil).padLeft(8).toByte(signed = true)

  def makeChannelUpdate(chainHash: ByteVector32, nodeSecret: PrivateKey, remoteNodeId: PublicKey, shortChannelId: Long, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi, enable: Boolean = true, timestamp: Long = System.currentTimeMillis.milliseconds.toSeconds): ChannelUpdate = {
    val messageFlags = makeMessageFlags(hasOptionChannelHtlcMax = true) // NB: we always support option_channel_htlc_max
    val channelFlags = makeChannelFlags(isNode1 = isNode1(nodeSecret.publicKey, remoteNodeId), enable = enable)
    val htlcMaximumMsatOpt = Some(htlcMaximumMsat)

    val witness = channelUpdateWitnessEncode(chainHash, shortChannelId, timestamp, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsatOpt, unknownFields = ByteVector.empty)
    val sig = Crypto.sign(witness, nodeSecret)
    ChannelUpdate(
      signature = sig,
      chainHash = chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      messageFlags = messageFlags,
      channelFlags = channelFlags,
      cltvExpiryDelta = cltvExpiryDelta,
      htlcMinimumMsat = htlcMinimumMsat,
      feeBaseMsat = feeBaseMsat,
      feeProportionalMillionths = feeProportionalMillionths,
      htlcMaximumMsat = htlcMaximumMsatOpt
    )
  }

  def checkSig(upd: ChannelUpdate)(nodeId: PublicKey): Boolean = {
    val witness = channelUpdateWitnessEncode(upd.chainHash, upd.shortChannelId, upd.timestamp, upd.messageFlags, upd.channelFlags,
      upd.cltvExpiryDelta, upd.htlcMinimumMsat, upd.feeBaseMsat, upd.feeProportionalMillionths, upd.htlcMaximumMsat, upd.unknownFields)
    verifySignature(witness, upd.signature, nodeId)
  }

  def checkSig(ann: NodeAnnouncement): Boolean = {
    val witness = nodeAnnouncementWitnessEncode(ann.timestamp, ann.nodeId, ann.rgbColor, ann.alias, ann.features, ann.addresses, ann.unknownFields)
    verifySignature(witness, ann.signature, ann.nodeId)
  }
}
