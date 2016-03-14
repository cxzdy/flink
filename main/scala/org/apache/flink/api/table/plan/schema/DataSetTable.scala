/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.table.plan.schema

import java.lang.Double
import java.util
import java.util.Collections

import org.apache.calcite.rel.{RelCollation, RelDistribution}
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory}
import org.apache.calcite.schema.Statistic
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.calcite.util.ImmutableBitSet
import org.apache.flink.api.common.typeinfo.AtomicType
import org.apache.flink.api.common.typeutils.CompositeType
import org.apache.flink.api.java.DataSet
import org.apache.flink.api.table.typeutils.TypeConverter

class DataSetTable[T](
    val dataSet: DataSet[T],
    val fieldIndexes: Array[Int],
    val fieldNames: Array[String])
  extends AbstractTable {

  if (fieldIndexes.length != fieldNames.length) {
    throw new IllegalArgumentException(
      "Number of field indexes and field names must be equal.")
  }

  // check uniquenss of field names
  if (fieldNames.length != fieldNames.toSet.size) {
    throw new IllegalArgumentException(
      "Table field names must be unique.")
  }

  val fieldTypes: Array[SqlTypeName] =
    dataSet.getType match {
      case cType: CompositeType[T] =>
        if (fieldNames.length != cType.getArity) {
          throw new IllegalArgumentException(
          s"Arity of DataSet type (" + cType.getFieldNames.deep + ") " +
            "not equal to number of field names " + fieldNames.deep + ".")
        }
        fieldIndexes
          .map(cType.getTypeAt(_))
          .map(TypeConverter.typeInfoToSqlType(_))
      case aType: AtomicType[T] =>
        if (fieldIndexes.length != 1 || fieldIndexes(0) != 0) {
          throw new IllegalArgumentException(
            "Non-composite input type may have only a single field and its index must be 0.")
        }
        Array(TypeConverter.typeInfoToSqlType(aType))
    }

  override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
    val builder = typeFactory.builder
    fieldNames.zip(fieldTypes)
      .foreach( f => builder.add(f._1, f._2).nullable(true) )
    builder.build
  }

//  override def getStatistic: Statistic = {
//    new DefaultDataSetStatistic
//  }

}

class DefaultDataSetStatistic extends Statistic {

  override def getRowCount: Double = 1000d

  override def getCollations: util.List[RelCollation] = Collections.emptyList()

  override def isKey(columns: ImmutableBitSet): Boolean = false

  override def getDistribution: RelDistribution = null
}
