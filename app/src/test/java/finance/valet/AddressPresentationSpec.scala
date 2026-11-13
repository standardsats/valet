package finance.valet

import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.addressToPublicKeyScript
import immortan.utils.BitcoinUri
import org.junit.Assert._
import org.junit.Test

import scala.util.Try

class AddressPresentationSpec {
  private val bech32P2wpkhMainnet = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
  private val p2pkhMainnet = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
  private val p2shMainnet = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"
  private val p2shTestnet = "2N2JD6wb56AfK4tfmM6PwdVmoYk2dCKf4Br"

  // Valid witness vectors (checksum-valid), deterministic and network-scoped.
  private val bech32P2wpkhTestnet = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
  private val bech32P2wpkhRegtest = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
  private val taprootMainnet = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
  private val longWitnessRegtest = "bcrt1qqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq076jc3"

  private val groupedExpected = Map(
    bech32P2wpkhMainnet -> "bc1q xy2k gdyg jrsq tzq2 n0yr f249 3p83 kkfj hx0w lh",
    p2pkhMainnet -> "1Boa tSLR HtKN ngkd XEeo bR76 b53L ETtp yT",
    p2shMainnet -> "3J98 t1Wp EZ73 CNmQ viec rnyi Wrnq RhWN Ly",
    p2shTestnet -> "2N2J D6wb 56Af K4tf mM6P wdVm oYk2 dCKf 4Br",
    bech32P2wpkhTestnet -> "tb1q w508 d6qe jxtd g4y5 r3za rvar y0c5 xw7k xpjz sx",
    bech32P2wpkhRegtest -> "bcrt 1qw5 08d6 qejx tdg4 y5r3 zarv ary0 c5xw 7kyg t080",
    taprootMainnet -> "bc1p 0xlx vlhe mja6 c4dq v22u apct qupf hlxm 9h8z 3k2e 72q4 k9hc z7vq zk5j j0",
    longWitnessRegtest -> "bcrt 1qqq qsyq cyq5 rqwz qfqq qsyq cyq5 rqwz qfqq qsyq cyq5 rqwz qfqy pq07 6jc3"
  )

  private case class ChainScopedAddress(raw: String, chainHash: ByteVector32)

  private val validDisplayEligibleAddresses = Seq(
    ChainScopedAddress(bech32P2wpkhMainnet, Block.LivenetGenesisBlock.hash),
    ChainScopedAddress(p2pkhMainnet, Block.LivenetGenesisBlock.hash),
    ChainScopedAddress(p2shMainnet, Block.LivenetGenesisBlock.hash),
    ChainScopedAddress(p2shTestnet, Block.SignetGenesisBlock.hash),
    ChainScopedAddress(bech32P2wpkhTestnet, Block.SignetGenesisBlock.hash),
    ChainScopedAddress(bech32P2wpkhRegtest, Block.RegtestGenesisBlock.hash),
    ChainScopedAddress(taprootMainnet, Block.LivenetGenesisBlock.hash),
    ChainScopedAddress(longWitnessRegtest, Block.RegtestGenesisBlock.hash)
  )

  @Test
  def groupedAndAccessibilityPreserveExactContentForChecksumValidEligibleFormats: Unit = {
    validDisplayEligibleAddresses.foreach { case ChainScopedAddress(raw, chainHash) =>
      assertTrue(s"expected production decoder to accept: $raw", Try(addressToPublicKeyScript(raw, chainHash)).isSuccess)
      assertTrue(s"expected display-eligible: $raw", BaseActivity.AddressPresentation.isDisplayEligibleAddress(raw))

      val grouped = BaseActivity.AddressPresentation.groupedFull(raw)
      assertEquals(groupedExpected(raw), grouped)
      assertEquals(raw, grouped.replace(" ", ""))
      assertEquals(raw, BaseActivity.AddressPresentation.accessibilityFull(raw))
    }
  }

  @Test
  def validUppercaseBech32RemainsUngroupedByDisplayPolicyAndMixedCaseOrLookalikesAreRejected: Unit = {
    val upperBech32Mainnet = bech32P2wpkhMainnet.toUpperCase
    val mixedBech32 = "bc1Qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
    val lookalikeBase58 = "1BoatSLRHtKNngkdXEeobR76b53LETtpy0"

    // Uppercase Bech32 is checksum-valid; we intentionally keep display policy lowercase-only for grouping.
    assertTrue(Try(addressToPublicKeyScript(upperBech32Mainnet, Block.LivenetGenesisBlock.hash)).isSuccess)
    assertFalse(BaseActivity.AddressPresentation.isDisplayEligibleAddress(upperBech32Mainnet))
    assertEquals(upperBech32Mainnet, BaseActivity.AddressPresentation.groupedFull(upperBech32Mainnet))
    assertEquals(upperBech32Mainnet, BaseActivity.AddressPresentation.accessibilityFull(upperBech32Mainnet))

    Seq(mixedBech32, lookalikeBase58).foreach { raw =>
      assertTrue(s"expected production decoder to reject malformed input: $raw", Try(addressToPublicKeyScript(raw, Block.LivenetGenesisBlock.hash)).isFailure)
      assertFalse(BaseActivity.AddressPresentation.isDisplayEligibleAddress(raw))
      assertEquals(raw, BaseActivity.AddressPresentation.groupedFull(raw))
      assertEquals(raw, BaseActivity.AddressPresentation.accessibilityFull(raw))
    }
  }

  @Test
  def nullWhitespaceAndUnknownPolicyIsExplicit: Unit = {
    assertEquals("", BaseActivity.AddressPresentation.groupedFull(null))
    assertEquals("", BaseActivity.AddressPresentation.groupedFull("  \n\t  "))
    assertEquals("", BaseActivity.AddressPresentation.accessibilityFull(null))

    val unknown = "not-an-address!"
    assertFalse(BaseActivity.AddressPresentation.isDisplayEligibleAddress(unknown))
    assertEquals(unknown, BaseActivity.AddressPresentation.groupedFull(unknown))
    assertEquals(unknown, BaseActivity.AddressPresentation.accessibilityFull(unknown))
  }

  @Test
  def bip21FixturesKeepExactEncodingAndAreNotGroupedByMistake: Unit = {
    val fixture = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.00123456&label=Coffee%20Shop&message=Thanks%2Bfor%2Bpaying"

    val parsed = BitcoinUri.fromRaw(fixture)
    assertEquals(bech32P2wpkhMainnet, parsed.address)
    assertEquals("amount=0.00123456&label=Coffee%20Shop&message=Thanks%2Bfor%2Bpaying", parsed.uri.get.getEncodedQuery)

    assertFalse(BaseActivity.AddressPresentation.isDisplayEligibleAddress(fixture))
    assertEquals(fixture, BaseActivity.AddressPresentation.groupedFull(fixture))
    assertEquals(fixture, BaseActivity.AddressPresentation.accessibilityFull(fixture))

    assertEquals(groupedExpected(bech32P2wpkhMainnet), BaseActivity.AddressPresentation.groupedFull(parsed.address))
    assertEquals(Some("Coffee Shop"), parsed.label)
    assertEquals(Some("Thanks+for+paying"), parsed.message)
    assertEquals(Some(BigDecimal("0.00123456")), parsed.uri.map(_.getQueryParameter("amount")).map(BigDecimal.apply).toOption)
  }
}
