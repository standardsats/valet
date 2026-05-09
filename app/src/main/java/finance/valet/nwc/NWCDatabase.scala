package finance.valet.nwc

import android.content.Context
import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import fr.acinq.bitcoin.ByteVector32
import immortan.sqlite.{DBInterface, RichCursor}
import scodec.bits.ByteVector

import scala.collection.mutable.ListBuffer


/**
 * NWC Connection table definition.
 */
object NWCConnectionTable {
  val table = "nwc_connections"
  val id = "id"
  val name = "name"
  val walletPrivkey = "wallet_privkey"
  val walletPubkey = "wallet_pubkey"
  val secret = "secret"
  val relay = "relay"
  val createdAt = "created_at"
  val connectionType = "connection_type"

  val newSql = s"INSERT OR REPLACE INTO $table ($id, $name, $walletPrivkey, $walletPubkey, $secret, $relay, $createdAt, $connectionType) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

  val selectAllSql = s"SELECT * FROM $table ORDER BY $createdAt DESC"

  val selectByIdSql = s"SELECT * FROM $table WHERE $id = ?"

  val killSql = s"DELETE FROM $table WHERE $id = ?"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id TEXT PRIMARY KEY,
      $name TEXT NOT NULL,
      $walletPrivkey BLOB NOT NULL,
      $walletPubkey TEXT NOT NULL,
      $secret TEXT NOT NULL,
      $relay TEXT NOT NULL,
      $createdAt INTEGER NOT NULL,
      $connectionType TEXT NOT NULL DEFAULT 'send'
    )"""
    createTable :: Nil
  }
}


/**
 * SQLite database helper for NWC data.
 */
class NWCDatabaseHelper(context: Context, name: String) extends SQLiteOpenHelper(context, name, null, 2) {
  val base: SQLiteDatabase = getWritableDatabase

  override def onCreate(db: SQLiteDatabase): Unit = {
    NWCConnectionTable.createStatements.foreach(db.execSQL)
  }

  override def onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = {
    // Migration from version 1 to 2: add connection_type column
    if (oldVersion < 2) {
      db.execSQL(s"ALTER TABLE ${NWCConnectionTable.table} ADD COLUMN ${NWCConnectionTable.connectionType} TEXT NOT NULL DEFAULT 'send'")
    }
  }
}


/**
 * NWC Database access layer.
 */
class NWCDatabase(helper: NWCDatabaseHelper) {

  private val db = helper.base

  /**
   * Save a connection to the database.
   */
  def saveConnection(connection: NWCConnection): Unit = {
    db.execSQL(
      NWCConnectionTable.newSql,
      Array[AnyRef](
        connection.id,
        connection.name,
        connection.walletPrivkey.toArray,
        connection.walletPubkey,
        connection.secret,
        connection.relay,
        java.lang.Long.valueOf(connection.createdAt),
        connection.connectionType
      )
    )
  }

  /**
   * Get a connection by ID.
   */
  def getConnection(id: String): Option[NWCConnection] = {
    val cursor = db.rawQuery(NWCConnectionTable.selectByIdSql, Array(id))
    try {
      if (cursor.moveToFirst()) {
        Some(cursorToConnection(cursor))
      } else {
        None
      }
    } finally {
      cursor.close()
    }
  }

  /**
   * List all connections.
   */
  def listConnections(): List[NWCConnection] = {
    val connections = ListBuffer[NWCConnection]()
    val cursor = db.rawQuery(NWCConnectionTable.selectAllSql, Array.empty[String])
    try {
      while (cursor.moveToNext()) {
        connections += cursorToConnection(cursor)
      }
    } finally {
      cursor.close()
    }
    connections.toList
  }

  /**
   * Delete a connection.
   */
  def deleteConnection(id: String): Unit = {
    db.execSQL(NWCConnectionTable.killSql, Array[AnyRef](id))
  }

  /**
   * Convert a cursor row to NWCConnection.
   */
  private def cursorToConnection(cursor: android.database.Cursor): NWCConnection = {
    val id = cursor.getString(cursor.getColumnIndexOrThrow(NWCConnectionTable.id))
    val name = cursor.getString(cursor.getColumnIndexOrThrow(NWCConnectionTable.name))
    val walletPrivkeyBytes = cursor.getBlob(cursor.getColumnIndexOrThrow(NWCConnectionTable.walletPrivkey))
    val walletPubkey = cursor.getString(cursor.getColumnIndexOrThrow(NWCConnectionTable.walletPubkey))
    val secret = cursor.getString(cursor.getColumnIndexOrThrow(NWCConnectionTable.secret))
    val relay = cursor.getString(cursor.getColumnIndexOrThrow(NWCConnectionTable.relay))
    val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(NWCConnectionTable.createdAt))
    val connectionType = cursor.getString(cursor.getColumnIndexOrThrow(NWCConnectionTable.connectionType))

    // Reconstruct the connection URL
    val connectionUrl = NostrRelay.generateConnectionUrl(walletPubkey, relay, secret)

    NWCConnection(
      id = id,
      name = name,
      walletPrivkey = ByteVector32(ByteVector.view(walletPrivkeyBytes)),
      walletPubkey = walletPubkey,
      secret = secret,
      relay = relay,
      createdAt = createdAt,
      connectionUrl = connectionUrl,
      connectionType = connectionType
    )
  }
}


/**
 * Companion object for creating NWCDatabase instances.
 */
object NWCDatabase {
  private var instance: Option[NWCDatabase] = None

  /**
   * Initialize the NWC database.
   */
  def init(context: Context): NWCDatabase = {
    instance match {
      case Some(db) => db
      case None =>
        val helper = new NWCDatabaseHelper(context.getApplicationContext, "nwc.db")
        val db = new NWCDatabase(helper)
        instance = Some(db)
        db
    }
  }

  /**
   * Get the initialized database instance.
   */
  def get: Option[NWCDatabase] = instance
}
