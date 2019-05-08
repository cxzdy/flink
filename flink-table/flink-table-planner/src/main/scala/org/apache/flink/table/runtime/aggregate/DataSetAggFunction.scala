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

import java.lang.Iterable

import org.apache.flink.api.common.functions.{MapPartitionFunction, RichGroupReduceFunction}
import org.apache.flink.configuration.Configuration
import org.apache.flink.table.codegen.{Compiler, GeneratedAggregationsFunction}
import org.apache.flink.table.runtime.ConcatKeyAndAggResultCollector
import org.apache.flink.table.util.Logging
import org.apache.flink.types.Row
import org.apache.flink.util.Collector

/**
  * [[RichGroupReduceFunction]] and [[MapPartitionFunction]] to compute aggregates that do
  * not support pre-aggregation for batch(DataSet) queries.
  *
  * @param genAggregations Code-generated [[GeneratedAggregations]]
  * @param numGroupingKey The number of grouping keys.
  * @param isTableAggregate Whether it is table aggregate.
  */
class DataSetAggFunction[F <: GeneratedAggregations](
    private val genAggregations: GeneratedAggregationsFunction,
    private val numGroupingKey: Int,
    private val isTableAggregate: Boolean)
  extends RichGroupReduceFunction[Row, Row]
    with MapPartitionFunction[Row, Row]
    with Compiler[F] with Logging {

  private var output: Row = _
  private var accumulators: Row = _
  private var concatCollector: ConcatKeyAndAggResultCollector[Row] = _

  private var function: F = _

  override def open(config: Configuration) {
    LOG.debug(s"Compiling AggregateHelper: $genAggregations.name \n\n " +
                s"Code:\n$genAggregations.code")
    val clazz = compile(
      getRuntimeContext.getUserCodeClassLoader,
      genAggregations.name,
      genAggregations.code)
    LOG.debug("Instantiating AggregateHelper.")
    function = clazz.newInstance()

    output = function.createOutputRow()
    accumulators = function.createAccumulators()

    if (isTableAggregate) {
      concatCollector = new ConcatKeyAndAggResultCollector(numGroupingKey)
      concatCollector.setResultRow(output)
    }
  }

  /**
    * Computes a non-pre-aggregated aggregation.
    *
    * @param records An iterator over all records of the group.
    * @param out     The collector to hand results to.
    */
  override def reduce(records: Iterable[Row], out: Collector[Row]): Unit = {

    // reset accumulators
    function.resetAccumulator(accumulators)

    val iterator = records.iterator()

    var record: Row = null
    while (iterator.hasNext) {
      record = iterator.next()

      // accumulate
      function.accumulate(accumulators, record)
    }

    // set group keys value to final output
    function.setForwardedFields(record, output)

    if (isTableAggregate) {
      concatCollector.out = out
      function.asInstanceOf[GeneratedTableAggregations].emit(accumulators, concatCollector)
    } else {
      // set agg results to output
      function.setAggregationResults(accumulators, output)
      out.collect(output)
    }
  }

  /**
    * Computes a non-pre-aggregated aggregation and returns a row even if the input is empty.
    *
    * @param records An iterator over all records of the partition.
    * @param out     The collector to hand results to.
    */
  override def mapPartition(records: Iterable[Row], out: Collector[Row]): Unit = {
    reduce(records, out)
  }

}
