/*
 * Copyright 2014-2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.eval.stream

import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.util.ByteString
import com.netflix.atlas.eval.model.AggrDatapoint
import com.netflix.atlas.eval.model.LwcDataExpr
import com.netflix.atlas.eval.model.LwcDatapoint
import com.netflix.atlas.eval.model.LwcDiagnosticMessage
import com.netflix.atlas.eval.model.LwcHeartbeat
import com.netflix.atlas.eval.model.LwcSubscription
import com.netflix.atlas.json.Json
import com.typesafe.scalalogging.Logger

/**
  * Process the SSE output from an LWC service and convert it into a stream of
  * [[AggrDatapoint]]s that can be used for evaluation.
  */
private[stream] class LwcToAggrDatapoint(context: StreamContext)
    extends GraphStage[FlowShape[ByteString, AggrDatapoint]] {

  private val logger = Logger(getClass)

  private val badMessages = context.registry.counter("atlas.eval.badMessages")

  private val in = Inlet[ByteString]("LwcToAggrDatapoint.in")
  private val out = Outlet[AggrDatapoint]("LwcToAggrDatapoint.out")

  override val shape: FlowShape[ByteString, AggrDatapoint] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) with InHandler with OutHandler {
      import com.netflix.atlas.eval.model.LwcMessages._

      // Default to a decent size so it is unlikely there'll be a need to allocate
      // a larger array
      private[this] var buffer = new Array[Byte](16384)

      private[this] var state: Map[String, LwcDataExpr] = Map.empty

      // HACK: needed until we can plumb the actual source through the system
      private var nextSource: Int = 0

      override def onPush(): Unit = {
        val message = grab(in)
        try {
          message match {
            case msg if msg.startsWith(subscribePrefix)  => updateState(msg)
            case msg if msg.startsWith(metricDataPrefix) => pushDatapoint(msg)
            case msg if msg.startsWith(diagnosticPrefix) => pushDiagnosticMessage(msg)
            case msg if msg.startsWith(heartbeatPrefix)  => pushHeartbeat(msg)
            case msg                                     => ignoreMessage(msg)
          }
        } catch {
          case e: Exception =>
            val messageString = toString(message)
            logger.warn(s"failed to process message [$messageString]", e)
            badMessages.increment()
        }
      }

      private def toString(bytes: ByteString): String = {
        val builder = new StringBuilder()
        bytes.foreach { b =>
          val c = b & 0xFF
          if (isPrintable(c))
            builder.append(c.asInstanceOf[Char])
          else if (c <= 0xF)
            builder.append("\\x0").append(Integer.toHexString(c))
          else
            builder.append("\\x").append(Integer.toHexString(c))
        }
        builder.toString()
      }

      private def isPrintable(c: Int): Boolean = {
        c >= 32 && c < 127
      }

      private def copy(msg: ByteString, length: Int): Int = {
        if (length > buffer.length) {
          buffer = new Array[Byte](length)
        }
        msg.copyToArray(buffer, 0, length)
        length
      }

      private def updateState(msg: ByteString): Unit = {
        val json = msg.drop(subscribePrefix.length)
        val length = copy(json, json.length)
        val sub = Json.decode[LwcSubscription](buffer, 0, length)
        state ++= sub.metrics.map(m => m.id -> m).toMap
        pull(in)
      }

      private def pushDatapoint(msg: ByteString): Unit = {
        val json = msg.drop(metricDataPrefix.length)
        val length = copy(json, json.length)
        val d = Json.decode[LwcDatapoint](buffer, 0, length)
        state.get(d.id) match {
          case Some(sub) =>
            // TODO, put in source, for now make it random to avoid dedup
            nextSource += 1
            val expr = sub.expr
            val step = sub.step
            push(out, AggrDatapoint(d.timestamp, step, expr, nextSource.toString, d.tags, d.value))
          case None =>
            pull(in)
        }
      }

      private def pushDiagnosticMessage(msg: ByteString): Unit = {
        val json = msg.drop(diagnosticPrefix.length)
        val length = copy(json, json.length)
        val d = Json.decode[LwcDiagnosticMessage](buffer, 0, length)
        state.get(d.id).foreach { sub =>
          context.log(sub.expr, d.message)
        }
        pull(in)
      }

      private def pushHeartbeat(msg: ByteString): Unit = {
        val json = msg.drop(heartbeatPrefix.length)
        val length = copy(json, json.length)
        val d = Json.decode[LwcHeartbeat](buffer, 0, length)
        push(out, AggrDatapoint.heartbeat(d.timestamp, d.step))
      }

      private def ignoreMessage(msg: ByteString): Unit = {
        pull(in)
      }

      override def onPull(): Unit = {
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        completeStage()
      }

      setHandlers(in, out, this)
    }
  }
}
