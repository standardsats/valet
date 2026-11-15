package finance.valet

import finance.valet.R.string._
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletAppSignetPolicySpec {
  @Test
  def nonSignetChangedElectrumWithChannelsIsNotBlockedBySignetPolicy: Unit = {
    val problem = WalletApp.signetSwitchProblemByPolicy(
      isSignetFlavor1 = false,
      changedElectrum = true,
      channelsExist1 = true,
      customSignet1 = false,
      customElectrumValid = true,
      changedSignetId = false)

    assertEquals(None, problem)
  }

  @Test
  def nonSignetReturnsWithoutEvaluatingSignetOnlyInputs: Unit = {
    var evaluated = 0

    val problem = WalletApp.signetSwitchProblemByPolicy(
      isSignetFlavor1 = false,
      changedElectrum = { evaluated += 1; true },
      channelsExist1 = { evaluated += 1; true },
      customSignet1 = true,
      customElectrumValid = { evaluated += 1; false },
      changedSignetId = { evaluated += 1; true })

    assertEquals(None, problem)
    assertEquals(0, evaluated)
  }

  @Test
  def signetChangedServerWithChannelsIsRejected: Unit = {
    val problem = WalletApp.signetSwitchProblemByPolicy(
      isSignetFlavor1 = true,
      changedElectrum = true,
      channelsExist1 = true,
      customSignet1 = false,
      customElectrumValid = true,
      changedSignetId = false)

    assertEquals(Some(settings_electrum_close_channels), problem)
  }

  @Test
  def signetCustomSignetWithoutValidEndpointIsRejected: Unit = {
    val problem = WalletApp.signetSwitchProblemByPolicy(
      isSignetFlavor1 = true,
      changedElectrum = false,
      channelsExist1 = false,
      customSignet1 = true,
      customElectrumValid = false,
      changedSignetId = true)

    assertEquals(Some(settings_custom_signet_no_electrum), problem)
  }

  @Test
  def signetChainChangeWithChannelsIsRejected: Unit = {
    val problem = WalletApp.signetSwitchProblemByPolicy(
      isSignetFlavor1 = true,
      changedElectrum = false,
      channelsExist1 = true,
      customSignet1 = false,
      customElectrumValid = true,
      changedSignetId = true)

    assertEquals(Some(settings_custom_signet_close_channels), problem)
  }

  @Test
  def signetNoChannelsNoSwitchProblem: Unit = {
    val problem = WalletApp.signetSwitchProblemByPolicy(
      isSignetFlavor1 = true,
      changedElectrum = true,
      channelsExist1 = false,
      customSignet1 = false,
      customElectrumValid = true,
      changedSignetId = false)

    assertEquals(None, problem)
  }

  @Test
  def nonSignetIgnoresSignetOnlyFlags: Unit = {
    val problem = WalletApp.signetSwitchProblemByPolicy(
      isSignetFlavor1 = false,
      changedElectrum = false,
      channelsExist1 = true,
      customSignet1 = true,
      customElectrumValid = false,
      changedSignetId = true)

    assertEquals(None, problem)
  }
}
