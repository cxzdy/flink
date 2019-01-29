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

package org.apache.flink.table.factories.utils

import java.util

import org.apache.flink.table.api.{TableConfig, TablePlanner, TablePlannerFactory}
import org.apache.flink.api.scala.{ExecutionEnvironment => ScalaBatchExecEnv}
import org.apache.flink.table.api.scala.{BatchTableEnvironment => ScalaBatchTablePlanner}

/**
  * Table source factory for testing.
  */
class ScalaBatchTablePlannerFactory extends TablePlannerFactory {

  override def requiredContext(): util.Map[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put("mode", "batch")
    context
  }

  override def supportedProperties(): util.List[String] = {
    val properties = new util.ArrayList[String]()
    properties
  }

  override def createTablePlanner(
      tableConfig: TableConfig)
    : TablePlanner = {

    new TablePlanner(tableConfig)
  }
}


