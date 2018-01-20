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
package org.apache.flink.table.runtime.join

import org.apache.flink.api.common.state._
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.tuple.{Tuple2 => JTuple2}
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.table.api.{StreamQueryConfig, Types}
import org.apache.flink.table.runtime.types.CRow
import org.apache.flink.types.Row
import org.apache.flink.util.Collector

/**
  * Connect data for left stream and right stream. Only use for LeftJoin with NonEquiPredicates.
  * An MapState of type [Row, Long] is added to record how many rows from the right table can be
  * matched for each left row. Left join without NonEquiPredicates doesn't need it because
  * left rows can always join right rows as long as join key is same.
  *
  * @param leftType          the input type of left stream
  * @param rightType         the input type of right stream
  * @param resultType        the output type of join
  * @param genJoinFuncName   the function code of other non-equi condition
  * @param genJoinFuncCode   the function name of other non-equi condition
  * @param queryConfig       the configuration for the query to generate
  */
class NonWindowLeftJoinWithNonEquiPredicates(
    leftType: TypeInformation[Row],
    rightType: TypeInformation[Row],
    resultType: TypeInformation[CRow],
    genJoinFuncName: String,
    genJoinFuncCode: String,
    queryConfig: StreamQueryConfig)
  extends NonWindowJoin(
    leftType,
    rightType,
    resultType,
    genJoinFuncName,
    genJoinFuncCode,
    queryConfig) {

  // result row, all field from right will be null
  private var resultRow: Row = _
  // how many matched rows from the right table for each left row
  protected var leftJoinCnt: MapState[Row, Long] = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)

    val leftJoinCntDescriptor = new MapStateDescriptor[Row, Long](
      "leftJoinCnt", leftType, Types.LONG.asInstanceOf[TypeInformation[Long]])
    leftJoinCnt = getRuntimeContext.getMapState(leftJoinCntDescriptor)
    resultRow = new Row(resultType.getArity)

    LOG.debug("Instantiating NonWindowLeftJoin.")
  }

  /**
    * Puts or Retract an element from the input stream into state and search the other state to
    * output records meet the condition. Records will be expired in state if state retention time
    * has been specified.
    */
  override def processElement(
      value: CRow,
      ctx: CoProcessFunction[CRow, CRow, CRow]#Context,
      out: Collector[CRow],
      timerState: ValueState[Long],
      currentSideState: MapState[Row, JTuple2[Int, Long]],
      otherSideState: MapState[Row, JTuple2[Int, Long]],
      isLeft: Boolean): Unit = {

    val inputRow = value.row
    cRowWrapper.reset()
    cRowWrapper.setCollector(out)
    cRowWrapper.setChange(value.change)

    val curProcessTime = ctx.timerService.currentProcessingTime
    val oldCntAndExpiredTime = currentSideState.get(inputRow)
    val cntAndExpiredTime = if (null == oldCntAndExpiredTime) {
      JTuple2.of(0, -1L)
    } else {
      oldCntAndExpiredTime
    }

    cntAndExpiredTime.f1 = getNewExpiredTime(curProcessTime, cntAndExpiredTime.f1)
    if (stateCleaningEnabled && timerState.value() == 0) {
      timerState.update(cntAndExpiredTime.f1)
      ctx.timerService().registerProcessingTimeTimer(cntAndExpiredTime.f1)
    }

    // update current side stream state
    if (!value.change) {
      cntAndExpiredTime.f0 = cntAndExpiredTime.f0 - 1
      if (cntAndExpiredTime.f0 <= 0) {
        currentSideState.remove(inputRow)
        if (isLeft) {
          leftJoinCnt.remove(inputRow)
        }
      } else {
        currentSideState.put(inputRow, cntAndExpiredTime)
      }
    } else {
      cntAndExpiredTime.f0 = cntAndExpiredTime.f0 + 1
      currentSideState.put(inputRow, cntAndExpiredTime)
    }
    val otherSideIterator = otherSideState.iterator()
    cRowWrapper.setEmitCnt(0)
    // join other side data
    if (isLeft) {
      while (otherSideIterator.hasNext) {
        val otherSideEntry = otherSideIterator.next()
        val otherSideRow = otherSideEntry.getKey
        val cntAndExpiredTimeOfOtherSide = otherSideEntry.getValue
        // join
        cRowWrapper.setTimes(cntAndExpiredTimeOfOtherSide.f0)
        joinFunction.join(inputRow, otherSideRow, cRowWrapper)
        // clear expired data. Note: clear after join to keep closer to the original semantics
        if (stateCleaningEnabled && curProcessTime >= cntAndExpiredTimeOfOtherSide.f1) {
          otherSideIterator.remove()
        }
      }
      // update matched cnt

      if (cntAndExpiredTime.f0 > 0) {
        leftJoinCnt.put(inputRow, cRowWrapper.getEmitCnt)
      }
      // The result is NULL from the right side, if there is no match.
      if (cRowWrapper.getEmitCnt == 0) {
        cRowWrapper.setTimes(1)
        collectWithNullRight(inputRow, resultRow, cRowWrapper)
      }
    } else {

      while (otherSideIterator.hasNext) {
        val otherSideEntry = otherSideIterator.next()
        val otherSideRow = otherSideEntry.getKey
        val cntAndExpiredTimeOfOtherSide = otherSideEntry.getValue

        cRowWrapper.setLazyOutput(true)
        cRowWrapper.setRow(null)
        joinFunction.join(otherSideRow, inputRow, cRowWrapper)
        val outputRow = cRowWrapper.getRow()

        if (outputRow != null) {
          cRowWrapper.setLazyOutput(false)
          cRowWrapper.setTimes(cntAndExpiredTimeOfOtherSide.f0)
          val joinCnt = leftJoinCnt.get(otherSideRow)
          if (value.change) {
            leftJoinCnt.put(otherSideRow, joinCnt + 1L)
            if (joinCnt == 0) {
              // retract previous non matched result row
              cRowWrapper.setChange(false)
              collectWithNullRight(otherSideRow, resultRow, cRowWrapper)
              cRowWrapper.setChange(true)
            }
            // do normal join
            joinFunction.join(otherSideRow, inputRow, cRowWrapper)
          } else {
            leftJoinCnt.put(otherSideRow, joinCnt - 1L)
            // do normal join
            joinFunction.join(otherSideRow, inputRow, cRowWrapper)
            if (joinCnt == 1) {
              // output non matched result row
              cRowWrapper.setChange(true)
              collectWithNullRight(otherSideRow, resultRow, cRowWrapper)
              cRowWrapper.setChange(false)
            }
          }
        }
      }
    }
  }

  /**
    * Removes records which are expired from left state. Registers a new timer if the state still
    * holds records after the clean-up. Also, clear leftJoinCnt map state when clear left
    * rowMapState.
    */
  override def expireOutTimeRowForLeft(
    curTime: Long,
    rowMapState: MapState[Row, JTuple2[Int, Long]],
    timerState: ValueState[Long],
    ctx: CoProcessFunction[CRow, CRow, CRow]#OnTimerContext): Unit = {

    val rowMapIter = rowMapState.iterator()
    var validTimestamp: Boolean = false

    while (rowMapIter.hasNext) {
      val mapEntry = rowMapIter.next()
      val recordExpiredTime = mapEntry.getValue.f1
      if (recordExpiredTime <= curTime) {
        rowMapIter.remove()
        leftJoinCnt.remove(mapEntry.getKey)
      } else {
        // we found a timestamp that is still valid
        validTimestamp = true
      }
    }
    // If the state has non-expired timestamps, register a new timer.
    // Otherwise clean the complete state for this input.
    if (validTimestamp) {
      val cleanupTime = curTime + maxRetentionTime
      ctx.timerService.registerProcessingTimeTimer(cleanupTime)
      timerState.update(cleanupTime)
    } else {
      timerState.clear()
      rowMapState.clear()
      leftJoinCnt.clear()
    }
  }
}

