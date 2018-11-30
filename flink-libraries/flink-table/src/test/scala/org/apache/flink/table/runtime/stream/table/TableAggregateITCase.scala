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

package org.apache.flink.table.runtime.stream.table

import org.apache.flink.api.common.time.Time
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.scala._
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.{StreamQueryConfig, TableEnvironment, Types}
import org.apache.flink.table.runtime.utils._
import org.apache.flink.table.utils.{Top3WithRetractInput, TopN}
import org.apache.flink.types.Row
import org.junit.Assert.assertEquals
import org.junit.Test

import scala.collection.mutable

/**
  * Tests of groupby (without window) aggregations
  */
class TableAggregateITCase extends StreamingWithStateTestBase {
  private val queryConfig = new StreamQueryConfig()
  queryConfig.withIdleStateRetentionTime(Time.hours(1), Time.hours(2))

  @Test
  def testTop3InAccModeWithoutGroupBy(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStateBackend(getStateBackend)
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    env.setParallelism(1)
    tEnv.registerTableSink(
      "upsertSink",
      new TestUpsertSink(Array("category", "rank"), false).configure(
        Array[String]("groupKey", "v", "rank"),
        Array[TypeInformation[_]](Types.INT, Types.LONG, Types.INT)))

    val top3 = new TopN(3)
    tEnv.registerFunction("top3", top3)
    val source = StreamTestData.get3TupleDataStream(env).toTable(tEnv, 'a, 'b, 'c)
    source
      .flatAggregate(top3("1".cast(Types.INT), 'a.cast(Types.LONG)))
      .select('_1, '_2, '_3)
      .as('category, 'v, 'rank)
      .insertInto("upsertSink")
    env.execute()
    val results = RowCollector.getAndClearValues

    val retracted = RowCollector.upsertResults(results, Array(0, 2)).sorted
    val expected = List(
      "1,21,0",
      "1,20,1",
      "1,19,2").sorted
    assertEquals(expected, retracted)
  }

  @Test
  def testTop3InAccModeWithGroupBy(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStateBackend(getStateBackend)
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    tEnv.registerTableSink(
      "upsertSink",
      new TestUpsertSink(Array("category", "rank"), false).configure(
        Array[String]("groupKey", "v", "rank"),
        Array[TypeInformation[_]](Types.INT, Types.LONG, Types.INT)))

    val top3 = new TopN(3)
    tEnv.registerFunction("top3", top3)
    val source = StreamTestData.get3TupleDataStream(env).toTable(tEnv, 'a, 'b, 'c)
    source.groupBy('b)
      .flatAggregate(top3('b.cast(Types.INT), 'a.cast(Types.LONG)))
      .select('_1, '_2, '_3)
      .as('category, 'v, 'rank)
      .insertInto("upsertSink")

    env.execute()
    val results = RowCollector.getAndClearValues

    val retracted = RowCollector.upsertResults(results, Array(0, 2)).sorted
    val expected = List(
      "1,1,0",
      "2,3,0",
      "2,2,1",
      "3,6,0",
      "3,5,1",
      "3,4,2",
      "4,10,0",
      "4,9,1",
      "4,8,2",
      "5,15,0",
      "5,14,1",
      "5,13,2",
      "6,21,0",
      "6,20,1",
      "6,19,2"
    ).sorted
    assertEquals(expected, retracted)
  }

  @Test
  def testTop3InAccRetractModeWithoutGroupBy(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStateBackend(getStateBackend)
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val top3 = new TopN(3)
    tEnv.registerFunction("top3", top3)
    val source = StreamTestData.get3TupleDataStream(env).toTable(tEnv, 'a, 'b, 'c)
    val t = source
      .flatAggregate(top3("1".cast(Types.INT), 'a.cast(Types.LONG)))
      .select('_1, '_2, '_3)
      .as('category, 'v, 'rank)

    val results = t.toRetractStream[Row](queryConfig)
    results.addSink(new StreamITCase.RetractingSink).setParallelism(1)
    env.execute()

    val expected = mutable.MutableList(
      "1,21,0",
      "1,20,1",
      "1,19,2")
    assertEquals(expected.sorted, StreamITCase.retractedResults.sorted)
  }

  @Test
  def testTop3InAccRetractModeWithGroupBy(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStateBackend(getStateBackend)
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val top3 = new TopN(3)
    tEnv.registerFunction("top3", top3)
    val source = StreamTestData.get3TupleDataStream(env).toTable(tEnv, 'a, 'b, 'c)
    val t = source.groupBy('b)
      .flatAggregate(top3('b.cast(Types.INT), 'a.cast(Types.LONG)))
      .select('_1, '_2, '_3)

    val results = t.toRetractStream[Row](queryConfig)
    results.addSink(new StreamITCase.RetractingSink)
    env.execute()

    val expected = mutable.MutableList(
      "1,1,0",
      "2,3,0",
      "2,2,1",
      "3,6,0",
      "3,5,1",
      "3,4,2",
      "4,10,0",
      "4,9,1",
      "4,8,2",
      "5,15,0",
      "5,14,1",
      "5,13,2",
      "6,21,0",
      "6,20,1",
      "6,19,2")
    assertEquals(expected.sorted, StreamITCase.retractedResults.sorted)
  }

  // test with retraction input
  @Test
  def testTop3InAccRetractModeWithRetractInput(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStateBackend(getStateBackend)
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val top3 = new Top3WithRetractInput
    tEnv.registerFunction("top3", top3)
    val source = StreamTestData.get3TupleDataStream(env).toTable(tEnv, 'a, 'b, 'c)
    val t = source
      .groupBy('b)
      .select("1" as 'a, 'b, 'a.count as 'count)
      .groupBy('a)
      .flatAggregate(top3('b.cast(Types.INT), 'count))
      .select('_1, '_2, '_3)

    val results = t.toRetractStream[Row](queryConfig)
    results.addSink(new StreamITCase.RetractingSink)
    env.execute()

    val expected = mutable.MutableList(
      "1,6,0",
      "1,5,1",
      "1,4,2")
    assertEquals(expected.sorted, StreamITCase.retractedResults.sorted)
  }

}
