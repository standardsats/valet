package finance.valet

import android.content.ClipData
import android.os.Bundle
import android.view.View
import android.widget._
import finance.valet.BaseActivity.StringOps
import finance.valet.R.string._
import finance.valet.nwc._
import immortan.crypto.Tools._

import java.text.SimpleDateFormat
import java.util.{Date, Locale}


class NWCActivity extends BaseCheckActivity { me =>
  lazy private[this] val nwcContainer = findViewById(R.id.nwcContainer).asInstanceOf[LinearLayout]
  private var nwcManager: Option[NWCManager] = None

  override def PROCEED(state: Bundle): Unit = {
    setContentView(R.layout.activity_nwc)

    // Use the singleton NWC manager from WalletApp
    if (WalletApp.nwcManager != null) {
      nwcManager = Some(WalletApp.nwcManager)
    }

    updateView()
  }

  override def onDestroy(): Unit = {
    // Don't stop connections - they should persist in background
    super.onDestroy()
  }

  private def updateView(): Unit = {
    nwcContainer.removeAllViews()

    // Title
    val title = new TitleView(me getString nwc_title)
    title.view.setOnClickListener(me onButtonTap finish)
    title.backArrow.setVisibility(View.VISIBLE)

    // Add new connection button
    addFlowChip(title.flow, me getString nwc_add_connection, R.drawable.border_green, _ => showAddConnectionDialog())

    nwcContainer.addView(title.view)

    // List existing connections
    nwcManager.foreach { manager =>
      val connections = manager.listConnections()

      if (connections.isEmpty) {
        val emptyView = getLayoutInflater.inflate(R.layout.frag_switch, null).asInstanceOf[RelativeLayout]
        val emptyTitle = emptyView.findViewById(R.id.settingsTitle).asInstanceOf[TextView]
        val emptyInfo = emptyView.findViewById(R.id.settingsInfo).asInstanceOf[TextView]
        val emptyCheck = emptyView.findViewById(R.id.settingsCheck).asInstanceOf[CheckBox]

        emptyTitle.setText(settings_nwc_no_connections)
        emptyInfo.setText(settings_nwc_info)
        setVis(isVisible = false, emptyCheck)

        nwcContainer.addView(emptyView)
      } else {
        connections.foreach { connection =>
          val itemView = getLayoutInflater.inflate(R.layout.frag_nwc_connection, null).asInstanceOf[RelativeLayout]
          val nameView = itemView.findViewById(R.id.nwcConnectionName).asInstanceOf[TextView]
          val statusView = itemView.findViewById(R.id.nwcConnectionStatus).asInstanceOf[TextView]
          val createdView = itemView.findViewById(R.id.nwcConnectionCreated).asInstanceOf[TextView]
          val deleteButton = itemView.findViewById(R.id.nwcDeleteButton).asInstanceOf[ImageButton]

          // Show name with type indicator
          val typeLabel = if (connection.connectionType == NWCConnectionType.RECEIVE) {
            s"[${getString(nwc_type_receive_short)}]"
          } else {
            s"[${getString(nwc_type_send_short)}]"
          }
          nameView.setText(s"${connection.name} $typeLabel")

          // Status
          val isActive = manager.isConnectionActive(connection.id)
          if (isActive) {
            statusView.setText(nwc_connected)
            statusView.setTextColor(getResources.getColor(R.color.colorGreen))
          } else {
            statusView.setText(nwc_disconnected)
            statusView.setTextColor(getResources.getColor(R.color.colorAccent))
          }

          // Created date
          val dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault)
          val createdDate = dateFormat.format(new Date(connection.createdAt))
          createdView.setText(getString(nwc_created_at).format(createdDate))

          // Click to show connection details
          itemView.setOnClickListener(me onButtonTap showConnectionDetails(connection))

          // Delete button
          deleteButton.setOnClickListener(me onButtonTap confirmDeleteConnection(connection))

          nwcContainer.addView(itemView)
        }
      }
    }
  }

  private def showAddConnectionDialog(): Unit = {
    // Show type selection dialog first
    val options = Array[CharSequence](
      me getString nwc_type_send,
      me getString nwc_type_receive
    )

    val typeBuilder = new androidx.appcompat.app.AlertDialog.Builder(me)
      .setTitle(me getString nwc_select_type)
      .setItems(options, new android.content.DialogInterface.OnClickListener {
        override def onClick(dialog: android.content.DialogInterface, which: Int): Unit = {
          val connectionType = if (which == 0) NWCConnectionType.SEND else NWCConnectionType.RECEIVE
          dialog.dismiss()
          showNameInputDialog(connectionType)
        }
      })
      .setNegativeButton(dialog_cancel, null)

    typeBuilder.show()
  }

  private def showNameInputDialog(connectionType: String): Unit = {
    val (container, extraInputLayout, extraInput) = singleInputPopup
    val typeLabel = if (connectionType == NWCConnectionType.SEND) getString(nwc_type_send) else getString(nwc_type_receive)
    val builder = titleBodyAsViewBuilder(s"${getString(nwc_add_connection)} ($typeLabel)".asDefView, container)

    mkCheckForm(alert => runAnd(alert.dismiss)(createConnection(extraInput.getText.toString.trim, connectionType)), none, builder, dialog_ok, dialog_cancel)
    extraInputLayout.setHint(nwc_connection_name_hint)
    showKeys(extraInput)
  }

  private def createConnection(name: String, connectionType: String): Unit = {
    if (name.isEmpty) {
      snack(nwcContainer, me getString nwc_error_empty_name, dialog_ok, _.dismiss())
      return
    }

    nwcManager.foreach { manager =>
      manager.createConnection(name, connectionType) match {
        case scala.util.Success(connection) =>
          UITask {
            updateView()
            showConnectionDetails(connection)
          }.run

        case scala.util.Failure(e) =>
          snack(nwcContainer, s"Failed to create connection: ${e.getMessage}", dialog_ok, _.dismiss())
      }
    }
  }

  private def showConnectionDetails(connection: NWCConnection): Unit = {
    val message = s"<b>${me getString nwc_connection_url}</b><br><br><small>${connection.connectionUrl}</small>"

    def copyUrl(): Unit = {
      val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE).asInstanceOf[android.content.ClipboardManager]
      val clip = ClipData.newPlainText("NWC URL", connection.connectionUrl)
      clipboard.setPrimaryClip(clip)
      snack(nwcContainer, getString(copied_to_clipboard), dialog_ok, _.dismiss())
    }

    val builder = new androidx.appcompat.app.AlertDialog.Builder(me)
      .setTitle(connection.name)
      .setMessage(message.html)

    mkCheckFormNeutral(
      _.dismiss(),
      none,
      _ => copyUrl(),
      builder,
      dialog_ok,
      -1,
      nwc_copy_url
    )
  }

  private def confirmDeleteConnection(connection: NWCConnection): Unit = {
    val builder = new androidx.appcompat.app.AlertDialog.Builder(me)
      .setTitle(me getString nwc_delete_confirm)
      .setMessage(connection.name)

    mkCheckForm(alert => {
      alert.dismiss()
      deleteConnection(connection.id)
    }, none, builder, nwc_delete, dialog_cancel)
  }

  private def deleteConnection(id: String): Unit = {
    nwcManager.foreach { manager =>
      manager.deleteConnection(id) match {
        case scala.util.Success(_) =>
          UITask(updateView()).run

        case scala.util.Failure(e) =>
          snack(nwcContainer, s"Failed to delete: ${e.getMessage}", dialog_ok, _.dismiss())
      }
    }
  }
}
