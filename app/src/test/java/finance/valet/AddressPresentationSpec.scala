package finance.valet

import immortan.utils.BitcoinUri
import org.junit.Assert._
import org.junit.Test

class AddressPresentationSpec {
  private val bech32 = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
  private val legacy = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"

  @Test
  def groupedFullAddressPreservesOrderForSupportedFormats: Unit = {
    val groupedBech32 = BaseActivity.AddressPresentation.groupedFull(bech32)
    assertTrue(groupedBech32.contains(" "))
    assertEquals(bech32, groupedBech32.replace(" ", ""))

    val groupedLegacy = BaseActivity.AddressPresentation.groupedFull(legacy)
    assertTrue(groupedLegacy.contains(" "))
    assertEquals(legacy, groupedLegacy.replace(" ", ""))
  }

  @Test
  def accessibilityAddressRetainsAllCharacters: Unit = {
    assertEquals(bech32, BaseActivity.AddressPresentation.accessibilityFull(bech32))
    assertEquals(legacy, BaseActivity.AddressPresentation.accessibilityFull(legacy))
  }

  @Test
  def nullEmptyAndInvalidPolicyIsExplicit: Unit = {
    assertEquals("", BaseActivity.AddressPresentation.groupedFull(null))
    assertEquals("", BaseActivity.AddressPresentation.groupedFull("  \n\t  "))
    assertEquals("", BaseActivity.AddressPresentation.accessibilityFull(null))

    val invalid = "not-an-address!"
    assertFalse(BaseActivity.AddressPresentation.isSupported(invalid))
    assertEquals(invalid, BaseActivity.AddressPresentation.groupedFull(invalid))
    assertEquals(invalid, BaseActivity.AddressPresentation.accessibilityFull(invalid))
  }

  @Test
  def bip21FixturesKeepExactEncodingAndAreNotGroupedByMistake: Unit = {
    val fixture = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.00123456&label=Coffee%20Shop&message=Thanks%2Bfor%2Bpaying"

    val fixtureSnapshot = fixture
    val parsed = BitcoinUri.fromRaw(fixture)
    assertEquals(bech32, parsed.address)
    assertEquals(fixtureSnapshot, fixture)
    assertEquals("amount=0.00123456&label=Coffee%20Shop&message=Thanks%2Bfor%2Bpaying", parsed.uri.get.getEncodedQuery)

    assertFalse(BaseActivity.AddressPresentation.isSupported(fixture))
    assertEquals(fixture, BaseActivity.AddressPresentation.groupedFull(fixture))
    assertEquals(fixture, BaseActivity.AddressPresentation.accessibilityFull(fixture))

    assertEquals(Some("Coffee Shop"), parsed.label)
    assertEquals(Some("Thanks+for+paying"), parsed.message)
    assertEquals(Some(BigDecimal("0.00123456")), parsed.uri.map(_.getQueryParameter("amount")).map(BigDecimal.apply).toOption)
  }
}
