package com.btcontract.walletfiat.utils

import android.os.Environment._
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.{ContextCompat, FileProvider}
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import fr.acinq.eclair.randomBytes
import com.google.common.io.{ByteStreams, Files}
import android.content.{ContentValues, Context}
import android.net.Uri
import android.os.{Build, Environment}
import android.provider.MediaStore
import scodec.bits.{BitVector, ByteVector}
import immortan.crypto.Tools
import immortan.wire.ExtCodecs
import scodec.Attempt.{Failure, Successful}

import scala.util.Try
import java.io.{BufferedInputStream, File, FileInputStream}
import scala.collection.immutable.HashMap
import scala.collection.JavaConverters._

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
  def askPermission(activity: AppCompatActivity): Unit = ActivityCompat.requestPermissions(activity, Array(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), LOCAL_BACKUP_REQUEST_NUMBER)
  def isAllowed(context: Context): Boolean = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
  // Note that the function returns directory in the internal storage, then we copy backups to external dir
  def downloadsDir(context: Context): File = context.getExternalFilesDir(DIRECTORY_DOWNLOADS)

  private val DOWNLOAD_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

//  val finalUri : Uri? = copyFileToDownloads(context, downloadedFile)

  def copyFileToDownloads(context: Context, downloadedFile: File): Uri = {
    val resolver = context.getContentResolver
    val downloadedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val contentValues = new ContentValues()
      contentValues.put(MediaStore.MediaColumns.IS_PENDING, true)
      contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, downloadedFile.getName)
      contentValues.put(MediaStore.MediaColumns.MIME_TYPE, resolver.getType(android.net.Uri.fromFile(downloadedFile)))
      contentValues.put(MediaStore.MediaColumns.SIZE, String.valueOf(downloadedFile.length()))

      resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
    } else {
      val authority = s"${context.getPackageName}.provider"
      val destinyFile = new File(DOWNLOAD_DIR, downloadedFile.getName)
      FileProvider.getUriForFile(context, authority, destinyFile)
    }

    val outputStream = resolver.openOutputStream(downloadedUri)
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
    copyFileToDownloads(context, backupFile)
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
