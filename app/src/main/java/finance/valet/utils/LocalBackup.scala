package finance.valet.utils

import android.os.Environment._
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import androidx.appcompat.app.AppCompatActivity
import fr.acinq.eclair.randomBytes
import com.google.common.io.{ByteStreams, Files}
import android.content.{Context, Intent}
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import finance.valet.WalletApp
import finance.valet.WalletApp.customBackupLocation
import scodec.bits.ByteVector
import immortan.crypto.Tools
import immortan.wire.ExtCodecs
import scodec.Attempt.{Failure, Successful}

import scala.collection.JavaConverters._
import scala.util.Try
import java.io.{BufferedInputStream, File, FileInputStream}
object LocalBackup { me =>
  final val BACKUP_NAME = "encrypted.channels"
  final val GRAPH_NAME = "graph.snapshot"
  final val BACKUP_EXTENSION = ".bin"
  final val GRAPH_EXTENSION = ".zlib"

  def getNetwork(chainHash: ByteVector32): String = chainHash match {
    case Block.LivenetGenesisBlock.hash => "mainnet"
    case Block.Testnet3GenesisBlock.hash => "testnet"
    case Block.Testnet4GenesisBlock.hash => "testnet"
    case _ => "unknown"
  }

  def getBackupFileUnsafe(context: Context, chainHash: ByteVector32, seed: ByteVector): File = {
    val specifics = s"${me getNetwork chainHash}-${Crypto.hash160(seed).take(4).toHex}"
    new File(downloadsDir(context), s"$BACKUP_NAME-$specifics$BACKUP_EXTENSION")
  }

  // Backups are published exclusively through SAF (ACTION_OPEN_DOCUMENT_TREE): runtime
  // storage permissions are never granted on modern Android, so the user picks a directory
  // once and we hold a persistable write permission on it.
  def askBackupDirectory(activity: AppCompatActivity, requestCode: Int): Unit = {
    val intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.addCategory(Intent.CATEGORY_DEFAULT)
    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    activity.startActivityForResult(Intent.createChooser(intent, activity getString finance.valet.R.string.settings_choose_directory), requestCode)
  }

  def saveChosenDirectory(context: Context, uri: Uri): Unit = {
    val resolver = context.getContentResolver
    val oldUri = customBackupLocation.filterNot(_ == uri)
    Try(resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)).foreach { _ =>
      if (WalletApp.app.prefs.edit.putString(WalletApp.CUSTOM_BACKUP_LOCATION, uri.toString).commit) {
        oldUri.foreach(uri => Try(resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
      }
    }
  }

  def isAllowed(context: Context): Boolean = customBackupLocation.exists { uri =>
    context.getContentResolver.getPersistedUriPermissions.asScala.exists(perm => perm.getUri == uri && perm.isWritePermission)
  }

  // Note that the function returns directory in the internal storage, then we copy backups to external dir
  def downloadsDir(context: Context): File = context.getExternalFilesDir(DIRECTORY_DOWNLOADS)

  // Helper to print exception to logs if any
  def printExceptions[T](body: => T): T = {
    val result = Try {body}
    result match {
      case util.Failure(exception) => {
        println(exception.getMessage)
        exception.printStackTrace()
      }
      case _ => ()
    }
    result.get
  }

  // Copies the staged backup file into the user-selected SAF directory, overwriting an
  // older backup with the same name when present.
  def copyFileToDirectory(context: Context, directory: Uri, downloadedFile: File): Uri = printExceptions {
    val resolver = context.getContentResolver
    val fileName = downloadedFile.getName
    println("LocalBackup: Got custom directory " + directory)
    val dirFile: DocumentFile = DocumentFile.fromTreeUri(context, directory)
    println("LocalBackup: Can create files in directory: " ++ dirFile.canWrite.toString)
    val downloadedUri: Uri = dirFile.listFiles.find(_.getName.equals(fileName)) match {
      case Some(existingFile) =>
        println("LocalBackup: We found file to rewrite: " ++ existingFile.getUri.toString)
        existingFile.getUri

      case None =>
        println("LocalBackup: Creating new file")
        DocumentsContract.createDocument(resolver, dirFile.getUri, "application/valet", fileName)
    }
    println("LocalBackup: Will write backup to: " ++ downloadedUri.toString)

    val outputStream = resolver.openOutputStream(downloadedUri, "wt")
    val brr = Array.ofDim[Byte](1024)
    var len: Int = 0
    val bufferedInputStream = new BufferedInputStream(new FileInputStream(downloadedFile.getAbsoluteFile))
    while ({
      val it = bufferedInputStream.read(brr, 0, brr.size)
      len = it
      it != -1
    }) {
      outputStream.write(brr, 0, len)
    }
    outputStream.flush()
    bufferedInputStream.close()
    downloadedUri
  }

  // Prefixing by one byte to discern future backup types (full wallet backup / minimal channel backup etc)
  def encryptBackup(backup: ByteVector, seed: ByteVector): ByteVector = 0.toByte +: Tools.chaChaEncrypt(Crypto.sha256(seed), randomBytes(12), backup)
  def decryptBackup(backup: ByteVector, seed: ByteVector): Try[ByteVector] = Tools.chaChaDecrypt(Crypto.sha256(seed), backup drop 1)

  def encryptAndWritePlainBackup(context: Context, dbFileName: String, chainHash: ByteVector32, seed: ByteVector): Unit = {
    val dataBaseFile = new File(context.getDatabasePath(dbFileName).getPath)
    val cipherBytes = encryptBackup(ByteVector.view(Files toByteArray dataBaseFile), seed)
    val backupFile = getBackupFileUnsafe(context, chainHash, seed)
    atomicWrite(backupFile, cipherBytes)
    WalletApp.customBackupLocation.foreach(copyFileToDirectory(context, _, backupFile))
  }

  // It is assumed that we try to decrypt a backup before running this and only proceed on success
  def copyPlainDataToDbLocation(context: Context, dbFileName: String, plainBytes: ByteVector): Unit = {
    val dataBaseFile = new File(context.getDatabasePath(dbFileName).getPath)
    if (!dataBaseFile.exists) dataBaseFile.getParentFile.mkdirs
    atomicWrite(dataBaseFile, plainBytes)
  }

  // Graph implanting

  // Separate method because we save the same file both in Downloads and in local assets folders
  def getGraphResourceName(chainHash: ByteVector32): String = s"$GRAPH_NAME-${me getNetwork chainHash}$GRAPH_EXTENSION"
  def getGraphFileUnsafe(context: Context, chainHash: ByteVector32): File = new File(downloadsDir(context), me getGraphResourceName chainHash)

  // Helper function to save graph database as compressed bytes into downloads folder
  def writeCompressedGraph(context: Context, dbFileName: String, chainHash: ByteVector32): Unit = {
    val dataBaseFile = new File(context.getDatabasePath(dbFileName).getPath)
    val uncompressedPlainBytes = ByteStreams.toByteArray(new FileInputStream(dataBaseFile))
    val plainBytesA = ExtCodecs.compressedByteVecCodec.encode(ByteVector view uncompressedPlainBytes)
    val targetFile =getGraphFileUnsafe(context, chainHash)
    println(s"Write down compressed graph to ${targetFile.getPath}")
    plainBytesA match {
      case Successful(plainBytes) => atomicWrite(targetFile, plainBytes.bytes)
      case Failure(cause) => println(s"Failed to store graph: $cause")
    }
  }

  // Utils

  def atomicWrite(file: File, data: ByteVector): Unit = {
    val atomicFile = new android.util.AtomicFile(file)
    var fileOutputStream = atomicFile.startWrite

    try {
      fileOutputStream.write(data.toArray)
      atomicFile.finishWrite(fileOutputStream)
      fileOutputStream = null
    } finally {
      if (fileOutputStream != null) {
        atomicFile.failWrite(fileOutputStream)
      }
    }
  }
}
