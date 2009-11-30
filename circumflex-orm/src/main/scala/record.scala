package ru.circumflex.orm

import core.HashModel
import collection.mutable.HashMap
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Contains base functionality for objects that can be retrieved from and
 * persisted to database relations.
 * There's a couple of things one must know about records.
 * <ul>
 * <li>Each record instance "knows" about it's main relation through
 * <code>relation</code> method.</li>
 * <li>Records carry the data around using <em>fields</em; internally they are
 * stored in <code>fieldsMap</code> in a "column-to-value" form.</li>
 * <li>Each record has a primary key field which identifies the record in database.
 * The <code>isIdentified</code> method determines, whether primary key field is set.</li>
 * <li>Two records are considered equal if their relations and primary key
 * fields are equal. If they are not identified, the internally generated uuid is
 * used for equality testing (so unidentified records never match each other).</li>
 * </ul>
 */
abstract class Record[R] extends JDBCHelper with HashModel {
  private val uuid = UUID.randomUUID.toString

  val fieldsMap = HashMap[Column[_, R], Any]()
  val manyToOneMap = HashMap[Association[R, _], Any]()
  val oneToManyMap = new HashMap[Association[_, R], Seq[Any]]() {
    override def default(key: Association[_, R]): Seq[Any] = Nil
  }

  def relation: Relation[R]

  def primaryKey: Option[_] = fieldsMap.get(relation.primaryKey.column)

  def isIdentified = primaryKey != None

  def get(key: String): Option[Any] = {
    // if key matches column name return a field
    relation.columns.find(_.columnName == key) match {
      case Some(col) => return getField(col)
      case _ =>
    }
    // if key matches relation name of association, return their results
    // TODO
    return None
  }

  /* FIELDS-RELATED STUFF */

  def field[T](col: Column[T, R]) = new Field(this, col)

  def getField[T](col: Column[T, R]): Option[T] =
    fieldsMap.get(col).asInstanceOf[Option[T]]

  def setField[T](col: Column[T, R], value: T): Unit =
    setField(col, Some(value))

  def setField[T](col: Column[T, R], value: Option[T]) = {
    value match {
      case Some(value) => fieldsMap += (col -> value)
      case _ => fieldsMap -= col
    }
    // invalidate associated many-to-one caches
    manyToOneMap.keys.filter(_.localColumn == col).foreach(manyToOneMap -= _)
    // invalidate one-to-many caches if identifier changed
    if (col == relation.primaryKey.column) oneToManyMap.clear
  }

  /* ASSOCIATIONS-RELATED STUFF */

  def manyToOne[P](association: Association[R, P]) =
    new ManyToOne[R, P](this, association)

  def getManyToOne[P](a: Association[R, P]): Option[P] =
    manyToOneMap.get(a) match {
      case Some(value : P) => Some(value)   // parent is already in cache
      case _ => {
        getField(a.localColumn) match {     // lazy-fetch a parent
          case Some(localVal) => a.fetchManyToOne(localVal) match {
            case Some(mto : P) =>
              manyToOneMap += (a -> mto)
              Some(mto)
            case _ => None
          } case _ => None
        }
      }
    }

  def setManyToOne[P](a: Association[R, P], value: P): Unit =
    setManyToOne(a, Some(value))

  def setManyToOne[P](a: Association[R, P], value: Option[P]): Unit = value match {
    case Some(value: P) => {
      manyToOneMap += (a -> value)
      setField(a.localColumn.asInstanceOf[Column[Any, R]], value.asInstanceOf[Record[P]].primaryKey)
    }
    case None => {
      manyToOneMap -= a
      setField(a.localColumn, None)
    }
  }

  def oneToMany[C](association: Association[C, R]) =
    new OneToMany[C, R](this, association)

  def getOneToMany[C](a: Association[C, R]): Seq[C] =
    oneToManyMap.apply(a) match {
      case Nil => primaryKey match {     // no cached children yet
          case Some(refVal) => {         // lazy-fetch children if identified
            val children = a.fetchOneToMany(refVal)
            oneToManyMap += (a -> children)
            children
          }
          case _ => Nil
        }
      case seq => seq.asInstanceOf[Seq[C]]   // children are already in cache
    }

  def setOneToMany[C](a: Association[C, R], value: Seq[C]): Unit = {
    oneToManyMap += (a -> value)
  }

  /* PERSISTENCE-RELATED STUFF */

  def validate(): Option[Seq[ValidationError]] = relation.validate(this)

  def validate_!(): Unit = relation.validate_!(this)

  def insert(): Int = {
    validate_!()
    insert_!()
  }

  def insert_!(): Int = {
    val conn = relation.connectionProvider.getConnection
    val sql = relation.dialect.insertRecord(this)
    sqlLog.debug(sql)
    auto(conn.prepareStatement(sql))(st => {
      setParams(st, relation.columns)
      return st.executeUpdate
    })
  }

  def update(): Int = {
    validate_!()
    update_!()
  }

  def update_!(): Int = {
    val conn = relation.connectionProvider.getConnection
    val sql = relation.dialect.updateRecord(this)
    sqlLog.debug(sql)
    auto(conn.prepareStatement(sql))(st => {
      setParams(st, relation.nonPKColumns)
      relation.typeConverter.write(
        st,
        primaryKey.get,
        relation.nonPKColumns.size + 1)
      return st.executeUpdate
    })
  }

  def save(): Int = {
    validate_!()
    save_!()
  }

  def save_!(): Int =
    if (isIdentified) update_!()
    else {
      generateFields
      insert_!()
    }

  def delete(): Int = {
    val conn = relation.connectionProvider.getConnection
    val sql = relation.dialect.deleteRecord(this)
    sqlLog.debug(sql)
    auto(conn.prepareStatement(sql))(st => {
      relation.typeConverter.write(st, primaryKey.get, 1)
      return st.executeUpdate
    })
  }

  def generateFields(): Unit =
    relation.columns.flatMap(_.sequence).foreach(seq => {
      val nextval = seq.nextValue
      this.setField(seq.column, nextval)
    })

  private def setParams(st: PreparedStatement, cols: Seq[Column[_, R]]) =
    (0 until cols.size).foreach(ix => {
      val col = cols(ix)
      val value = this.getField(col) match {
        case Some(v) => v
        case _ => null
      }
      relation.typeConverter.write(st, value, ix + 1)
    })

  /* EQUALS BOILERPLATE */

  override def equals(obj: Any) = obj match {
    case r: Record[R] if (r.relation == this.relation) =>
      this.primaryKey.getOrElse(this.uuid) == r.primaryKey.getOrElse(r.uuid)
    case _ => false
  }

  override def hashCode = this.primaryKey.getOrElse(uuid).hashCode

  override def toString = relation.relationName + ": " + this.fieldsMap.toString
}

class Field[T, R](val record: Record[R],
                  val column: Column[T, R]) {

  def default(value: T): this.type = {
    set(value)
    return this
  }

  def get: Option[T] = record.getField(column)

  def getOrElse(defaultValue: T): T = get match {
    case Some(value) => value
    case _ => defaultValue
  }

  def set(value: T): Unit = record.setField(column, value)
  def setNull: Unit = record.setField(column, None)
  def <=(value: T): Unit = set(value)
  def :=(value: T): Unit = set(value)

  override def toString = get match {
    case Some(value) => value.toString
    case None => ""
  }
}

class ManyToOne[C, P](val record: Record[C],
                      val association: Association[C, P]) {

  def default(value: P): this.type = {
    set(value)
    return this
  }

  def get: Option[P] = record.getManyToOne(association)

  def getOrElse(defaultValue: P): P = get match {
    case Some(value) => value
    case _ => defaultValue
  }

  def set(value: P): Unit = record.setManyToOne(association, value)
  def setNull: Unit = record.setManyToOne(association, None)
  def <=(value: P): Unit = set(value)
  def :=(value: P): Unit = set(value)

  override def toString = get match {
    case Some(value) => value.toString
    case None => ""
  }

}

class OneToMany[C, P](val record: Record[P],
                      val association: Association[C, P]) {

  def default(values: C*): this.type = {
    set(values.toList)
    return this
  }

  def get: Seq[C] = record.getOneToMany(association)

  def set(value: Seq[C]): Unit = record.setOneToMany(association, value)
  def setNull: Unit = record.setOneToMany(association, Nil)
  def <=(value: Seq[C]): Unit = set(value)
  def :=(value: Seq[C]): Unit = set(value)

  override def toString = get.toString

}