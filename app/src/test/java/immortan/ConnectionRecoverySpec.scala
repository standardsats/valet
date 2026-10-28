package immortan

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{Socket, SocketAddress}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.wire.{Init, NodeAddress}
import immortan.crypto.Tools.randomKeyPair
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import scala.collection.mutable
import scala.concurrent.duration._

class ConnectionRecoverySpec {
  class ControlledSocket extends Socket {
    val closed = new CountDownLatch(1)
    override def connect(address: SocketAddress, timeout: Int): Unit = ()
    override def getOutputStream: ByteArrayOutputStream = new ByteArrayOutputStream
    override def getInputStream: InputStream = new InputStream {
      override def read(): Int = { closed.await(); -1 }
    }
    override def close(): Unit = { closed.countDown(); () }
  }

  def withWorker(test: (CommsTower.Worker, ControlledSocket) => Unit): Unit = {
    val previousInit = LNParams.ourInit
    LNParams.ourInit = Init(Features.empty)
    val info = RemoteNodeInfo(randomKey.publicKey, NodeAddress.unresolved(9735, 127, 0, 0, 1), "test")
    val pair = KeyPairAndPubKey(randomKeyPair, info.nodeId)
    val socket = new ControlledSocket
    val worker = new CommsTower.Worker(pair, info, new Array[Byte](1024), socket)
    CommsTower.workers(pair) = worker
    try test(worker, socket) finally {
      CommsTower.forget(pair)
      LNParams.ourInit = previousInit
    }
  }

  @Test
  def listenerRegisteredAfterInitSnapshotIsNotLostOrNotifiedTwice: Unit = withWorker { (worker, _) =>
    var notifications = 0
    val listener = new ConnectionListener {
      override def onOperational(worker: CommsTower.Worker, init: Init): Unit = { notifications += 1 }
    }
    // The transport took an empty snapshot before this registration, but has not delivered Init yet.
    CommsTower.listen(Set(listener), worker.pair, worker.info)
    worker.handleTheirRemoteInitMessage(Set.empty, LNParams.ourInit)
    assertEquals(1, notifications)
    for (_ <- 1 to 3) CommsTower.listen(Set(listener), worker.pair, worker.info)
    assertEquals(1, notifications)

    var lateNotifications = 0
    val lateListener = new ConnectionListener {
      override def onOperational(worker: CommsTower.Worker, init: Init): Unit = { lateNotifications += 1 }
    }
    CommsTower.listen(Set(lateListener), worker.pair, worker.info)
    assertEquals(1, notifications)
    assertEquals(1, lateNotifications)
  }

  @Test
  def disconnectRemovesWorkerBeforeNotifyingListeners: Unit = withWorker { (worker, _) =>
    val disconnected = new CountDownLatch(1)
    @volatile var workerStillRegistered = true
    CommsTower.listeners(worker.pair) = Set(new ConnectionListener {
      override def onDisconnect(stopped: CommsTower.Worker): Unit = {
        workerStillRegistered = CommsTower.workers.contains(stopped.pair)
        disconnected.countDown()
      }
    })
    worker.disconnect
    assertTrue(disconnected.await(5, TimeUnit.SECONDS))
    assertFalse(workerStillRegistered)
  }

  class ScheduledMaster extends ChannelMaster(null, null, null, null) {
    val tasks = mutable.Queue.empty[(FiniteDuration, () => Unit)]
    var attempts = 0
    override protected def scheduleConnectionTask(delay: FiniteDuration)(action: => Unit): Unit = tasks.enqueue((delay, () => action))
    override def initConnect: Unit = { attempts += 1 }
  }

  @Test
  def failedConnectionsKeepFiveSecondRetriesWithoutDuplicateTimers: Unit = {
    val master = new ScheduledMaster
    val nodeId: PublicKey = randomKey.publicKey
    for (attempt <- 1 to 10) {
      for (_ <- 1 to 3) master.scheduleReconnect(nodeId)
      assertEquals(1, master.tasks.size)
      val (delay, reconnect) = master.tasks.dequeue()
      assertEquals(5.seconds, delay)
      reconnect()
      assertEquals(attempt, master.attempts)
    }
  }

  @Test
  def handshakeCheckIsScheduledWithoutWaitingForAnotherBlock: Unit = withWorker { (worker, _) =>
    val master = new ScheduledMaster
    master.onOperational(worker, LNParams.ourInit)
    assertEquals(1, master.tasks.size)
    assertEquals(ChannelMaster.REESTABLISH_TIMEOUT, master.tasks.dequeue()._1)
  }
}
