/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can.server

import cc.spray.can.parsing._
import cc.spray.can.rendering.HttpResponsePartRenderingContext
import cc.spray.io._
import akka.event.LoggingAdapter
import java.nio.ByteBuffer
import annotation.tailrec
import cc.spray.can.model.{HttpMessageEndPart, HttpHeader, HttpResponse}

object RequestParsing {

  lazy val continue = "HTTP/1.1 100 Continue\r\n\r\n".getBytes("ASCII")

  def apply(settings: ParserSettings, log: LoggingAdapter): EventPipelineStage = new EventPipelineStage {
    val startParser = new EmptyRequestParser(settings)

    def build(context: PipelineContext, commandPL: Pipeline[Command], eventPL: Pipeline[Event]): EPL = {

      new EPL {
        var currentParsingState: ParsingState = startParser

        @tailrec
        final def parse(buffer: ByteBuffer) {
          currentParsingState match {
            case x: IntermediateState =>
              if (buffer.remaining > 0) {
                currentParsingState = x.read(buffer)
                parse(buffer)
              } // else wait for more input

            case x: HttpMessagePartCompletedState =>
              val messagePart = x.toHttpMessagePart
              eventPL(messagePart)
              currentParsingState =
                if (messagePart.isInstanceOf[HttpMessageEndPart]) startParser
                else new ChunkParser(settings)
              parse(buffer)

            case Expect100ContinueState(nextState) =>
              commandPL(IoPeer.Send(ByteBuffer.wrap(continue), ack = false))
              currentParsingState = nextState
              parse(buffer)

            case ErrorState(_, -1) => // if we already handled the error state we ignore all further input

            case x: ErrorState =>
              handleParseError(x)
              currentParsingState = ErrorState("", -1) // set to "special" ErrorState that ignores all further input
          }
        }

        def handleParseError(state: ErrorState) {
          log.warning("Illegal request, responding with status {} and '{}'", state.status, state.message)
          val response = HttpResponse(
            status = state.status,
            headers = List(HttpHeader("Content-Type", "text/plain"))
          ).withBody(state.message)

          // In case of a request parsing error we probably stopped reading the request somewhere in between,
          // where we cannot simply resume. Resetting to a known state is not easy either,
          // so we need to close the connection to do so.
          commandPL(HttpResponsePartRenderingContext(response))
          commandPL(HttpServer.Close(ProtocolError(state.message)))
        }

        def apply(event: Event) {
          event match {
            case x: IoPeer.Received => parse(x.buffer)
            case ev => eventPL(ev)
          }
        }
      }
    }
  }

}