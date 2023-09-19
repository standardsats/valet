package com.btcontract.walletfiat

import android.os.Bundle
import android.widget.{ImageButton, ImageView, RelativeLayout, TextView}
import androidx.transition.TransitionManager
import BaseActivity.StringOps
import Colors._
import immortan.crypto.Tools._
import immortan.fsm.IncomingRevealed
import immortan.utils.{InputParser, PaymentRequestExt}
import immortan.{ChannelMaster, LNParams, PaymentInfo}
import rx.lang.scala.Subscription


class QRInvoiceActivity extends QRActivity with ExternalDataChecker { me =>
  lazy private[this] val activityQRInvoiceMain = findViewById(R.id.activityQRInvoiceMain).asInstanceOf[RelativeLayout]
  lazy private[this] val invoiceQrCaption = findViewById(R.id.invoiceQrCaption).asInstanceOf[TextView]
  lazy private[this] val invoiceHolding = findViewById(R.id.invoiceHolding).asInstanceOf[ImageButton]
  lazy private[this] val invoiceSuccess = findViewById(R.id.invoiceSuccess).asInstanceOf[ImageView]
  lazy private[this] val qrViewHolder = new QRViewHolder(me findViewById R.id.invoiceQr)

  private var fulfillSubscription: Subscription = _
  private var holdSubscription: Subscription = _

  def markFulfilled: Unit = UITask {
    TransitionManager.beginDelayedTransition(activityQRInvoiceMain)
    setVisMany(true -> invoiceSuccess, false -> invoiceHolding)
  }.run

  def markHolding: Unit = UITask {
    TransitionManager.beginDelayedTransition(activityQRInvoiceMain)
    setVisMany(false -> invoiceSuccess, true -> invoiceHolding)
  }.run

  override def PROCEED(state: Bundle): Unit = {
    setContentView(R.layout.activity_qr_lightning_invoice)
    invoiceQrCaption setText getString(R.string.dialog_receive_ln).html
    invoiceHolding setOnClickListener onButtonTap(finish)
    checkExternalData(noneRunnable)
  }

  def showInvoice(info: PaymentInfo): Unit =
    runInFutureProcessOnUI(QRActivity.get(info.prExt.raw.toUpperCase, qrSize), onFail) { qrBitmap =>
      def share: Unit = runInFutureProcessOnUI(shareData(qrBitmap, info.prExt.raw), onFail)(none)
      setVis(isVisible = false, qrViewHolder.qrEdit)

      qrViewHolder.qrLabel setText WalletApp.denom.parsedWithSign(info.received, cardIn, totalZero).html
      qrViewHolder.qrCopy setOnClickListener onButtonTap(WalletApp.app copy info.prExt.raw)
      qrViewHolder.qrCode setOnClickListener onButtonTap(WalletApp.app copy info.prExt.raw)
      qrViewHolder.qrShare setOnClickListener onButtonTap(share)
      qrViewHolder.qrCode setImageBitmap qrBitmap

      fulfillSubscription = ChannelMaster.inFinalized
        .collect { case revealed: IncomingRevealed => revealed }
        .filter(revealed => info.fullTag == revealed.fullTag)
        .subscribe(_ => markFulfilled)

      holdSubscription = ChannelMaster.stateUpdateStream.filter { _ =>
        val incomingFsmOpt = LNParams.cm.inProcessors.get(info.fullTag)
        incomingFsmOpt.exists(info.isActivelyHolding)
      }.subscribe(_ => markHolding)
    }

  override def checkExternalData(whenNone: Runnable): Unit = InputParser.checkAndMaybeErase {
    case prEx: PaymentRequestExt => LNParams.cm.payBag.getPaymentInfo(prEx.pr.paymentHash).foreach(showInvoice)
    case _ => finish
  }

  override def onDestroy: Unit = {
    fulfillSubscription.unsubscribe
    holdSubscription.unsubscribe
    super.onDestroy
  }
}
