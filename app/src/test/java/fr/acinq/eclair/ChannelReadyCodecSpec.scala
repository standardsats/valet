package fr.acinq.eclair

import fr.acinq.eclair.wire.{FundingLocked, FundingLockedTlv, LightningMessageCodecs, TlvStream}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import scodec.bits._

class ChannelReadyCodecSpec {
  // channel_ready as LND sends it for a non-taproot channel: type 36, channel id, next point, TLV 1 with an alias from LND's range (block 16000000)
  val lndChannelReady: ByteVector = hex"0024" ++ ByteVector.fill(32)(7) ++
    hex"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798" ++ hex"0108" ++ hex"f424000000010000"

  @Test
  def lndChannelReadyIsDecoded: Unit = {
    val decoded = LightningMessageCodecs.lightningMessageCodecWithFallback.decode(lndChannelReady.bits).require
    assertTrue(decoded.remainder.isEmpty)
    assertEquals(classOf[FundingLocked], decoded.value.getClass)
    assertEquals(Some(ShortChannelId(16000000, 1, 0)), decoded.value.asInstanceOf[FundingLocked].alias)
    assertEquals(lndChannelReady.bits, LightningMessageCodecs.lightningMessageCodecWithFallback.encode(decoded.value).require)
  }

  @Test
  def aliasesPreserveAll64Bits: Unit = {
    val locked = LightningMessageCodecs.lightningMessageCodecWithFallback.decode(lndChannelReady.bits).require.value.asInstanceOf[FundingLocked]
    for (alias <- Seq(0L, Long.MaxValue, Long.MinValue, -1L)) {
      val message = locked.copy(tlvStream = TlvStream[FundingLockedTlv](FundingLockedTlv.ShortChannelIdAlias(alias)))
      val encoded = LightningMessageCodecs.lightningMessageCodecWithFallback.encode(message).require
      val decoded = LightningMessageCodecs.lightningMessageCodecWithFallback.decode(encoded).require
      assertTrue(decoded.remainder.isEmpty)
      assertEquals(message, decoded.value)
    }
  }

  @Test
  def legacyChannelReadyWithoutAliasIsDecoded: Unit = {
    val decoded = LightningMessageCodecs.lightningMessageCodecWithFallback.decode(lndChannelReady.dropRight(10).bits).require
    assertTrue(decoded.remainder.isEmpty)
    assertEquals(None, decoded.value.asInstanceOf[FundingLocked].alias)
  }

  @Test
  def truncatedAliasIsRejected: Unit = {
    assertTrue(LightningMessageCodecs.lightningMessageCodecWithFallback.decode(lndChannelReady.dropRight(1).bits).isFailure)
  }
}
