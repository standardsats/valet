package com.btcontract.walletfiat

import android.os.Bundle
import android.view.View
import scala.util.Try

class EmergencyActivity extends BaseActivity {
  override def PREINIT(state: Bundle): Unit = INIT(state)

  def INIT(state: Bundle): Unit = setContentView(R.layout.activity_emergency)

  def shareErrorReport(view: View): Unit = Try(getIntent getStringExtra UncaughtHandler.ERROR_REPORT).foreach(share)
}
