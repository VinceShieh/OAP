/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.spinach

import java.io.IOException
import java.nio.charset.StandardCharsets

import scala.collection.mutable.{ArrayBuffer, BitSet}
import scala.collection.mutable

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.execution.datasources.spinach.filecache.{DataFiberCache, IndexFiberCacheData}
import org.apache.spark.sql.execution.datasources.spinach.index.{IndexNode, UnsafeIndexNode}
import org.apache.spark.sql.execution.datasources.spinach.io.SpinachDataFile
import org.apache.spark.sql.types._


/**
 * The Spinach meta file is organized in the following format.
 *
 * FileMeta 1        -- 512 bytes
 *     Fingerprint   -- 248 bytes -- The signature of the file.
 *     RecordCount   --   8 bytes -- The record count in the segment.
 *     DataFileName  -- 256 bytes -- The associated data file name. The path is not included.
 * FileMeta 2
 *    .
 *    .
 * FileMeta N
 * IndexMeta 1      -- 768 bytes
 *     Name         -- 255 bytes -- The index name.
 *     indexType    --   1 bytes -- The index type. Sort(0)/ Bitmap Mask(1).
 *     keyOrdinal   -- 256 bytes -- The bit mask for the index key. Maximum support 256 fields
 *     keySortDir   -- 256 bytes -- The bit mask for the key's sort direction. This is only used by
 *                                  BTreeIndex. The bit is set if the sort direction is Descending.
 * IndexMeta 2
 *    .
 *    .
 * IndexMeta N
 * Schema           -- Variable Length -- The table schema in json format.
 * Data Reader Class Name -- Variable Length -- The associated data reader class name.
 * FileHeader       --  32 bytes
 *     RecordCount  --   8 bytes -- The number of all of the records in the same folder.
 *     DataFileCount--   8 bytes -- The number of the data files.
 *     IndexCount   --   8 bytes -- The number of the index.
 *     Version      --   3 bytes -- Each bytes represents Major, Minor and Revision.
 *     MagicNumber  --   5 bytes -- The magic number of the meta file which is always "FIBER".
 *
 */

private[spinach] trait IndexType

private[spinach] case class BTreeIndexEntry(ordinal: Int, dir: SortDirection = Ascending) {
  override def toString: String = ordinal + " " + (if (dir == Ascending) "ASC" else "DESC")
}

private[spinach] case class BTreeIndex(entries: Seq[BTreeIndexEntry] = Nil) extends IndexType {
  def appendEntry(entry: BTreeIndexEntry): BTreeIndex = BTreeIndex(entries :+ entry)

  override def toString: String = "COLUMN(" + entries.mkString(", ") + ") BTREE"
}

private[spinach] case class BitMapIndex(entries: Seq[Int] = Nil) extends IndexType {
  def appendEntry(entry: Int): BitMapIndex = BitMapIndex(entries :+ entry)

  override def toString: String = "COLUMN(" + entries.mkString(", ") + ") BITMAP"
}

private[spinach] case class HashIndex(entries: Seq[Int] = Nil) extends IndexType {
  def appendEntry(entry: Int): HashIndex = HashIndex(entries :+ entry)

  override def toString: String = "COLUMN(" + entries.mkString(", ") + ") BITMAP"
}

private[spinach] class FileMeta {
  import DataSourceMeta._

  var fingerprint: String = _
  var recordCount: Long = _
  var dataFileName: String = _

  def write(out: FSDataOutputStream): Unit = {
    writeString(fingerprint, FILE_META_FINGERPRINT_LENGTH, out)
    out.writeLong(recordCount)
    writeString(dataFileName, FILE_META_DATA_FILE_NAME_LENGTH, out)
  }

  def read(in: FSDataInputStream): Unit = {
    var readPos = in.getPos
    in.seek(readPos)
    fingerprint = in.readUTF()
    readPos += FILE_META_FINGERPRINT_LENGTH

    in.seek(readPos)
    recordCount = in.readLong()
    dataFileName = in.readUTF()
  }
}

private[spinach] object FileMeta {
  def apply(): FileMeta = new FileMeta()
  def apply(fingerprint: String, recordCount: Long, dataFileName: String): FileMeta = {
    val fileMeta = new FileMeta()
    fileMeta.fingerprint = fingerprint
    fileMeta.recordCount = recordCount
    fileMeta.dataFileName = dataFileName
    fileMeta
  }
}

private[spinach] class IndexMeta(var name: String = null, var indexType: IndexType = null)
    extends Serializable {
  import DataSourceMeta._
  import IndexMeta._

  override def toString: String = name + ": " + indexType

  def open(data: IndexFiberCacheData, keySchema: StructType): IndexNode = {
    UnsafeIndexNode(DataFiberCache(data.fiberData), data.rootOffset, data.dataEnd, keySchema)
  }

  private def writeBitSet(value: BitSet, totalSizeToWrite: Int, out: FSDataOutputStream): Unit = {
    val sizeBefore = out.size
    value.toBitMask.foreach(out.writeLong)
    val sizeWritten = out.size - sizeBefore
    val remaining = totalSizeToWrite - sizeWritten
    assert(remaining >= 0,
      s"Failed to write $value as it exceeds the max allowed $totalSizeToWrite bytes.")
    for (i <- 0 until remaining) {
      out.writeByte(0)
    }
  }

  private def writeBTreeIndexEntries(
      entries: Seq[BTreeIndexEntry], totalSizeToWrite: Int, out: FSDataOutputStream): Unit = {
    val sizeBefore = out.size
    out.writeInt(entries.size)
    entries.foreach(e => {
      val abs = e.ordinal + 1
      val v = if (e.dir == Descending) {
        -abs
      } else {
        abs
      }
      out.writeInt(v)
    })
    val sizeWritten = out.size - sizeBefore
    val remaining = totalSizeToWrite - sizeWritten
    assert(remaining >= 0,
      s"Failed to write $entries as it exceeds the max allowed $totalSizeToWrite bytes.")
    for (i <- 0 until remaining) {
      out.writeByte(0)
    }
  }

  def write(out: FSDataOutputStream): Unit = {
    writeString(name, INDEX_META_NAME_LENGTH, out)
    val keyBits = BitSet.empty
    val dirBits = BitSet.empty
    indexType match {
      case BTreeIndex(entries) =>
        out.writeByte(BTREE_INDEX_TYPE)
        writeBTreeIndexEntries(entries, INDEX_META_KEY_LENGTH * 2, out)
      case BitMapIndex(entries) =>
        out.writeByte(BITMAP_INDEX_TYPE)
        entries.foreach(keyBits += _)
        writeBitSet(keyBits, INDEX_META_KEY_LENGTH, out)
        writeBitSet(dirBits, INDEX_META_KEY_LENGTH, out)
      case HashIndex(entries) =>
        out.writeByte(HASH_INDEX_TYPE)
        entries.foreach(keyBits += _)
        writeBitSet(keyBits, INDEX_META_KEY_LENGTH, out)
        writeBitSet(dirBits, INDEX_META_KEY_LENGTH, out)
    }
  }

  def read(in: FSDataInputStream): Unit = {
    var readPos = in.getPos
    name = in.readUTF()
    readPos += INDEX_META_NAME_LENGTH

    in.seek(readPos)
    val indexTypeFlag = in.readByte()

    indexType = indexTypeFlag match {
      case BTREE_INDEX_TYPE =>
        val size = in.readInt()
        val data = (0 until size).map(_ => in.readInt())
        BTreeIndex(data.map(d =>
          BTreeIndexEntry(math.abs(d) - 1, if (d > 0) Ascending else Descending)))
      case flag =>
        val bitMask = new Array[Long](INDEX_META_KEY_LENGTH / 8)
        val keyBits = {
          for (j <- 0 until INDEX_META_KEY_LENGTH / 8) {
            bitMask(j) = in.readLong()
          }
          BitSet.fromBitMask(bitMask)
        }
        flag match {
          case BITMAP_INDEX_TYPE => BitMapIndex(keyBits.toSeq)
          case HASH_INDEX_TYPE => HashIndex(keyBits.toSeq)
        }
    }
  }
}

private[spinach] object IndexMeta {
  final val BTREE_INDEX_TYPE = 0
  final val BITMAP_INDEX_TYPE = 1
  final val HASH_INDEX_TYPE = 2

  def apply() : IndexMeta = new IndexMeta()
  def apply(name: String, indexType: IndexType): IndexMeta = {
    val indexMeta = new IndexMeta()
    indexMeta.name = name
    indexMeta.indexType = indexType
    indexMeta
  }
}

private[spinach] case class Version(major: Byte, minor: Byte, revision: Byte)

private[spinach] class FileHeader {
  import DataSourceMeta._

  var recordCount: Long = _
  var dataFileCount: Long = _
  var indexCount: Long = _

  def write(out: FSDataOutputStream): Unit = {
    out.writeLong(recordCount)
    out.writeLong(dataFileCount)
    out.writeLong(indexCount)
    out.writeByte(VERSION.major)
    out.writeByte(VERSION.minor)
    out.writeByte(VERSION.revision)
    out.write(MAGIC_NUMBER.getBytes(StandardCharsets.UTF_8))
  }

  def read(in: FSDataInputStream): Unit = {
    recordCount = in.readLong()
    dataFileCount = in.readLong()
    indexCount = in.readLong()
    val version = Version(in.readByte(), in.readByte(), in.readByte())
    val buffer = new Array[Byte](MAGIC_NUMBER.length)
    in.readFully(buffer)
    val magicNumber = new String(buffer, StandardCharsets.UTF_8)
    if (magicNumber != MAGIC_NUMBER) {
      throw new IOException("Not a valid Spinach meta file.")
    }
    if (version != VERSION) {
      throw new IOException("The Spinach meta file version is not compatible.")
    }
  }
}

private[spinach] object FileHeader {
  def apply(): FileHeader = new FileHeader()
  def apply(recordCount: Long, dataFileCount: Long, indexCount: Long): FileHeader = {
    val fileHeader = new FileHeader()
    fileHeader.recordCount = recordCount
    fileHeader.dataFileCount = dataFileCount
    fileHeader.indexCount = indexCount
    fileHeader
  }
}

private[spinach] case class DataSourceMeta(
    @transient fileMetas: Array[FileMeta],
    indexMetas: Array[IndexMeta],
    schema: StructType,
    dataReaderClassName: String,
    @transient fileHeader: FileHeader) extends Serializable {

   def isSupportedByIndex(exp: Expression,
                          hashSetList: mutable.ListBuffer[mutable.HashSet[String]]): Boolean = {
     val bTreeSet: mutable.HashSet[String] = hashSetList(0)
     val bitmapSet: mutable.HashSet[String] = hashSetList(1)
     var attr: String = null
     def checkAttribute(filter: Expression): Boolean = filter match {
       case Or(left, right) =>
         checkAttribute(left) && checkAttribute(right)
       case And(left, right) =>
         checkAttribute(left) && checkAttribute(right)
       case EqualTo(attrRef: AttributeReference, _) =>
         if (attr ==  null || attr == attrRef.name) {
           attr = attrRef.name
           bTreeSet.contains(attr) || bitmapSet.contains(attr)
         } else false
       case LessThan(attrRef: AttributeReference, _) =>
         if (attr ==  null || attr == attrRef.name) {
           attr = attrRef.name
           bTreeSet.contains(attr) || bitmapSet.contains(attr)
         } else false
       case LessThanOrEqual(attrRef: AttributeReference, _) =>
         if (attr ==  null || attr == attrRef.name) {
           attr = attrRef.name
           bTreeSet.contains(attr) || bitmapSet.contains(attr)
         } else false
       case GreaterThan(attrRef: AttributeReference, _) =>
         if (attr ==  null || attr == attrRef.name) {
           attr = attrRef.name
           bTreeSet.contains(attr) || bitmapSet.contains(attr)
         } else false
       case GreaterThanOrEqual(attrRef: AttributeReference, _) =>
         if (attr ==  null || attr == attrRef.name) {
           attr = attrRef.name
           bTreeSet.contains(attr) || bitmapSet.contains(attr)
         } else false
       case _ => false
     }

     checkAttribute(exp)
  }
}

private[spinach] class DataSourceMetaBuilder {
  val fileMetas = ArrayBuffer.empty[FileMeta]
  val indexMetas = ArrayBuffer.empty[IndexMeta]
  var schema: StructType = new StructType()
  var dataReaderClassName: String = classOf[SpinachDataFile].getCanonicalName

  def addFileMeta(fileMeta: FileMeta): this.type = {
    fileMetas += fileMeta
    this
  }

  def addIndexMeta(indexMeta: IndexMeta): this.type = {
    indexMetas += indexMeta
    this
  }

  def containsFileMeta(fileMeta: FileMeta): Boolean = {
    fileMetas.indexWhere{_.dataFileName == fileMeta.dataFileName} >= 0
  }

  def containsFileMeta(fileName: String): Boolean = {
    fileMetas.indexWhere{_.dataFileName == fileName} >= 0
  }

  def containsIndexMeta(indexMeta: IndexMeta): Boolean = {
    indexMetas.indexWhere{_.name == indexMeta.name} >= 0
  }

  def withNewSchema(schema: StructType): this.type = {
    this.schema = schema
    this
  }

  def withNewDataReaderClassName(clsName: String): this.type = {
    this.dataReaderClassName = clsName
    this
  }

  def build(): DataSourceMeta = {
    val fileHeader = FileHeader(fileMetas.map(_.recordCount).sum, fileMetas.size, indexMetas.size)
    DataSourceMeta(fileMetas.toArray, indexMetas.toArray, schema, dataReaderClassName, fileHeader)
  }
}

private[spinach] object DataSourceMeta {
  final val MAGIC_NUMBER = "FIBER"
  final val VERSION = Version(1, 0, 0)
  final val FILE_HEAD_LEN = 32

  final val FILE_META_START_OFFSET = 0
  final val FILE_META_LENGTH = 512
  final val FILE_META_FINGERPRINT_LENGTH = 248
  final val FILE_META_DATA_FILE_NAME_LENGTH = 256

  final val INDEX_META_LENGTH = 768
  final val INDEX_META_NAME_LENGTH = 255
  final val INDEX_META_TYPE_LENGTH = 1
  final val INDEX_META_KEY_LENGTH = 256

  private def readFileHeader(file: FileStatus, in: FSDataInputStream): FileHeader = {
    if (file.getLen < FILE_HEAD_LEN) {
      throw new IOException(s" ${file.getPath} is not a valid Spinach meta file.")
    }
    in.seek(file.getLen - FILE_HEAD_LEN)
    val fileHeader = FileHeader()
    fileHeader.read(in)
    fileHeader
  }

  private def readFileMetas(fileHeader: FileHeader, in: FSDataInputStream): Array[FileMeta] = {
    val dataFileCount = fileHeader.dataFileCount.toInt
    val fileMetas = new Array[FileMeta](dataFileCount)
    var readPos = FILE_META_START_OFFSET

    for (i <- 0 until dataFileCount) {
      val readPos = FILE_META_START_OFFSET + FILE_META_LENGTH * i
      in.seek(readPos)
      fileMetas(i) = FileMeta()
      fileMetas(i).read(in)
    }
    fileMetas
  }

  private def readIndexMetas(fileHeader: FileHeader, in: FSDataInputStream): Array[IndexMeta] = {
    val indexCount = fileHeader.indexCount.toInt
    val indexMetas = new Array[IndexMeta](indexCount)

    for (i <- 0 until indexCount) {
      val readPos = FILE_META_START_OFFSET + FILE_META_LENGTH * fileHeader.dataFileCount +
        INDEX_META_LENGTH * i
      in.seek(readPos)
      indexMetas(i) = IndexMeta()
      indexMetas(i).read(in)
    }
    indexMetas
  }

  private def readSchema(fileHeader: FileHeader, in: FSDataInputStream) : StructType = {
    in.seek(FILE_META_START_OFFSET + FILE_META_LENGTH * fileHeader.dataFileCount +
      INDEX_META_LENGTH * fileHeader.indexCount)
    StructType.fromString(in.readUTF())
  }

  private def writeSchema(schema: StructType, out: FSDataOutputStream): Unit = {
    out.writeUTF(schema.json)
  }

  def writeString(value: String, totalSizeToWrite: Int, out: FSDataOutputStream): Unit = {
    val sizeBefore = out.size
    out.writeUTF(value)
    val sizeWritten = out.size - sizeBefore
    val remaining = totalSizeToWrite - sizeWritten
    assert(remaining >= 0,
      s"Failed to write $value as it exceeds the max allowed $totalSizeToWrite bytes.")
    for (i <- 0 until remaining) {
      out.writeByte(0)
    }
  }

  def initialize(path: Path, jobConf: Configuration): DataSourceMeta = {
    val fs = path.getFileSystem(jobConf)
    val file = fs.getFileStatus(path)
    val in = fs.open(path)

    val fileHeader = readFileHeader(file, in)
    val fileMetas = readFileMetas(fileHeader, in)
    val indexMetas = readIndexMetas(fileHeader, in)
    val schema = readSchema(fileHeader, in)
    val dataReaderClassName = in.readUTF()
    in.close()
    DataSourceMeta(fileMetas, indexMetas, schema, dataReaderClassName, fileHeader)
  }

  def write(
      path: Path,
      jobConf: Configuration,
      meta: DataSourceMeta,
      deleteIfExits: Boolean = true): Unit = {
    val fs = path.getFileSystem(jobConf)

    if (fs.exists(path) && !deleteIfExits) {
      throw new FileAlreadyExistsException(s"File $path already exists.")
    }

    val rn_path = new Path(path.getName + "_bk")

    val out = fs.create(rn_path)
    meta.fileMetas.foreach(_.write(out))
    meta.indexMetas.foreach(_.write(out))
    writeSchema(meta.schema, out)
    out.writeUTF(meta.dataReaderClassName)
    meta.fileHeader.write(out)
    out.close()

    if (fs.exists(rn_path)) {
      if (fs.exists(path)) {
        fs.delete(path, true) // just in case it exists
      }
      if (!fs.rename(rn_path, path)) {
        throw new IOException(s"Could not rename from $rn_path to $path")
      }
    } else {
      throw new IOException(s"Could not create $rn_path")
    }
  }

  def newBuilder() : DataSourceMetaBuilder = {
    new DataSourceMetaBuilder
  }
}
