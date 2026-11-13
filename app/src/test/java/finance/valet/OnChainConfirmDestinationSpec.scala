package finance.valet

import fr.acinq.eclair.MilliSatoshi
import org.junit.Assert._
import org.junit.Test

class OnChainConfirmDestinationSpec {
  private val addressA = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
  private val addressB = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfja20wlh"
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
}
