/**
 * Copyright (C) 2009 Alexander Azarov <azarov@osinka.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.osinka.mongodb

import org.bson.types.ObjectId
import com.mongodb._
import wrapper._

/**
 * Scala collection of objects T backed by MongoDB DBCollection.
 *
 * @see com.osinka.mongodb.shape.ShapedCollection
 */
trait MongoCollection[T] extends PartialFunction[ObjectId, T] with Iterable[T] with DBCollectionWrapper {
    /**
     * Serializer for objects of type <code>T</code>
     */
    def serializer: Serializer[T]

    protected def cursor(q: Query) = {
        val cursor = find(q.query)
        for {n <- q.skip } cursor.skip(n)
        for {n <- q.limit} cursor.limit(n)
        for {sort <- q.sorting} cursor.sort(sort)
        // TODO: snapshot mode
        cursor
    }

    protected def find(q: Query): Iterator[T] =
        new DBObjectIterator(cursor(q)).flatMap{serializer.out(_).toList.iterator}

    protected def findOne(q: Query): Option[T] =
        if (q.slice_?) find(q take 1).toSeq.headOption
        else Option(findOne(q.query)).flatMap{serializer.out}

    protected def getCount(q: Query): Long = {
        def lim(n: Int) = q.limit map{_ min n} getOrElse n
        def skp(n: Int) = q.skip map{x => (n - x) max 0} getOrElse n

        if (q.slice_?) lim(skp(cursor(q).count))
        else getCount(q.query)
    }

    /**
     * Generic update method, not for public usage. See MongoDB's update
     */
    protected def update(q: DBObject, op: DBObject, multi: Boolean): Boolean = {
        underlying.update(q, op, false, multi)
        underlying.getDB.getLastError get "updatedExisting" match {
            case null => false
            case b: java.lang.Boolean => b.booleanValue
        }
    }

    protected def remove(q: DBObject) {
        underlying remove q
    }

    /**
     * Returns iterator through collection objects.
     *
     * This method should not be used
     * explicitly, use Scala <code>Iterable[T]</code> methods instead.
     */
    protected def find: Iterator[T] = find(Query.empty)

    /**
     * Collection size estimate. Rough size estimates the collection size: it does
     * not take object shape into account. Do not use this method, use methods from
     * Scala <tt>Iterable[T]</tt>
     */
    protected def sizeEstimate = getCount(Query.empty)

    /**
     * MongoDB <code>insert</code> method
     * @param x object to insert into the collection
     */
    def <<(x: T) {
        val dbo = serializer.in(x)
        underlying insert dbo
        serializer.mirror(x)(dbo)
    }

    /**
     * MongoDB <code>insert</code> with subsequent check for object existance
     * @param x object to insert into the collection
     * @return <code>None</code> if such object exists already (with the same identity);
     * <code>Some(x)</code> in the case of success.
     */
    def <<?(x: T): Option[T] = {
        val dbo = serializer.in(x)
        underlying insert dbo
        underlying.getDB.getLastError get "err" match {
            case null => Some( serializer.mirror(x)(dbo) )
            case msg: String => None
        }
    }

    /**
     * MongoDB DBCollection.save method
     * @param x object to save to the collection
     */
    def +=(x: T) {
        val dbo = serializer.in(x)
        underlying save dbo
        serializer.mirror(x)(dbo)
    }

    /**
     * MongoDB DBCollection.remove method
     * @param x object to remove from the collection
     */
    def -=(x: T) { underlying remove serializer.in(x) }

    /**
     * MongoDB DBCollection.remove method
     * @param q query
     */
    def -=(q: Query) { remove(q.query) }

    /**
     * MongoDB DBCollection.update method
     * @param q filter, which objects to update
     * @param op set of modify operations in the form of Scala Map
     * @param multi update only one object if <tt>false</tt> or update
     * all matching objects if <tt>true</tt>
     * @return <tt>true</tt> if any objects has been updated
     * @todo TODO: update -> foreach?..
     */
    def update(q: Query, op: Map[String,Any], multi: Boolean): Boolean = update(q.query, DBO.fromMap(op), multi)

    def get(oid: ObjectId): Option[T] = findOne(Query byId oid)

    // -- PartialFunction[ObjectId, T]
    override def isDefinedAt(oid: ObjectId) = getCount(Query byId oid) > 0

    override def apply(oid: ObjectId) = get(oid).get

    // -- Collection[T]
    override def iterator: Iterator[T] = find

    override def headOption = findOne(Query.empty)

    /**
     * Size of the collection. <strong>Note</strong>: Original MongoDB cursor
     * reports collection's size regardless
     * of skip and limit modificators. This implementation takes these into
     * account: you may expect to get accurate collection length when
     * you called drop and take on Query object.
     * <br/>
     * Beware: narrowing as Long value of getCount is cast to Int
     */
    override def size: Int = length
    def length: Int = sizeEstimate.toInt

    override def stringPrefix: String = "MongoCollection"

    /**
     * toString method in Iterable lists all the elements, which can be a
     * problem: collections can store a lot of documents in MongoDB. Thus the
     * method is overridden to display the collection's size only
     */
    override def toString = stringPrefix+"("+getName+"):"+size
}