/*
 * Copyright 2012-2013 Stephane Godbillon (@sgodbillon) and Zenexity
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivemongo.api.indexes

import reactivemongo.core.protocol.MongoWireVersion
import reactivemongo.api._
import reactivemongo.bson._
import reactivemongo.api.commands.{ DropIndexes, LastError, WriteResult }
import reactivemongo.utils.option
import scala.concurrent.{ Future, ExecutionContext }

/** Type of Index */
sealed trait IndexType {
  /** Value of the index (`{fieldName: value}`). */
  def value: BSONValue
  private[indexes] def valueStr: String
}

object IndexType {
  object Ascending extends IndexType {
    def value = BSONInteger(1)
    def valueStr = "1"
  }

  object Descending extends IndexType {
    def value = BSONInteger(-1)
    def valueStr = "-1"
  }

  object Geo2D extends IndexType {
    def value = BSONString("2d")
    def valueStr = "2d"
  }

  object Geo2DSpherical extends IndexType {
    def value = BSONString("2dsphere")
    def valueStr = "2dsphere"
  }

  object GeoHaystack extends IndexType {
    def value = BSONString("geoHaystack")
    def valueStr = "geoHaystack"
  }

  object Hashed extends IndexType {
    def value = BSONString("hashed")
    def valueStr = "hashed"
  }

  object Text extends IndexType {
    def value = BSONString("text")
    def valueStr = "text"
  }

  def apply(value: BSONValue) = value match {
    case BSONInteger(i) if i > 0             => Ascending
    case BSONInteger(i) if i < 0             => Descending
    case BSONDouble(i) if i > 0              => Ascending
    case BSONDouble(i) if i < 0              => Descending
    case BSONLong(i) if i > 0                => Ascending
    case BSONLong(i) if i < 0                => Descending
    case BSONString(s) if s == "2d"          => Geo2D
    case BSONString(s) if s == "2dsphere"    => Geo2DSpherical
    case BSONString(s) if s == "geoHaystack" => GeoHaystack
    case BSONString(s) if s == "hashed"      => Hashed
    case BSONString(s) if s == "text"        => Text
    case _                                   => throw new IllegalArgumentException("unsupported index type")
  }
}

/**
 * A MongoDB index (excluding the namespace).
 *
 * Consider reading [[http://www.mongodb.org/display/DOCS/Indexes the documentation about indexes in MongoDB]].
 *
 * @param key The index key (it can be composed of multiple fields). This list should not be empty!
 * @param name The name of this index. If you provide none, a name will be computed for you.
 * @param unique Enforces uniqueness.
 * @param background States if this index should be built in background. You should read [[http://www.mongodb.org/display/DOCS/Indexes#Indexes-background%3Atrue the documentation about background indexing]] before using it.
 * @param dropDups States if duplicates should be discarded (if unique = true). Warning: you should read [[http://www.mongodb.org/display/DOCS/Indexes#Indexes-dropDups%3Atrue the documentation]].
 * @param sparse States if the index to build should only consider the documents that have the indexed fields. See [[http://www.mongodb.org/display/DOCS/Indexes#Indexes-sparse%3Atrue the documentation]] on the consequences of such an index.
 * @param version Indicates the [[http://www.mongodb.org/display/DOCS/Index+Versions version]] of the index (1 for >= 2.0, else 0). You should let MongoDB decide.
 * @param options Optional parameters for this index (typically specific to an IndexType like Geo2D).
 */
case class Index(
    key: Seq[(String, IndexType)],
    name: Option[String] = None,
    unique: Boolean = false,
    background: Boolean = false,
    dropDups: Boolean = false, // Deprecated since 2.6, TODO: Remove
    sparse: Boolean = false,
    version: Option[Int] = None, // let MongoDB decide
    // TODO: storageEngine (new for Mongo3)
    options: BSONDocument = BSONDocument()) {
  /** The name of the index (a default one is computed if none). */
  lazy val eventualName: String = name.getOrElse(key.foldLeft("") { (name, kv) =>
    name + (if (name.length > 0) "_" else "") + kv._1 + "_" + kv._2.valueStr
  })
}

/**
 * A MongoDB namespaced index.
 * A MongoDB index is composed with the namespace (the fully qualified collection name) and the other fields of [[reactivemongo.api.indexes.Index]].
 *
 * Consider reading [[http://www.mongodb.org/display/DOCS/Indexes the documentation about indexes in MongoDB]].
 *
 * @param namespace The fully qualified name of the indexed collection.
 * @param index The other fields of the index.
 */
case class NSIndex(namespace: String, index: Index) {
  val (dbName: String, collectionName: String) = {
    val spanned = namespace.span(_ != '.')
    spanned._1 -> spanned._2.drop(1)
  }
}

/** Indexes manager at database level. */
sealed trait IndexesManager {

  /** Gets a future list of all the index on this database. */
  def list(): Future[List[NSIndex]]

  /**
   * Creates the given index only if it does not exist on this database.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param nsIndex The index to create.
   *
   * @return a future containing true if the index was created, false if it already exists.
   */
  def ensure(nsIndex: NSIndex): Future[Boolean]

  /**
   * Creates the given index.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param nsIndex The index to create.
   */
  def create(nsIndex: NSIndex): Future[WriteResult]

  /**
   * Deletes the given index on the given collection.
   *
   * @return The deleted index number.
   */
  @deprecated("Use drop instead", "0.11.0")
  def delete(nsIndex: NSIndex): Future[Int] =
    delete(nsIndex.collectionName, nsIndex.index.eventualName)

  /**
   * Deletes the given index on the given collection.
   *
   * @return The number of indexes that were dropped.
   */
  @deprecated("Use drop instead", "0.11.0")
  def delete(collectionName: String, indexName: String): Future[Int] =
    drop(collectionName, indexName)

  /**
   * Drops the given index on the given collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(nsIndex: NSIndex): Future[Int] =
    drop(nsIndex.collectionName, nsIndex.index.eventualName)

  /**
   * Drops the given index on the given collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(collectionName: String, indexName: String): Future[Int]

  /**
   * Drops all the indexes on the given collection.
   */
  def dropAll(collectionName: String): Future[Int]

  /** Gets a manager for the given collection. */
  def onCollection(name: String): CollectionIndexesManager
}

/**
 * A helper class to manage the indexes on a Mongo 2.x database.
 *
 * @param db The subject database.
 */
final class LegacyIndexesManager(db: DB)(
    implicit context: ExecutionContext) extends IndexesManager {

  val collection = db("system.indexes")

  def list(): Future[List[NSIndex]] = collection.find(BSONDocument()).cursor(db.connection.options.readPreference)(IndexesManager.NSIndexReader, context, CursorProducer.defaultCursorProducer).collect[List]()

  def ensure(nsIndex: NSIndex): Future[Boolean] = {
    val query = BSONDocument(
      "ns" -> BSONString(nsIndex.namespace),
      "name" -> BSONString(nsIndex.index.eventualName))

    collection.find(query).one.flatMap { idx =>
      if (!idx.isDefined)
        create(nsIndex).map(_ => true)
      // there is a match, returning a future ok. TODO
      else Future.successful(false)
    }
  }

  def create(nsIndex: NSIndex): Future[WriteResult] = {
    implicit val writer = IndexesManager.NSIndexWriter
    collection.insert(nsIndex)
  }

  def drop(collectionName: String, indexName: String): Future[Int] = {
    import reactivemongo.api.commands.bson.BSONDropIndexesImplicits._
    db.collection(collectionName).runValueCommand(DropIndexes(indexName))
  }

  def dropAll(collectionName: String): Future[Int] = drop(collectionName, "*")

  def onCollection(name: String): CollectionIndexesManager =
    new LegacyCollectionIndexesManager(db.name, name, this)
}

/**
 * A helper class to manage the indexes on a Mongo 3.x database.
 *
 * @param db The subject database.
 */
final class DefaultIndexesManager(db: DB with DBMetaCommands)(
    implicit context: ExecutionContext) extends IndexesManager {

  import reactivemongo.api.commands.ListIndexes

  private def listIndexes(collections: List[String], indexes: List[NSIndex]): Future[List[NSIndex]] = collections match {
    case c :: cs => onCollection(c).list().flatMap(ix =>
      listIndexes(cs, indexes ++ ix.map(NSIndex(s"${db.name}.$c", _))))
    case _ => Future.successful(indexes)
  }

  def list(): Future[List[NSIndex]] =
    db.collectionNames.flatMap(listIndexes(_, Nil))

  def ensure(nsIndex: NSIndex): Future[Boolean] =
    onCollection(nsIndex.collectionName).ensure(nsIndex.index)

  def create(nsIndex: NSIndex): Future[WriteResult] =
    onCollection(nsIndex.collectionName).create(nsIndex.index)

  def drop(collectionName: String, indexName: String): Future[Int] =
    onCollection(collectionName).drop(indexName)

  def dropAll(collectionName: String): Future[Int] =
    onCollection(collectionName).dropAll()

  def onCollection(name: String): CollectionIndexesManager =
    new DefaultCollectionIndexesManager(db, name)
}

sealed trait CollectionIndexesManager {
  /** Returns the list of indexes for the current collection. */
  def list(): Future[List[Index]]

  /**
   * Creates the given index only if it does not exist on this collection.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param index The index to create.
   *
   * @return a future containing true if the index was created, false if it already exists.
   */
  def ensure(index: Index): Future[Boolean]

  /**
   * Creates the given index.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param index The index to create.
   */
  def create(index: Index): Future[WriteResult]

  /**
   * Deletes the given index on that collection.
   *
   * @return The deleted index number.
   */
  @deprecated("Use drop instead", "0.11.0")
  def delete(index: Index): Future[Int]

  /**
   * Deletes the given index on that collection.
   *
   * @return The deleted index number.
   */
  @deprecated("Use drop instead", "0.11.0")
  def delete(name: String): Future[Int]

  /**
   * Drops the given index on that collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(nsIndex: NSIndex): Future[Int]

  /**
   * Drops the given index on that collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(indexName: String): Future[Int]

  /**
   * Drops all the indexes on that collection.
   */
  def dropAll(): Future[Int]
}

private class LegacyCollectionIndexesManager(
    db: String, collectionName: String, legacy: LegacyIndexesManager)(
        implicit context: ExecutionContext) extends CollectionIndexesManager {

  val fqName = db + "." + collectionName

  def list(): Future[List[Index]] =
    legacy.list.map(_.filter(_.namespace == fqName).map(_.index))

  def ensure(index: Index): Future[Boolean] =
    legacy.ensure(NSIndex(fqName, index))

  def create(index: Index): Future[WriteResult] =
    legacy.create(NSIndex(fqName, index))

  @deprecated("Use drop instead", "0.11.0")
  def delete(index: Index) = drop(NSIndex(fqName, index))

  @deprecated("Use drop instead", "0.11.0")
  def delete(name: String) = drop(name)

  @deprecated("Use [[IndexesManager.drop]]", "0.11.0")
  def drop(nsIndex: NSIndex): Future[Int] = legacy.drop(nsIndex)

  def drop(indexName: String): Future[Int] =
    legacy.drop(collectionName, indexName)

  def dropAll(): Future[Int] = legacy.dropAll(collectionName)
}

private class DefaultCollectionIndexesManager(db: DB, collectionName: String)(
    implicit context: ExecutionContext) extends CollectionIndexesManager {

  import reactivemongo.api.commands.{
    CreateIndexes,
    Command,
    ListIndexes
  }
  import reactivemongo.api.commands.bson.BSONListIndexesImplicits._
  import reactivemongo.api.commands.bson.BSONCreateIndexesImplicits._

  private lazy val collection = db(collectionName)
  private lazy val listCommand = ListIndexes(db.name)

  def list(): Future[List[Index]] =
    Command.run(BSONSerializationPack)(collection, listCommand) recoverWith {
      case err: WriteResult if err.code.exists(_ == 26 /* no database */ ) =>
        Future.successful(List.empty[Index])

      case err => Future.failed(err)
    }

  def ensure(index: Index): Future[Boolean] = for {
    is <- list().map(_.dropWhile(_.key != index.key).headOption.isDefined)
    cr <- if (!is) create(index).map(_ => true) else Future.successful(false)
  } yield cr

  def create(index: Index): Future[WriteResult] =
    Command.run(BSONSerializationPack)(collection,
      CreateIndexes(db.name, List(index)))

  @deprecated("Use drop instead", "0.11.0")
  def delete(index: Index) = drop(index.eventualName)

  @deprecated("Use drop instead", "0.11.0")
  def delete(name: String) = drop(name)

  @deprecated("Use [[IndexesManager.drop]]", "0.11.0")
  def drop(nsIndex: NSIndex): Future[Int] = {
    import reactivemongo.api.commands.bson.BSONDropIndexesImplicits._
    Command.run(BSONSerializationPack)(
      db(nsIndex.collectionName), DropIndexes(nsIndex.index.eventualName)).
      map(_.value)
  }

  def drop(indexName: String): Future[Int] = {
    import reactivemongo.api.commands.bson.BSONDropIndexesImplicits._
    Command.run(BSONSerializationPack)(collection, DropIndexes(indexName)).
      map(_.value)
  }

  @inline def dropAll(): Future[Int] = drop("*")
}

/** Factory for indexes manager scoped with a specified collection. */
object CollectionIndexesManager {
  /**
   * Returns an indexes manager for specified collection.
   *
   * @param db the database
   * @param collectionName the collection name
   */
  def apply(db: DB, collectionName: String)(implicit context: ExecutionContext): CollectionIndexesManager = {
    val wireVer = db.connection.metadata.map(_.maxWireVersion)

    if (wireVer.exists(_ == MongoWireVersion.V30)) {
      new DefaultCollectionIndexesManager(db, collectionName)
    } else new LegacyCollectionIndexesManager(db.name, collectionName,
      new LegacyIndexesManager(db))
  }
}

object IndexesManager {
  /**
   * Returns an indexes manager for specified database.
   *
   * @param db the database
   */
  def apply(db: DB with DBMetaCommands)(implicit context: ExecutionContext): IndexesManager = {
    val wireVer = db.connection.metadata.map(_.maxWireVersion)

    if (wireVer.exists(_ == MongoWireVersion.V30)) new DefaultIndexesManager(db)
    else new LegacyIndexesManager(db)
  }

  protected def toBSONDocument(nsIndex: NSIndex) = {
    BSONDocument(
      "ns" -> BSONString(nsIndex.namespace),
      "name" -> BSONString(nsIndex.index.eventualName),
      "key" -> BSONDocument(
        (for (kv <- nsIndex.index.key)
          yield kv._1 -> kv._2.value).toStream),
      "background" -> option(nsIndex.index.background, BSONBoolean(true)),
      "dropDups" -> option(nsIndex.index.dropDups, BSONBoolean(true)),
      "sparse" -> option(nsIndex.index.sparse, BSONBoolean(true)),
      "unique" -> option(nsIndex.index.unique, BSONBoolean(true))) ++ nsIndex.index.options
  }

  implicit object NSIndexWriter extends BSONDocumentWriter[NSIndex] {
    def write(nsIndex: NSIndex): BSONDocument = {
      if (nsIndex.index.key.isEmpty)
        throw new RuntimeException("the key should not be empty!")
      toBSONDocument(nsIndex)
    }
  }

  implicit object IndexReader extends BSONDocumentReader[Index] {
    def read(doc: BSONDocument): Index = doc.getAs[BSONDocument]("key").map(
      _.elements.map { elem => elem._1 -> IndexType(elem._2) }.toList).
      fold[Index](throw new Exception("the key must be defined")) { key =>
        val options = doc.elements.filterNot { element =>
          element._1 == "ns" || element._1 == "key" || element._1 == "name" ||
            element._1 == "unique" || element._1 == "background" ||
            element._1 == "dropDups" || element._1 == "sparse" ||
            element._1 == "v"
        }.toSeq

        Index(key,
          // TODO: read storageEngine property (since MongoDB 3)
          doc.getAs[BSONString]("name").map(_.value),
          doc.getAs[BSONBoolean]("unique").map(_.value).getOrElse(false),
          doc.getAs[BSONBoolean]("background").map(_.value).getOrElse(false),
          doc.getAs[BSONBoolean]("dropDups").map(_.value).getOrElse(false),
          doc.getAs[BSONBoolean]("sparse").map(_.value).getOrElse(false),
          doc.getAs[BSONNumberLike]("v").map(_.toInt),
          BSONDocument(options.toStream))
      }
  }

  implicit object NSIndexReader extends BSONDocumentReader[NSIndex] {
    def read(doc: BSONDocument): NSIndex =
      doc.getAs[BSONString]("ns").map(_.value).fold[NSIndex](
        throw new Exception("the namespace ns must be defined"))(
          NSIndex(_, doc.as[Index]))
  }
}
