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

package org.apache.flink.table.calcite

import java.lang.Iterable
import java.util.{Collections, List => JList}

import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.plan._
import org.apache.calcite.plan.volcano.VolcanoPlanner
import org.apache.calcite.prepare.CalciteCatalogReader
import org.apache.calcite.rel.logical.LogicalAggregate
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.tools.RelBuilder.{AggCall, GroupKey}
import org.apache.calcite.tools.{FrameworkConfig, RelBuilder}
import org.apache.flink.table.api.TableException
import org.apache.flink.table.expressions.{Alias, ExpressionBridge, PlannerExpression, WindowProperty}
import org.apache.flink.table.operations.TableOperation
import org.apache.flink.table.plan.TableOperationConverter
import org.apache.flink.table.plan.logical.LogicalWindow
import org.apache.flink.table.plan.logical.rel.{LogicalTableAggregate, LogicalWindowAggregate, LogicalWindowTableAggregate}

import scala.collection.JavaConverters._

/**
  * Flink specific [[RelBuilder]] that changes the default type factory to a [[FlinkTypeFactory]].
  */
class FlinkRelBuilder(
    context: Context,
    relOptCluster: RelOptCluster,
    relOptSchema: RelOptSchema,
    expressionBridge: ExpressionBridge[PlannerExpression])
  extends RelBuilder(
    context,
    relOptCluster,
    relOptSchema) {

  private val toRelNodeConverter = new TableOperationConverter(this, expressionBridge)

  def getRelOptSchema: RelOptSchema = relOptSchema

  def getPlanner: RelOptPlanner = cluster.getPlanner

  def getCluster: RelOptCluster = relOptCluster

  override def shouldMergeProject(): Boolean = false

  override def getTypeFactory: FlinkTypeFactory =
    super.getTypeFactory.asInstanceOf[FlinkTypeFactory]

  /**
    * Build window aggregate for both aggregate or table aggregate.
    */
  def aggregate(
      window: LogicalWindow,
      groupKey: GroupKey,
      windowProperties: JList[PlannerExpression],
      aggCalls: Iterable[AggCall],
      isTableAggregate: Boolean)
    : RelBuilder = {
    // build logical aggregate
    val aggregate = super.aggregate(groupKey, aggCalls).build().asInstanceOf[LogicalAggregate]

    val namedProperties = windowProperties.asScala.map {
      case Alias(p: WindowProperty, name, _) =>
        p.toNamedWindowProperty(name)
      case _ => throw new TableException("This should never happen.")
    }

    // build logical window aggregate from it
    if (isTableAggregate) {
      push(LogicalWindowTableAggregate.create(window, namedProperties, aggregate))
    } else {
      push(LogicalWindowAggregate.create(window, namedProperties, aggregate))
    }
  }

  /**
    * Build non-window aggregate for both aggregate or table aggregate.
    */
  def aggregate(
    groupKey: GroupKey,
    aggCalls: Iterable[AggCall],
    isTableAggregate: Boolean): RelBuilder = {

    if (isTableAggregate) {
      // build logical aggregate
      val aggregate = super.aggregate(groupKey, aggCalls).build().asInstanceOf[LogicalAggregate]
      // build logical table aggregate from it
      push(LogicalTableAggregate.create(aggregate))
    } else {
      super.aggregate(groupKey, aggCalls)
    }
  }

  def tableOperation(tableOperation: TableOperation): RelBuilder= {
    val relNode = tableOperation.accept(toRelNodeConverter)

    push(relNode)
    this
  }

}

object FlinkRelBuilder {

  def create(
      config: FrameworkConfig,
      expressionBridge: ExpressionBridge[PlannerExpression])
    : FlinkRelBuilder = {

    // create Flink type factory
    val typeSystem = config.getTypeSystem
    val typeFactory = new FlinkTypeFactory(typeSystem)

    // create context instances with Flink type factory
    val planner = new VolcanoPlanner(config.getCostFactory, Contexts.empty())
    planner.setExecutor(config.getExecutor)
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE)
    val cluster = FlinkRelOptClusterFactory.create(planner, new RexBuilder(typeFactory))
    val calciteSchema = CalciteSchema.from(config.getDefaultSchema)
    val relOptSchema = new CalciteCatalogReader(
      calciteSchema,
      Collections.emptyList(),
      typeFactory,
      CalciteConfig.connectionConfig(config.getParserConfig))

    new FlinkRelBuilder(config.getContext, cluster, relOptSchema, expressionBridge)
  }

  /**
    * Information necessary to create a window aggregate.
    *
    * Similar to [[RelBuilder.AggCall]] or [[RelBuilder.GroupKey]].
    */
  case class NamedWindowProperty(name: String, property: WindowProperty)

}
