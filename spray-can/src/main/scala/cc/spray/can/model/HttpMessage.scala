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

package cc.spray.can
package model

import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import cc.spray.io.{Command, Event}
import cc.spray.util._
import java.util.Arrays

sealed trait HttpMessagePart extends Command with Event

sealed trait HttpRequestPart extends HttpMessagePart

sealed trait HttpResponsePart extends HttpMessagePart

sealed trait HttpMessageStartPart extends HttpMessagePart

sealed trait HttpMessageEndPart extends HttpMessagePart

sealed trait HttpMessage extends HttpMessageStartPart with HttpMessageEndPart {
  def headers: List[HttpHeader]
  def body: Array[Byte]
  def protocol: HttpProtocol
}

case class HttpRequest(
  method: HttpMethod = HttpMethods.GET,
  uri: String = "/",
  headers: List[HttpHeader] = Nil,
  body: Array[Byte] = EmptyByteArray,
  protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`
) extends HttpMessage with HttpRequestPart {
  def withBody(body: String, charset: String = "ISO-8859-1") = copy(body = body.getBytes(charset))
  def connectionHeader: Option[String] = headers.mapFind {
    header => if (header.name == "connection") Some(header.value) else None
  }

  override def hashCode() =
    (((method.## * 31 + uri.##) * 31 + headers.##) * 31 + Arrays.hashCode(body)) * 31 + protocol.##

  override def equals(obj: Any) = obj match {
    case x: AnyRef if this eq x => true
    case that: HttpRequest => that.method == method && that.uri == uri && that.headers == headers &&
      Arrays.equals(that.body, body) && that.protocol == protocol
    case _ => false
  }

  override def toString = "HttpRequest(" + method + ", " + uri + ", " + headers + ", " +
    new String(body, "ASCII") + ", " + protocol + ')'
}

object HttpRequest {
  def verify(request: HttpRequest) = {
    import request._
    def req(cond: Boolean, msg: => String) { require(cond, "Illegal HttpRequest: " + msg) }
    req(method != null, "method must not be null")
    req(uri != null && !uri.isEmpty, "uri must not be null or empty")
    req(headers != null, "headers must not be null")
    req(body != null, "body must not be null (you can use cc.spray.io.util.EmptyByteArray for an empty body)")
    headers.foreach { header =>
      if (header.name == "Content-Length" || header.name == "Transfer-Encoding" || header.name == "Host")
        throw new IllegalArgumentException(header.name + " header must not be set explicitly, it is set automatically")
    }
    request
  }
}

case class HttpResponse(
  status: Int = 200,
  headers: List[HttpHeader] = Nil,
  body: Array[Byte] = EmptyByteArray,
  protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`
) extends HttpMessage with HttpResponsePart {
  def withBody(body: String, charset: String = "ISO-8859-1") = copy(body = body.getBytes(charset))
  def bodyAsString: String = if (body.isEmpty) "" else {
    val charset = headers.mapFind {
      case HttpHeader("Content-Type", HttpResponse.ContentTypeCharsetPattern(value)) => Some(value)
      case _ => None
    }
    try {
      new String(body, charset.getOrElse("ISO-8859-1"))
    } catch {
      case e: UnsupportedEncodingException => "<unsupported charset in Content-Type-Header>"
    }
  }

  override def hashCode() =
    (((((status * 31) + headers.##) * 31) + Arrays.hashCode(body)) * 31) + protocol.##

  override def equals(obj: Any) = obj match {
    case x: AnyRef if this eq x => true
    case that: HttpResponse =>
      that.status == status && that.headers == headers && Arrays.equals(that.body, body) && that.protocol == protocol
    case _ => false
  }

  override def toString = "HttpResponse(" + status + ", " + headers + ", " +
    new String(body, "ASCII") + ", " + protocol + ')'
}

object HttpResponse {
  private val ContentTypeCharsetPattern = """.*charset=([-\w]+)""".r

  def verify(response: HttpResponse) = {
    import response._
    def req(cond: Boolean, msg: => String) { require(cond, "Illegal HttpResponse: " + msg) }
    req(100 <= status && status < 600, "Illegal HTTP status code: " + status)
    req(headers != null, "headers must not be null")
    req(body != null, "body must not be null (you can use cc.spray.io.util.EmptyByteArray for an empty body)")
    headers.foreach { header =>
      if (header.name == "Content-Length" || header.name == "Transfer-Encoding" || header.name == "Date")
        throw new IllegalArgumentException(header.name + " header must not be set explicitly, it is set automatically")
    }
    req(body.length == 0 || status / 100 > 1 && status != 204 && status != 304, "Illegal HTTP response: " +
            "responses with status code " + status + " must not have a message body")
    response
  }

  def defaultReason(statusCode: Int) = statusCode match {
    case 100 => "Continue"
    case 101 => "Switching Protocols"

    case 200 => "OK"
    case 201 => "Created"
    case 202 => "Accepted"
    case 203 => "Non-Authoritative Information"
    case 204 => "No Content"
    case 205 => "Reset Content"
    case 206 => "Partial Content"

    case 300 => "Multiple Choices"
    case 301 => "Moved Permanently"
    case 302 => "Found"
    case 303 => "See Other"
    case 304 => "Not Modified"
    case 305 => "Use Proxy"
    case 307 => "Temporary Redirect"

    case 400 => "Bad Request"
    case 401 => "Unauthorized"
    case 402 => "Payment Required"
    case 403 => "Forbidden"
    case 404 => "Not Found"
    case 405 => "Method Not Allowed"
    case 406 => "Not Acceptable"
    case 407 => "Proxy Authentication Required"
    case 408 => "Request Time-out"
    case 409 => "Conflict"
    case 410 => "Gone"
    case 411 => "Length Required"
    case 412 => "Precondition Failed"
    case 413 => "Request Entity Too Large"
    case 414 => "Request-URI Too Large"
    case 415 => "Unsupported Media Type"
    case 416 => "Requested range not satisfiable"
    case 417 => "Expectation Failed"

    case 500 => "Internal Server Error"
    case 501 => "Not Implemented"
    case 502 => "Bad Gateway"
    case 503 => "Service Unavailable"
    case 504 => "Gateway Time-out"
    case 505 => "HTTP Version not supported"
    case _   => "???"
  }
}

case class ChunkedRequestStart(request: HttpRequest) extends HttpMessageStartPart with HttpRequestPart

case class ChunkedResponseStart(response: HttpResponse) extends HttpMessageStartPart with HttpResponsePart

case class MessageChunk(body: Array[Byte], extensions: List[ChunkExtension])
  extends HttpRequestPart with HttpResponsePart {
  require(body.length > 0, "MessageChunk must not have empty body")
  def bodyAsString: String = bodyAsString("ISO-88591-1")
  def bodyAsString(charset: Charset): String = if (body.isEmpty) "" else new String(body, charset)
  def bodyAsString(charset: String): String = if (body.isEmpty) "" else new String(body, charset)
}

object MessageChunk {
  def apply(body: String): MessageChunk =
    apply(body, Nil)
  def apply(body: String, charset: String): MessageChunk =
    apply(body, charset, Nil)
  def apply(body: String, extensions: List[ChunkExtension]): MessageChunk =
    apply(body, "ISO-8859-1", extensions)
  def apply(body: String, charset: String, extensions: List[ChunkExtension]): MessageChunk =
    apply(body.getBytes(charset), extensions)
  def apply(body: Array[Byte]): MessageChunk =
    apply(body, Nil)
}

case class ChunkedMessageEnd(
  extensions: List[ChunkExtension] = Nil,
  trailer: List[HttpHeader] = Nil
) extends HttpRequestPart with HttpResponsePart with HttpMessageEndPart

case class ChunkExtension(name: String, value: String)