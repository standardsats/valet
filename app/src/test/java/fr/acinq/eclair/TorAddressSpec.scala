package fr.acinq.eclair

import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool
import fr.acinq.eclair.wire.{Domain, NodeAddress, Tor3}
import immortan.{ConnectionProvider, LNParams, RemoteNodeInfo}
import immortan.utils.{NormalChannelRequest, HostedChannelRequest, InputParser}
import org.junit.Assert._
import org.junit.Test

class TorAddressSpec {
  private val key = "02889be42fc32093d2dcbfa59369df262e3577b333d8a45e5859dcdd6a4139839a"

  @Test
  def scanningAndChannelRequestsPreserveHostnamesWithoutDns: Unit = {
    val uri = s"$key@wallet-peer.invalid:9735"
    val address = Domain("wallet-peer.invalid", 9735)
    assertEquals(address, NodeAddress.fromParts("wallet-peer.invalid", 9735))
    assertEquals(address, InputParser.parse(uri).asInstanceOf[RemoteNodeInfo].address)
    assertEquals(address, InputParser.parse(s"$key@wallet-peer.invalid").asInstanceOf[RemoteNodeInfo].address)
    assertEquals(address, NormalChannelRequest(uri, "https://wallet-peer.invalid/callback", "00" * 32).address)
    assertEquals(address, HostedChannelRequest(uri, None, "00" * 32, immortan.Ticker.USD_TICKER).address)
  }

  @Test
  def onionNamesAndElectrumServerListsRemainUnresolved: Unit = {
    val onion = "a" * 56
    val node = NodeAddress.fromParts(s"$onion.onion", 9735)
    assertEquals(Tor3(onion, 9735), node)
    assertTrue(node.socketAddress.isUnresolved)
    val json = """{"electrum.invalid":{"s":"50002"}}"""
    val servers = ElectrumClientPool.readServerAddresses(new java.io.ByteArrayInputStream(json.getBytes("UTF-8")))
    assertTrue(servers.head.address.isUnresolved)
    assertEquals("electrum.invalid", servers.head.address.getHostString)
  }

  @Test
  def domainAddressDoesNotAskAnUnreadyTorProviderForItsPort: Unit = {
    val previous = LNParams.connectionProvider
    LNParams.connectionProvider = new ConnectionProvider {
      override def usesProxy = true
      def proxyAddress = throw new AssertionError("Tor is not ready, DNS must still be deferred")
      def okHttpClient = throw new AssertionError("No HTTP during address parsing")
      def getSocket = throw new AssertionError("No sockets during address parsing")
      def doWhenReady(action: => Unit): Unit = ()
    }
    try assertTrue(Domain("wallet-peer.invalid", 9735).socketAddress.isUnresolved)
    finally LNParams.connectionProvider = previous
  }
}
