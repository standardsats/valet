package immortan

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.{FeatureSupport, Features}
import fr.acinq.eclair.Features.{ChannelRangeQueries, ChannelRangeQueriesExtended}
import fr.acinq.eclair.wire.{EncodedShortChannelIds, EncodingType, Init, ReplyChannelRange, TlvStream}
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(classOf[AndroidJUnit4])
class GossipQueriesSpec {

  @Test def classifiesBasicAndExtendedGossipPeers(): Unit = {
    val basic = Init(Features((ChannelRangeQueries, FeatureSupport.Optional)))
    val extended = Init(Features((ChannelRangeQueriesExtended, FeatureSupport.Optional)))

    assert(GossipQueriesSupport.from(basic) == BasicGossipQueries)
    assert(GossipQueriesSupport.from(extended) == ExtendedGossipQueries)
  }

  @Test def permitsBareBasicChannelRangeReplies(): Unit = {
    val reply = ReplyChannelRange(
      chainHash = ByteVector32.Zeroes,
      firstBlockNum = 0,
      numberOfBlocks = 1,
      syncComplete = 1,
      shortChannelIds = EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(42L))
    )

    assert(reply.timestamps.isEmpty)
    assert(reply.checksums.isEmpty)
    assert(SyncWorkerShortIdsData(ranges = List(reply), from = 0, gossipQueriesSupport = BasicGossipQueries).isHolistic)
  }

  @Test def encodesBasicGossipRequestsWithoutFeatureTlvs(): Unit = {
    val queries = SyncMaster.basicGossipQueries(ByteVector32.Zeroes, EncodingType.UNCOMPRESSED, Seq(42L, 43L), chunkSize = 1).toList

    assert(queries.size == 2)
    assert(queries.forall(_.tlvStream == TlvStream.empty))
  }
}
