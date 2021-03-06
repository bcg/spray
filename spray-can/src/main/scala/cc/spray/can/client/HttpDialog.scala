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

package cc.spray.can.client

import cc.spray.util.Reply
import cc.spray.io.IoClient.IoClientException
import cc.spray.io.{ProtocolError, CleanClose}
import cc.spray.can.model.{HttpResponsePart, HttpRequest, HttpResponse}
import akka.dispatch.{Promise, Future}
import akka.actor._
import akka.util.Duration
import collection.mutable.ListBuffer

/**
 * An `HttpDialog` encapsulates an exchange of HTTP messages over the course of one connection.
 * It provides a fluent API for constructing a "chain" of scheduled tasks that define what to do over the course of
 * the dialog.
 */
object HttpDialog {
  private sealed abstract class Action
  private case class ConnectAction(host: String, port: Int) extends Action
  private case class SendAction(request: HttpRequest) extends Action
  private case class WaitIdleAction(duration: Duration) extends Action
  private case class ReplyAction(f: HttpResponse => HttpRequest) extends Action
  private case object AwaitResponseAction extends Action

  private class DialogActor(result: Promise[AnyRef], client: ActorRef, multiResponse: Boolean) extends Actor {
    val responses = ListBuffer.empty[HttpResponse]
    var connection: Option[ActorRef] = None
    var responsesPending = 0
    var onResponse: Option[() => Unit] = None

    def complete(value: Either[Throwable, AnyRef]) {
      result.complete(value)
      connection.foreach(_ ! HttpClient.Close(CleanClose))
      context.stop(self)
    }

    def receive = {
      case ConnectAction(host, port) :: remainingActions =>
        client.tell(HttpClient.Connect(host, port), Reply.withContext(remainingActions))

      case SendAction(request) :: remainingActions =>
        connection.foreach(_ ! request)
        responsesPending += 1
        self ! remainingActions

      case WaitIdleAction(duration) :: remainingActions =>
        context.system.scheduler.scheduleOnce(duration, self, remainingActions)

      case ReplyAction(f) :: remainingActions =>
        onResponse = Some { () =>
          val request = f(responses.remove(0))
          self.tell(SendAction(request) :: remainingActions)
        }

      case AwaitResponseAction :: remainingActions =>
        onResponse = Some(() => self ! remainingActions)

      case x: HttpResponse =>
        responses += x
        responsesPending -= 1
        onResponse match {
          case Some(task) =>
            onResponse = None
            task()
          case None => if (responsesPending == 0) {
            if (multiResponse) complete(Right(responses.toList))
            else complete(Right(responses.head))
          }
        }

      case _: HttpResponsePart =>
        val msg = "The HttpDialog doesn't support chunked responses"
        sender ! HttpClient.Close(ProtocolError(msg))
        complete(Left(IoClientException(msg)))

      case Reply(HttpClient.Connected(handle), actions) =>
        connection = Some(handle.handler)
        self ! actions

      case Reply(msg, _) => self ! msg // unpack all other with-context replies

      case HttpClient.Closed(_, reason) =>
        complete(Left(IoClientException("Connection closed prematurely, reason: " + reason)))

      case _: HttpClient.AckSend => // drop potential write confirmations

      case Status.Failure(cause) => complete(Left(cause))
    }
  }

  private class Context(refFactory: ActorRefFactory, client: ActorRef, connect: ConnectAction) {
    private var actions = ListBuffer[Action](connect)
    def appendAction(action: Action): this.type = {
      actions += action
      this
    }
    def runActions(multiResponse: Boolean): Future[AnyRef] = {
      val result = Promise[AnyRef]() {
        refFactory match {
          case x: ActorContext => x.dispatcher
          case x: ActorSystem => x.dispatcher
          case x => throw new IllegalArgumentException("Unsupported ActorRefFactory '" + x + "'")
        }
      }
      refFactory.actorOf(Props(new DialogActor(result, client, multiResponse))) ! actions.toList
      result
    }
  }

  sealed abstract class EndSingleResponse(private[HttpDialog] val context: Context) {
    /**
     * Triggers the execution of the scheduled HttpDialog actions and produces a future for the result.
     */
    def end: Future[HttpResponse] =
      context.runActions(multiResponse = false).mapTo[HttpResponse]
  }

  sealed abstract class EndMultiResponse(private[HttpDialog] val context: Context) {
    /**
     * Triggers the execution of the scheduled HttpDialog actions and produces a future for the result.
     */
    def end: Future[List[HttpResponse]] =
      context.runActions(multiResponse = true).mapTo[List[HttpResponse]]
  }

  sealed abstract class SendFirst(private[HttpDialog] val context: Context) {
    /**
     * Chains the sending of the given [[cc.spray.can.model.HttpRequest]] into the dialog.
     * The request will be sent as soon as the connection has been established and any `awaitResponse` and
     * `waitIdle` tasks potentially chained in before this `send` have been completed.
     * Several `send` tasks not separated by `awaitResponse`/`waitIdle` will cause the corresponding requests
     * to be send in a pipelined fashion, one right after the other.
     */
    def send(request: HttpRequest) =
      new EndSingleResponse(context.appendAction(SendAction(request)))
        with SendSubsequent
        with SendMany
        with WaitIdle
        with AwaitResponse
        with Reply
  }

  sealed trait SendSubsequent {
    private[HttpDialog] def context: Context

    /**
     * Chains the sending of the given [[cc.spray.can.model.HttpRequest]] into the dialog.
     * The request will be sent as soon as the connection has been established and any `awaitResponse` and
     * `waitIdle` tasks potentially chained in before this `send` have been completed.
     * Several `send` tasks not separated by `awaitResponse`/`waitIdle` will cause the corresponding requests
     * to be send in a pipelined fashion, one right after the other.
     */
    def send(request: HttpRequest) =
      new EndMultiResponse(context.appendAction(SendAction(request)))
        with SendSubsequent
        with SendMany
        with WaitIdle
        with AwaitResponse
  }

  sealed trait SendMany {
    private[HttpDialog] def context: Context

    /**
     * Chains the sending of the given [[cc.spray.can.HttpRequest]] instances into the dialog.
     * The requests will be sent as soon as the connection has been established and any `awaitResponse` and
     * `waitIdle` tasks potentially chained in before this `send` have been completed.
     * All of the given HttpRequests are send in a pipelined fashion, one right after the other.
     */
    def send(requests: Seq[HttpRequest]) = {
      requests.foreach(req => context.appendAction(SendAction(req)))
      new EndMultiResponse(context)
        with SendSubsequent
        with SendMany
        with WaitIdle
    }
  }

  sealed trait WaitIdle {
    private[HttpDialog] def context: Context

    /**
     * Delays all subsequent tasks by the given time duration.
     */
    def waitIdle(duration: Duration): this.type = {
      context.appendAction(WaitIdleAction(duration))
      this
    }
  }

  sealed trait Reply {
    private[HttpDialog] def context: Context

    /**
     * Chains a simple responder function into the task chain.
     * Only legal after exactly one preceding `send` task. `reply` can be repeated, so the task chain
     * `send(...).reply(...).reply(...).reply(...)` is legal.
     */
    def reply(f: HttpResponse => HttpRequest): this.type = {
      context.appendAction(ReplyAction(f))
      this
    }
  }

  sealed trait AwaitResponse { this: SendSubsequent with SendMany with WaitIdle with AwaitResponse =>
    private[HttpDialog] def context: Context

    /**
     * Delays all subsequent tasks until exactly one pending responses has come in.
     */
    def awaitResponse: SendSubsequent with SendMany with WaitIdle with AwaitResponse = {
      context.appendAction(AwaitResponseAction)
      this
    }
  }

  /**
   * Constructs a new `HttpDialog` for a connection to the given host and port.
   */
  def apply(httpClient: ActorRef, host: String, port: Int = 80)(implicit refFactory: ActorRefFactory) =
    new SendFirst(new Context(refFactory, httpClient, ConnectAction(host, port)))
      with SendMany
      with WaitIdle

}