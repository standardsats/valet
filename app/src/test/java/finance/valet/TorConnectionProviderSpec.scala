package finance.valet

import java.io.{DataInputStream, IOException}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.junit.Assert._
import org.junit.Test

class TorConnectionProviderSpec {
  class ControlledTor extends TorConnectionProvider(null) {
    var starts = 0
    override protected def startTor(): Unit = { starts += 1 }
  }

  def unavailable(action: => Any): Unit = {
    try { action; fail("An unavailable Tor proxy must not permit a connection") }
    catch { case _: IOException => () }
  }

  @Test
  def resumePreservesStartupCallbacksAndReadyOnlyRunsThemOnce: Unit = {
    val tor = new ControlledTor
    var starts = 0
    tor.doWhenReady { starts += 1 }
    tor.doWhenReady { starts += 10 }
    tor.onTorStopped()
    tor.notifyAppAvailable
    assertEquals(3, tor.starts)
    assertEquals(0, starts)
    tor.onTorReady(19050)
    tor.onTorReady(19050)
    assertEquals(11, starts)
    tor.close
  }

  @Test
  def allTransportsFailClosedUntilTorReportsItsPort: Unit = {
    val tor = new ControlledTor
    assertTrue(tor.usesProxy)
    unavailable(tor.proxyAddress)
    unavailable(tor.get("https://must-not-resolve.invalid/"))
    val sock = tor.getSocket
    try unavailable(sock.connect(InetSocketAddress.createUnresolved("must-not-resolve.invalid", 9735), 100))
    finally sock.close()
    tor.close
  }

  @Test
  def recoveryReplacesTheHttpProxyAndDoesNotReuseTheOldPort: Unit = {
    val tor = new ControlledTor
    tor.onTorReady(19050)
    val oldClient = tor.okHttpClient
    assertEquals(19050, tor.proxyAddress.get.getPort)
    tor.onTorStopped()
    unavailable(tor.okHttpClient)
    tor.onTorReady(29050)
    assertEquals(29050, tor.proxyAddress.get.getPort)
    assertEquals(tor.proxyAddress.get, tor.okHttpClient.proxy.address)
    assertNotSame(oldClient, tor.okHttpClient)
    tor.close
  }

  @Test
  def closingDropsPendingCallbacksAndIgnoresLateReadiness: Unit = {
    val tor = new ControlledTor
    var calls = 0
    tor.doWhenReady { calls += 1 }
    tor.close
    tor.onTorReady(19050)
    assertEquals(0, calls)
    unavailable(tor.okHttpClient)
    unavailable(tor.doWhenReady { calls += 1 })
  }

  @Test
  def socketUsesDiscoveredPortAndSendsHostnameToSocksInsteadOfDns: Unit = {
    val server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    val done = new CountDownLatch(1)
    @volatile var failure: Option[Throwable] = None
    val peer = new Thread(new Runnable {
      override def run(): Unit = try {
        val socket = server.accept()
        try {
          socket.setSoTimeout(3000)
          val in = new DataInputStream(socket.getInputStream)
          val out = socket.getOutputStream
          assertEquals(5, in.readUnsignedByte())
          val methods = new Array[Byte](in.readUnsignedByte())
          in.readFully(methods)
          out.write(Array[Byte](5, 0)); out.flush()
          assertEquals(5, in.readUnsignedByte())
          assertEquals(1, in.readUnsignedByte())
          assertEquals(0, in.readUnsignedByte())
          assertEquals(3, in.readUnsignedByte()) // SOCKS hostname, not an IP address
          val host = new Array[Byte](in.readUnsignedByte())
          in.readFully(host)
          assertEquals("wallet-peer.invalid", new String(host, "UTF-8"))
          assertEquals(9735, in.readUnsignedShort())
          out.write(Array[Byte](5, 0, 0, 1, 127, 0, 0, 1, 0, 1)); out.flush()
        } finally socket.close()
      } catch { case error: Throwable => failure = Some(error) }
      finally done.countDown()
    })
    peer.setDaemon(true)
    peer.start()
    val tor = new ControlledTor
    tor.onTorReady(server.getLocalPort)
    val sock: Socket = tor.getSocket
    try {
      sock.connect(InetSocketAddress.createUnresolved("wallet-peer.invalid", 9735), 3000)
      assertTrue(done.await(5, TimeUnit.SECONDS))
      failure.foreach(throw _)
    } finally {
      sock.close()
      server.close()
      tor.close
    }
  }
}
