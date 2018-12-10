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

package org.apache.flink.table.api.batch.table.validation

import org.apache.flink.api.scala._
import org.apache.flink.table.api.{TableException, ValidationException}
import org.apache.flink.table.api.scala._
import org.apache.flink.table.utils.{EmptyTableAggFunc, TableTestBase}
import org.junit._

class TableAggregateValidationTest extends TableTestBase {

  @Test(expected = classOf[ValidationException])
  def testInvalidParameterNumber(): Unit = {
    val util = batchTestUtil()
    val table = util.addTable[(Long, Int, String)]('a, 'b, 'c)

    val func = new EmptyTableAggFunc
    table
      .groupBy('c)
      // must fail. func take 2 parameters
      .flatAggregate(func('a))
      .select('_1, '_2, '_3)
  }

  @Test(expected = classOf[ValidationException])
  def testInvalidParameterType(): Unit = {
    val util = batchTestUtil()
    val table = util.addTable[(Long, Int, String)]('a, 'b, 'c)

    val func = new EmptyTableAggFunc
    table
      .groupBy('c)
      // must fail. func take 2 parameters of type Long and Timestamp
      .flatAggregate(func('a, 'b))
      .select('_1, '_2, '_3)
  }

  @Test(expected = classOf[ValidationException])
  def testInvalidParameterWithAgg(): Unit = {
    val util = batchTestUtil()
    val table = util.addTable[(Long, Int, String)]('a, 'b, 'c)

    val func = new EmptyTableAggFunc
    table
      .groupBy('b)
      // must fail. func take agg function as input
      .flatAggregate(func('a.sum, 'c))
      .select('_1, '_2, '_3)
  }

  @Test(expected = classOf[TableException])
  def testInvalidSelectStar(): Unit = {
    val util = batchTestUtil()
    val table = util.addTable[(Long, Int, String)]('a, 'b, 'c)

    val func = new EmptyTableAggFunc
    table
      .groupBy('b)
      .flatAggregate(func('a, 'c))
      // must fail. * is not supported in the select of flatAggregate.
      .select('*)
  }

  @Test(expected = classOf[ValidationException])
  def testInvalidSelectAgg(): Unit = {
    val util = batchTestUtil()
    val table = util.addTable[(Long, Int, String)]('a, 'b, 'c)

    val func = new EmptyTableAggFunc
    table
      .groupBy('b)
      .flatAggregate(func('a, 'c))
      // must fail. agg function is not supported in the select of flatAggregate.
      .select('_1.sum, '_2.count)
  }
}
