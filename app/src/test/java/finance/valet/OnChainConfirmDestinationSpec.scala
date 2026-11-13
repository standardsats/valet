package finance.valet

import fr.acinq.bitcoin.SatoshiLong
import fr.acinq.eclair.MilliSatoshi
import org.junit.Assert._
import org.junit.Test

class OnChainConfirmDestinationSpec {
  // Synthetic pair: same prefix/suffix shape, different middle section.
  private val addressA = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
  private val addressB = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfja20wlh"
  // Genuine valid control from existing suite.
  private val addressC = "2N2JD6wb56AfK4tfmM6PwdVmoYk2dCKf4Br"

  @Test
  def confirmDestinationsKeepExactRawAddressesAndDifferingMiddles: Unit = {
    val destinations = BaseActivity.AddressPresentation.confirmDestinations(Seq(
      addressA -> Some(MilliSatoshi(1000L)),
      addressB -> Some(MilliSatoshi(2000L))
    ))

    assertEquals(2, destinations.size)
    assertEquals(addressA, destinations.head.raw)
    assertEquals(addressB, destinations(1).raw)

    // Both strings share long prefix/suffix but grouped representation must still differ.
    assertNotEquals(destinations.head.grouped, destinations(1).grouped)

    // Grouped text must never alter copy-safe raw destination.
    assertEquals(addressA, destinations.head.grouped.replace(" ", ""))
    assertEquals(addressB, destinations(1).grouped.replace(" ", ""))
  }

  @Test
  def confirmDestinationAccessibilityAlwaysUsesExactRawText: Unit = {
    val destination = BaseActivity.AddressPresentation.confirmDestination(addressC, amount = None)
    assertEquals(addressC, destination.accessibility)
    assertEquals(addressC, destination.grouped.replace(" ", ""))
    assertTrue(destination.amount.isEmpty)
  }

  @Test
  def verifiedSingleDestinationMappingRequiresExactOutputMatch: Unit = {
    val toKey: String => String = _ => "script-a"
    val ok = BaseActivity.AddressPresentation.verifiedSingleConfirmDestination(addressA, toKey, Map("script-a" -> 1500L.sat))
    assertTrue(ok.isRight)
    assertEquals(Some(MilliSatoshi(150000L)), ok.right.get.amount)

    val mismatch = BaseActivity.AddressPresentation.verifiedSingleConfirmDestination(addressA, toKey, Map("script-b" -> 1500L.sat))
    assertTrue(mismatch.isLeft)
  }

  @Test
  def verifiedBatchDestinationMappingKeepsAddressAmountAssociation: Unit = {
    val toKey: String => String = {
      case `addressA` => "script-a"
      case `addressB` => "script-b"
      case _ => "unknown"
    }

    val verified = BaseActivity.AddressPresentation.verifiedBatchConfirmDestinations(
      rawDestinations = Seq(addressA -> 1000L.sat, addressB -> 2000L.sat),
      toKey = toKey,
      actualOutputs = Map("script-a" -> 1000L.sat, "script-b" -> 2000L.sat)
    )

    assertTrue(verified.isRight)
    assertEquals(2, verified.right.get.size)
    assertEquals(addressA, verified.right.get.head.raw)
    assertEquals(Some(MilliSatoshi(100000L)), verified.right.get.head.amount)
    assertEquals(addressB, verified.right.get(1).raw)
    assertEquals(Some(MilliSatoshi(200000L)), verified.right.get(1).amount)
  }

  @Test
  def verifiedBatchDestinationMappingRejectsAmbiguousDuplicateScripts: Unit = {
    val duplicateKey = "script-dup"
    val toKey: String => String = {
      case `addressA` => duplicateKey
      case `addressB` => duplicateKey
      case _ => "unknown"
    }

    val rejected = BaseActivity.AddressPresentation.verifiedBatchConfirmDestinations(
      rawDestinations = Seq(addressA -> 1000L.sat, addressB -> 2000L.sat),
      toKey = toKey,
      actualOutputs = Map(duplicateKey -> 2000L.sat)
    )

    assertTrue(rejected.isLeft)
  }
}
