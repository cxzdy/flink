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
package org.apache.flink.table.functions

import java.util

/**
  * Base class for User-Defined Table Aggregates.
  *
  * The behavior of an [[TableAggregateFunction]] can be defined by implementing a series of custom
  * methods. An [[TableAggregateFunction]] needs at least three methods:
  *  - createAccumulator,
  *  - accumulate, and
  *  - emitValue or emitValueWithRetract
  *
  *  There are a few other methods that can be optional to have:
  *  - retract,
  *  - merge, and
  *  - resetAccumulator
  *
  * All these methods must be declared publicly, not static and named exactly as the names
  * mentioned above. The methods createAccumulator and emitValue(or emitValueWithRetract) are
  * defined in the [[TableAggregateFunction]] functions, while other methods are explained below.
  *
  *
  * {{{
  * Processes the input values and update the provided accumulator instance. The method
  * accumulate can be overloaded with different custom types and arguments. A TableAggregateFunction
  * requires at least one accumulate() method.
  *
  * @param accumulator           the accumulator which contains the current aggregated results
  * @param [user defined inputs] the input value (usually obtained from a new arrived data).
  *
  * def accumulate(accumulator: ACC, [user defined inputs]): Unit
  * }}}
  *
  *
  * {{{
  * Retracts the input values from the accumulator instance. The current design assumes the
  * inputs are the values that have been previously accumulated. The method retract can be
  * overloaded with different custom types and arguments. This function must be implemented for
  * datastream bounded over aggregate.
  *
  * @param accumulator           the accumulator which contains the current aggregated results
  * @param [user defined inputs] the input value (usually obtained from a new arrived data).
  *
  * def retract(accumulator: ACC, [user defined inputs]): Unit
  * }}}
  *
  *
  * {{{
  * Merges a group of accumulator instances into one accumulator instance. This function must be
  * implemented for datastream session window grouping aggregate and dataset grouping aggregate.
  *
  * @param accumulator  the accumulator which will keep the merged aggregate results. It should
  *                     be noted that the accumulator may contain the previous aggregated
  *                     results. Therefore user should not replace or clean this instance in the
  *                     custom merge method.
  * @param its          an [[java.lang.Iterable]] pointed to a group of accumulators that will be
  *                     merged.
  *
  * def merge(accumulator: ACC, its: java.lang.Iterable[ACC]): Unit
  * }}}
  *
  *
  * {{{
  * Resets the accumulator for this [[TableAggregateFunction]]. This function must be implemented
  * for dataset grouping aggregate.
  *
  * @param accumulator  the accumulator which needs to be reset
  *
  * def resetAccumulator(accumulator: ACC): Unit
  * }}}
  *
  * @tparam T   the type of the aggregation result
  * @tparam ACC the type of the aggregation accumulator. The accumulator is used to keep the
  *             aggregated values which are needed to compute an aggregation result.
  *             AggregateFunction represents its state using accumulator, thereby the state of the
  *             AggregateFunction must be put into the accumulator.
  */
abstract class TableAggregateFunction[T, ACC] extends UserDefinedAggregateFunction[T, ACC] {

  def getKeys: util.ArrayList[Integer] = new util.ArrayList[Integer]()
}
