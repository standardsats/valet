package finance.valet

import immortan.utils.BitcoinUri
import org.junit.Assert._
import org.junit.Test

class AddressPresentationSpec {
  private val bech32P2wpkh = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
  private val p2pkhMainnet = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
  private val p2shMainnet = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"
  private val p2shTestnet = "2N2JD6wb56AfK4tfmM6PwdVmoYk2dCKf4Br"
  private val bech32Testnet = "tb1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
  private val bech32Regtest = "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
  private val taprootMainnet = "bc1p5cyxnuxmeuwuvkwfem96llyxf3q7n8h6x7t0u2"
  private val longAddress = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlhxy2kgdygjrsqtzq2n0yrf2493p83kkfj"

  private val groupedExpected = Map(
    bech32P2wpkh -> "bc1q xy2k gdyg jrsq tzq2 n0yr f249 3p83 kkfj hx0w lh",
    p2pkhMainnet -> "1Boa tSLR HtKN ngkd XEeo bR76 b53L ETtp yT",
    p2shMainnet -> "3J98 t1Wp EZ73 CNmQ viec rnyi Wrnq RhWN Ly",
    p2shTestnet -> "2N2J D6wb 56Af K4tf mM6P wdVm oYk2 dCKf 4Br",
    bech32Testnet -> "tb1q xy2k gdyg jrsq tzq2 n0yr f249 3p83 kkfj hx0w lh",
    bech32Regtest -> "bcrt 1qxy 2kgd ygjr sqtz q2n0 yrf2 493p 83kk fjhx 0wlh",
    taprootMainnet -> "bc1p 5cyx nuxm euwu vkwf em96 llyx f3q7 n8h6 x7t0 u2",
    longAddress -> "bc1q xy2k gdyg jrsq tzq2 n0yr f249 3p83 kkfj hx0w lhxy 2kgd ygjr sqtz q2n0 yrf2 493p 83kk fj"
  )

  @Test
  def groupedAndAccessibilityAddressPreserveExactContentForSupportedFormats: Unit = {
    groupedExpected.foreach { case (raw, grouped) =>
      assertTrue(s"expected display-eligible: $raw", BaseActivity.AddressPresentation.isDisplayEligibleAddress(raw))
      assertEquals(grouped, BaseActivity.AddressPresentation.groupedFull(raw))
      assertEquals(raw, BaseActivity.AddressPresentation.groupedFull(raw).replace(" ", ""))
      assertEquals(raw, BaseActivity.AddressPresentation.accessibilityFull(raw))
    }
  }

  @Test
  def uppercaseMixedCaseAndLookalikeStringsAreNotDisplayEligible: Unit = {
    val upperBech32 = "BC1QXY2KGDYGJRSQTZQ2N0YRF2493P83KKFJHX0WLH"
    val mixedBech32 = "bc1Qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
    val lookalikeBase58 = "1BoatSLRHtKNngkdXEeobR76b53LETtpy0"

    Seq(upperBech32, mixedBech32, lookalikeBase58).foreach { raw =>
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
    assertEquals(bech32P2wpkh, parsed.address)
    assertEquals("amount=0.00123456&label=Coffee%20Shop&message=Thanks%2Bfor%2Bpaying", parsed.uri.get.getEncodedQuery)

    assertFalse(BaseActivity.AddressPresentation.isDisplayEligibleAddress(fixture))
    assertEquals(fixture, BaseActivity.AddressPresentation.groupedFull(fixture))
    assertEquals(fixture, BaseActivity.AddressPresentation.accessibilityFull(fixture))

    assertEquals(groupedExpected(bech32P2wpkh), BaseActivity.AddressPresentation.groupedFull(parsed.address))
    assertEquals(Some("Coffee Shop"), parsed.label)
    assertEquals(Some("Thanks+for+paying"), parsed.message)
    assertEquals(Some(BigDecimal("0.00123456")), parsed.uri.map(_.getQueryParameter("amount")).map(BigDecimal.apply).toOption)
  }
}
