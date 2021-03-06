/*
 * Copyright (C) 2011 Mathias Doenitz
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

package cc.spray
package typeconversion

import http._
import MediaTypes._
import HttpCharsets._
import xml.NodeSeq
import java.nio.CharBuffer
import akka.dispatch.Future
import akka.actor.{ActorRefFactory, Props, PoisonPill, Actor}

trait DefaultMarshallers extends MultipartMarshallers {

  def byteArrayMarshaller(contentType: ContentType) = new SimpleMarshaller[Array[Byte]] {
    val canMarshalTo = contentType :: Nil
    def marshal(value: Array[Byte], contentType: ContentType) = HttpContent(contentType, value)
  }

  implicit lazy val ByteArrayMarshaller = byteArrayMarshaller(`application/octet-stream`)

  implicit lazy val CharArrayMarshaller = new SimpleMarshaller[Array[Char]] {
    val canMarshalTo = ContentType(`text/plain`) :: Nil
    def marshal(value: Array[Char], contentType: ContentType) = {
      val nioCharset = contentType.charset.getOrElse(`ISO-8859-1`).nioCharset
      val charBuffer = CharBuffer.wrap(value)
      val byteBuffer = nioCharset.encode(charBuffer)
      HttpContent(contentType, byteBuffer.array)
    }
  }

  implicit lazy val StringMarshaller = new SimpleMarshaller[String] {
    val canMarshalTo = ContentType(`text/plain`) :: Nil
    def marshal(value: String, contentType: ContentType) = HttpContent(contentType, value)
  }

  implicit lazy val NodeSeqMarshaller = new SimpleMarshaller[NodeSeq] {
    val canMarshalTo = ContentType(`text/xml`) ::
                       ContentType(`text/html`) ::
                       ContentType(`application/xhtml+xml`) :: Nil
    def marshal(value: NodeSeq, contentType: ContentType) = StringMarshaller.marshal(value.toString, contentType)
  }

  implicit lazy val FormDataMarshaller = new SimpleMarshaller[FormData] {
    val canMarshalTo = ContentType(`application/x-www-form-urlencoded`) :: Nil
    def marshal(formContent: FormData, contentType: ContentType) = {
      import java.net.URLEncoder.encode
      val charset = contentType.charset.getOrElse(`ISO-8859-1`).aliases.head
      val keyValuePairs = formContent.fields.map {
        case (key, value) => encode(key, charset) + '=' + encode(value, charset)
      }
      StringMarshaller.marshal(keyValuePairs.mkString("&"), contentType)
    }
  }

  implicit lazy val ThrowableMarshaller = new Marshaller[Throwable] {
    def apply(sel: ContentTypeSelector) = MarshalWith(_.handleError)
  }

  implicit def streamMarshaller[T](implicit marshaller: Marshaller[T],
                                   refFactory: ActorRefFactory): Marshaller[Stream[T]] = new Marshaller[Stream[T]] {
    def apply(selector: ContentTypeSelector) = {
      marshaller(selector) match {
        case x: CantMarshal => x
        case MarshalWith(converter) => MarshalWith { ctx => stream =>
          refFactory.actorOf(Props(new ChunkingActor(ctx, stream, converter))) ! stream
        }
      }
    }

    class ChunkingActor(ctx: MarshallingContext, stream: Stream[T],
                        converter: MarshallingContext => T => Unit) extends Actor {
      var chunkSender: Option[ChunkSender] = None
      def receive = { case current #:: remaining =>
        converter {
          new MarshallingContext {
            def startChunkedMessage(contentType: ContentType) = sys.error("Cannot marshal a stream of streams")
            def handleError(error: Throwable) { ctx.handleError(error) }
            def marshalTo(content: HttpContent) {
              chunkSender orElse {
                chunkSender = Some(ctx.startChunkedMessage(content.contentType))
                chunkSender
              } foreach { sender =>
                sender.sendChunk(MessageChunk(content.buffer)).onSuccess { case () =>
                  // we only send the next chunk when the previous has actually gone out
                  self ! {
                    if (remaining.isEmpty) {
                      sender.close()
                      PoisonPill
                    } else remaining
                  }
                }
              }
            }
          }
        } apply(current.asInstanceOf[T])
      }
    }
  }

  implicit def optionMarshaller[T : Marshaller] = new Marshaller[Option[T]] {
    val m = marshaller[T]
    def apply(sel: ContentTypeSelector) = m(sel) match {
      case x: CantMarshal => x
      case MarshalWith(f) => MarshalWith { ctx =>
        val convert = f(ctx)
        _ match {
          case Some(value) => convert(value)
          case None => ctx.handleError(HttpException(StatusCodes.NotFound))
        }
      }
    }
  }

  implicit def eitherMarshaller[A :Marshaller, B :Marshaller] = new Marshaller[Either[A, B]] {
    val ma = marshaller[A]
    val mb = marshaller[B]
    def apply(sel: ContentTypeSelector) = ma(sel) match {
      case x: CantMarshal => x
      case MarshalWith(fa) => mb(sel) match {
        case x: CantMarshal => x
        case MarshalWith(fb) => MarshalWith { ctx =>
          lazy val converta = fa(ctx)
          lazy val convertb = fb(ctx);
          {
            case Right(value) => convertb(value)
            case Left(value) => converta(value)
          }
        }
      }
    }
  }

  implicit def futureMarshaller[T :Marshaller] = new Marshaller[Future[T]] {
    val m = marshaller[T]
    def apply(sel: ContentTypeSelector) = m(sel) match {
      case x: CantMarshal => x
      case MarshalWith(f) => MarshalWith { ctx =>
        val convert = f(ctx)
        _.onComplete {
          case Right(value) => convert(value)
          case Left(error) => ctx.handleError(error)
        }
      }
    }
  }

  // As a fallback we allow marshalling without content negotiation from objects that are implicitly convertible to
  // HttpContent (most notably HttpContent itself). Note that relying on this might make the application not HTTP spec
  // conformant since the Accept headers the client sent with the request are completely ignored
  implicit def view2Marshaller[T](implicit converter: T => HttpContent) = new Marshaller[T] {
    def apply(selector: ContentTypeSelector) = MarshalWith(ctx => value => ctx.marshalTo(converter(value)))
  }
}

object DefaultMarshallers extends DefaultMarshallers