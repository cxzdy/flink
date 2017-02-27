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
package org.apache.flink.table.runtime.aggregate

import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.ResultTypeQueryable
import org.apache.flink.types.Row
import org.apache.flink.configuration.Configuration
import org.apache.flink.table.functions.AggregateFunction
import org.apache.flink.util.Preconditions

class AggregateMapFunction[IN, OUT](
    private val aggregates: Array[AggregateFunction[_]],
    private val aggFields: Array[Int],
    private val groupingKeys: Array[Int],
    @transient private val returnType: TypeInformation[OUT])
  extends RichMapFunction[IN, OUT] with ResultTypeQueryable[OUT] {

  private var output: Row = _

  override def open(config: Configuration) {
    Preconditions.checkNotNull(aggregates)
    Preconditions.checkNotNull(aggFields)
    Preconditions.checkArgument(aggregates.length == aggFields.length)
    val partialRowLength = groupingKeys.length + aggregates.length
    output = new Row(partialRowLength)
  }

  override def map(value: IN): OUT = {

    val input = value.asInstanceOf[Row]
    for (i <- aggregates.indices) {
      val agg = aggregates(i)
      val accumulator = agg.createAccumulator()
      agg.accumulate(accumulator, input.getField(aggFields(i)))
      output.setField(groupingKeys.length + i, accumulator)
    }

    for (i <- groupingKeys.indices) {
      output.setField(i, input.getField(groupingKeys(i)))
    }
    output.asInstanceOf[OUT]
  }

  override def getProducedType: TypeInformation[OUT] = {
    returnType
  }
}
