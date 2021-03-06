/*
 * Copyright (C) 2011, 2012 Mathias Doenitz
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

package cc.spray.io

import akka.actor._
import java.nio.ByteBuffer
import java.net.InetSocketAddress


abstract class IoPeer extends Actor with ActorLogging {

  def ioWorker: IoWorker

  protected def createConnectionHandle(key: Key, address: InetSocketAddress, commander: ActorRef): Handle =
    SimpleHandle(key, self, address, commander) // default implementation

}

object IoPeer {

  ////////////// COMMANDS //////////////
  case class Close(reason: ConnectionClosedReason) extends Command
  case class Send(buffers: Seq[ByteBuffer]) extends Command
  object Send {
    def apply(buffer: ByteBuffer): Send = Send(Seq(buffer))
  }

  case object StopReading extends Command
  case object ResumeReading extends Command

  // only available with ConnectionActors mixin
  case class Tell(receiver: ActorRef, message: Any, sender: ActorRef) extends Command

  ////////////// EVENTS //////////////
  type Closed = IoWorker.Closed;                val Closed = IoWorker.Closed
  type SendCompleted = IoWorker.SendCompleted;  val SendCompleted = IoWorker.SendCompleted
  type Received = IoWorker.Received;            val Received = IoWorker.Received

}