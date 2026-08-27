package finance.valet

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.acinq.eclair.{Features, InitFeature, UnknownFeature}
import fr.acinq.eclair.channel.{ChannelFeatures, ChannelTypes}
import immortan.CommsTower
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(classOf[AndroidJUnit4])
class ChanActivitySpec {
  @Test def identifiesNegotiatedCommitmentType(): Unit = {
    assert(ChanActivity.commitmentBadge(ChannelFeatures.fromChannelType(ChannelTypes.AnchorOutputsZeroFeeHtlcTx))._1 == R.string.chan_commitment_zero_fee_anchors)
    assert(ChanActivity.commitmentBadge(ChannelFeatures.fromChannelType(ChannelTypes.AnchorOutputs))._1 == R.string.chan_commitment_anchors)
    assert(ChanActivity.commitmentBadge(ChannelFeatures())._1 == R.string.chan_commitment_legacy)
  }

  @Test def decodesInformationalPeerFeatures(): Unit = {
    val advertised = Features[InitFeature](Map.empty, Set(UnknownFeature(51), UnknownFeature(63)))
    val decoded = Features(advertised.toByteVector).initFeatures()
    assert(CommsTower.advertisesFeature(decoded, ChanActivity.ZeroconfFeatureBit))
    assert(CommsTower.advertisesFeature(decoded, ChanActivity.SpliceFeatureBit))
    assert(!CommsTower.advertisesFeature(Features.empty[InitFeature], ChanActivity.ZeroconfFeatureBit))
  }
}
