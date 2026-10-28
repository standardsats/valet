package finance.valet

import java.io.IOException
import java.net.{InetSocketAddress, Proxy, Socket, SocketAddress}
import java.util.concurrent.TimeUnit

import android.content.{BroadcastReceiver, ComponentName, Context, Intent, IntentFilter, ServiceConnection}
import android.os.{Build, Handler, IBinder, Looper}
import immortan.{ConnectionProvider, LNParams}
import immortan.crypto.Tools._
import okhttp3.OkHttpClient
import org.torproject.jni.TorService

import scala.util.Try


class TorConnectionProvider(context: Context) extends ConnectionProvider {
  override def usesProxy: Boolean = true

  @volatile private var client: Option[OkHttpClient] = None
  private var pending = Vector.empty[() => Unit]
  private var closed = false
  private var registered = false
  private var portConnection = Option.empty[ServiceConnection]
  private lazy val handler = new Handler(Looper.getMainLooper)

  // Unavailable Tor must fail closed, never expose a direct-network provider or a guessed port.
  override def okHttpClient: OkHttpClient = client.getOrElse(throw new IOException("Tor is not ready"))
  override def proxyAddress: Option[InetSocketAddress] = Some(okHttpClient.proxy.address.asInstanceOf[InetSocketAddress])

  override def getSocket: Socket = client match {
    case Some(ready) => new Socket(ready.proxy)
    // CommsTower handles connect failures asynchronously and schedules its normal retry.
    case None => new Socket {
      override def connect(address: SocketAddress, timeout: Int): Unit = throw new IOException("Tor is not ready")
      override def connect(address: SocketAddress): Unit = connect(address, 0)
    }
  }

  def doWhenReady(action: => Unit): Unit = {
    synchronized {
      if (closed) throw new IOException("Tor provider is closed")
      pending :+= (() => action)
    }
    startTor()
  }

  override def notifyAppAvailable: Unit = startTor()

  protected def startTor(): Unit = synchronized {
    if (!closed) {
      if (!registered) {
        val filter = new IntentFilter(TorService.ACTION_STATUS)
        filter.addAction(TorService.ACTION_ERROR)
        // Tor replays its current status only through Android broadcasts, not LocalBroadcastManager.
        val permission = context.getPackageName + ".permission.TOR_STATUS"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
          context.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter, permission, null)
        registered = true
      }
      // onStartCommand replays the status even when the service is already running.
      context.startService(new Intent(context, classOf[TorService]))
    }
  }

  private lazy val receiver: BroadcastReceiver = new BroadcastReceiver {
    override def onReceive(ctx: Context, intent: Intent): Unit = {
      if (intent.getAction == TorService.ACTION_ERROR) {
        releasePortConnection()
        onTorStopped()
        reportError(new IOException(intent.getStringExtra(Intent.EXTRA_TEXT)))
      } else intent.getStringExtra(TorService.EXTRA_STATUS) match {
        case TorService.STATUS_ON => bindForPort()
        case TorService.STATUS_STARTING | TorService.STATUS_STOPPING | TorService.STATUS_OFF =>
          releasePortConnection()
          onTorStopped()
        case _ =>
      }
    }
  }

  private def bindForPort(): Unit = synchronized {
    if (!closed && portConnection.isEmpty) {
      val connection = new ServiceConnection { binding =>
        override def onServiceConnected(name: ComponentName, binder: IBinder): Unit = {
          val service = binder.asInstanceOf[TorService#LocalBinder].getService
          val poll = new Runnable {
            override def run(): Unit = TorConnectionProvider.this.synchronized {
              if (portConnection.contains(binding) && !closed) {
                val port = service.getSocksPort
                // A circuit-ready broadcast can arrive just before the control thread stores the port.
                if (port <= 0) handler.postDelayed(this, 100L)
                else {
                  onTorReady(port)
                  releasePortConnection()
                }
              }
            }
          }
          poll.run()
        }
        override def onServiceDisconnected(name: ComponentName): Unit = TorConnectionProvider.this.synchronized {
          if (portConnection.contains(binding)) {
            releasePortConnection()
            onTorStopped()
          }
        }
      }
      portConnection = Some(connection)
      if (!context.bindService(new Intent(context, classOf[TorService]), connection, 0)) {
        portConnection = None
        onTorStopped()
      }
    }
  }

  private def releasePortConnection(): Unit = synchronized {
    portConnection.foreach(connection => try context.unbindService(connection) catch none)
    portConnection = None
  }

  private[valet] def onTorReady(port: Int): Unit = {
    require(port > 0 && port <= 65535, "Invalid Tor SOCKS port")
    val actions = synchronized {
      if (closed) Vector.empty[() => Unit] else {
        if (!client.exists(_.proxy.address.asInstanceOf[InetSocketAddress].getPort == port)) {
          onTorStopped()
          val proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port))
          client = Some(new OkHttpClient.Builder().proxy(proxy).connectTimeout(30, TimeUnit.SECONDS).build())
        }
        val actions = pending
        pending = Vector.empty
        actions
      }
    }
    actions.foreach(action => Try(action()).failed.foreach(reportError))
  }

  private[valet] def onTorStopped(): Unit = synchronized {
    val previous = client
    client = None
    previous.foreach { old =>
      old.dispatcher.cancelAll()
      old.connectionPool.evictAll()
    }
    // Keep pending startup work: resuming or restarting Tor must not discard Electrum initialization.
  }

  private def reportError(error: Throwable): Unit =
    if (LNParams.logBag != null) LNParams.logBag.put("tor", error.toString)

  override def close: Unit = synchronized {
    closed = true
    pending = Vector.empty
    releasePortConnection()
    onTorStopped()
    if (registered) {
      context.unregisterReceiver(receiver)
      registered = false
      context.stopService(new Intent(context, classOf[TorService]))
    }
  }
}
