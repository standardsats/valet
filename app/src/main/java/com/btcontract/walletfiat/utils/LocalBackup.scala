package com.btcontract.walletfiat.utils

import android.os.Environment._
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.{ContextCompat, FileProvider}
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import fr.acinq.eclair.randomBytes
import com.google.common.io.{ByteStreams, Files}
import android.content.{ContentResolver, ContentValues, Context}
import android.net.Uri
import android.os.{Build, Environment}
import android.provider.{DocumentsContract, MediaStore}
import androidx.documentfile.provider.DocumentFile
import com.btcontract.walletfiat.WalletApp
import com.btcontract.walletfiat.WalletApp.customBackupLocation
import scodec.bits.ByteVector
import immortan.crypto.Tools
import immortan.wire.ExtCodecs
import scodec.Attempt.{Failure, Successful}

import scala.util.Try
import java.io.{BufferedInputStream, File, FileInputStream}
object LocalBackup { me =>
  final val BACKUP_NAME = "encrypted.channels"
  final val GRAPH_NAME = "graph.snapshot"
  final val BACKUP_EXTENSION = ".bin"
  final val GRAPH_EXTENSION = ".zlib"

  def getNetwork(chainHash: ByteVector32): String = chainHash match {
    case Block.LivenetGenesisBlock.hash => "mainnet"
    case Block.TestnetGenesisBlock.hash => "testnet"
    case _ => "unknown"
  }

  def getBackupFileUnsafe(context: Context, chainHash: ByteVector32, seed: ByteVector): File = {
    val specifics = s"${me getNetwork chainHash}-${Crypto.hash160(seed).take(4).toHex}"
    new File(downloadsDir(context), s"$BACKUP_NAME-$specifics$BACKUP_EXTENSION")
  }

  final val LOCAL_BACKUP_REQUEST_NUMBER = 105
  def askPermission(activity: AppCompatActivity): Unit = ActivityCompat.requestPermissions(activity, Array(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), LOCAL_BACKUP_REQUEST_NUMBER)
  def isAllowed(context: Context): Boolean = {
    (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
      ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) || customBackupLocation.nonEmpty
  }

  // Note that the function returns directory in the internal storage, then we copy backups to external dir
  def downloadsDir(context: Context): File = context.getExternalFilesDir(DIRECTORY_DOWNLOADS)

  // This is directory in external storage
  private val DOWNLOAD_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
  private def downloadDirFileUri(context: Context, fileName: String): Uri = {
    val file = new File(DOWNLOAD_DIR.getAbsolutePath + "/" + fileName)
    FileProvider.getUriForFile(context, s"${context.getPackageName}", file)
  }

  // A way to get direct access to file inside download dir
  def findFileDirectlyInDownloads(context: Context, fileName: String): Option[Uri] = {
    val downloadDir = new File(DOWNLOAD_DIR.getAbsolutePath)
    val files = downloadDir.listFiles()
    val authority = s"${context.getPackageName}"
    println(files.mkString("\n"))
    files.find(_.getName == fileName).map(FileProvider.getUriForFile(context, authority, _))
  }

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

  // Depending on the Android version choose the best place to locate backup in the external storage.
  // Takes into account:
  // * directory - is custom directory selected by user to store backup at
  // * version - older versions will use direct File API, newer will prefer SAF if possible.
  // * existing of old backup - older one will be overwritten
  def selectDestinationUri(context: Context, resolver: ContentResolver, directory: Option[Uri], downloadedFile: File): Uri = {
    val fileName = downloadedFile.getName
    directory match {
      case None => {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val existedFile = findFileDirectlyInDownloads(context, fileName)
          existedFile match {
            case Some(uri) => {
              println("LocalBackup: found the backup file at " + uri.toString)
              val contentValues = new ContentValues()
              contentValues.put(MediaStore.MediaColumns.IS_PENDING, true)
              contentValues.put(MediaStore.MediaColumns.SIZE, String.valueOf(downloadedFile.length()))
              contentValues.put(MediaStore.MediaColumns.MIME_TYPE, resolver.getType(android.net.Uri.fromFile(downloadedFile)))
              resolver.update(uri, contentValues, null, null)
              uri
            }
            case _ => {
              println("LocalBackup: no backup file at downloads")
              downloadDirFileUri(context, fileName)
            }
          }
        } else {
          val authority = s"${context.getPackageName}"
          val destinyFile = new File(directory.map(uri => new File(uri.getPath)).getOrElse(DOWNLOAD_DIR), fileName)
          FileProvider.getUriForFile(context, authority, destinyFile)
        }
      }
      case Some(uri) => {
        println("LocalBackup: Got custom directory " + uri)
        val dirFile: DocumentFile = DocumentFile.fromTreeUri(context, uri)
        println("LocalBackup: Can create files in directory: " ++ dirFile.canWrite.toString)
        dirFile.listFiles.find(_.getName.equals(fileName)) match {
          case Some(existingFile) =>
            println("LocalBackup: We found file to rewrite: " ++ existingFile.getUri.toString)
            existingFile.getUri

          case None =>
            println("LocalBackup: Creating new file")
            DocumentsContract.createDocument(resolver, dirFile.getUri, "application/valet", fileName)
        }
      }
    }
  }

  def copyFileToDirectory(context: Context, directory: Option[Uri], downloadedFile: File): Uri = printExceptions {
    val resolver = context.getContentResolver
    val fileName = downloadedFile.getName
    val downloadedUri: Uri = directory match {
      case None => {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val existedFile = findFileDirectlyInDownloads(context, fileName)
          existedFile match {
            case Some(uri) => {
              println("LocalBackup: found the backup file at " + uri.toString)
              val contentValues = new ContentValues()
              contentValues.put(MediaStore.MediaColumns.IS_PENDING, true)
              contentValues.put(MediaStore.MediaColumns.SIZE, String.valueOf(downloadedFile.length()))
              contentValues.put(MediaStore.MediaColumns.MIME_TYPE, resolver.getType(android.net.Uri.fromFile(downloadedFile)))
              resolver.update(uri, contentValues, null, null)
              uri
            }
            case _ => {
              println("LocalBackup: no backup file at downloads")
              downloadDirFileUri(context, fileName)
            }
          }
        } else {
          val authority = s"${context.getPackageName}"
          val destinyFile = new File(directory.map(uri => new File(uri.getPath)).getOrElse(DOWNLOAD_DIR), fileName)
          FileProvider.getUriForFile(context, authority, destinyFile)
        }
      }
      case Some(uri) => {
        println("LocalBackup: Got custom directory " + uri)
        val dirFile: DocumentFile = DocumentFile.fromTreeUri(context, uri)
        println("LocalBackup: Can create files in directory: " ++ dirFile.canWrite.toString)
        dirFile.listFiles.find(_.getName.equals(fileName)) match {
          case Some(existingFile) =>
            println("LocalBackup: We found file to rewrite: " ++ existingFile.getUri.toString)
            existingFile.getUri

          case None =>
            println("LocalBackup: Creating new file")
            DocumentsContract.createDocument(resolver, dirFile.getUri, "application/valet", fileName)
        }
      }
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
    copyFileToDirectory(context, WalletApp.customBackupLocation, backupFile)
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
