package com.btcontract.walletfiat.utils

import android.os.Environment._
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import fr.acinq.eclair.randomBytes
import com.google.common.io.{ByteStreams, Files}
import android.content.Context
import scodec.bits.{BitVector, ByteVector}
import immortan.crypto.Tools
import immortan.wire.ExtCodecs
import scodec.Attempt.{Failure, Successful}

import scala.util.Try
import java.io.{File, FileInputStream}


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
  def downloadsDir(context: Context): File = context.getExternalFilesDir(DIRECTORY_DOWNLOADS)

  // Prefixing by one byte to discern future backup types (full wallet backup / minimal channel backup etc)
  def encryptBackup(backup: ByteVector, seed: ByteVector): ByteVector = 0.toByte +: Tools.chaChaEncrypt(Crypto.sha256(seed), randomBytes(12), backup)
  def decryptBackup(backup: ByteVector, seed: ByteVector): Try[ByteVector] = Tools.chaChaDecrypt(Crypto.sha256(seed), backup drop 1)

  def encryptAndWritePlainBackup(context: Context, dbFileName: String, chainHash: ByteVector32, seed: ByteVector): Unit = {
    val dataBaseFile = new File(context.getDatabasePath(dbFileName).getPath)
    val cipherBytes = encryptBackup(ByteVector.view(Files toByteArray dataBaseFile), seed)
    atomicWrite(getBackupFileUnsafe(context, chainHash, seed), cipherBytes)
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
