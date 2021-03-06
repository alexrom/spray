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

package cc.spray.can
package parsing

import org.specs2.mutable.Specification
import model._
import HttpProtocols._
import HttpMethods._

class ResponseParserSpec extends Specification {

  "The reponse parsing logic" should {
    "properly parse a response" in {
      "without headers and body" in {
        parse {
          """|HTTP/1.1 200 OK
             |
             |"""
        } mustEqual ErrorState("Content-Length header or chunked transfer encoding required", 411)
      }

      "with one header, a body, but no Content-Length header" in {
        parse {
          """|HTTP/1.0 404 Not Found
             |Host: api.example.com
             |
             |Foobs"""
        } mustEqual (`HTTP/1.0`, 404, "Not Found", List(HttpHeader("host", "api.example.com")), None, "Foobs")
      }

      "with 4 headers and a body" in {
        parse {
          """|HTTP/1.1 500 Internal Server Error
             |User-Agent: curl/7.19.7 xyz
             |Transfer-Encoding:identity
             |Connection:close
             |Content-Length    : 17
             |
             |Shake your BOODY!"""
        } mustEqual (`HTTP/1.1`, 500, "Internal Server Error", List(
          HttpHeader("content-length", "17"),
          HttpHeader("connection", "close"),
          HttpHeader("transfer-encoding", "identity"),
          HttpHeader("user-agent", "curl/7.19.7 xyz")
        ), Some("close"), "Shake your BOODY!")
      }

      "with multi-line headers" in {
        parse {
          """|HTTP/1.0 200 OK
             |User-Agent: curl/7.19.7
             | abc
             |    xyz
             |Accept
             | : */*  """ + """
             |
             |"""
        } mustEqual (`HTTP/1.0`, 200, "OK", List(
          HttpHeader("accept", "*/*"),
          HttpHeader("user-agent", "curl/7.19.7 abc xyz")
        ), None, "")
      }
    }

    "properly parse a" in {
      "chunked response start" in {
        parse {
          """|HTTP/1.1 200 OK
             |User-Agent: curl/7.19.7
             |Transfer-Encoding: chunked
             |
             |3
             |abc"""
        } mustEqual ChunkedStartState(
          StatusLine(`HTTP/1.1`, 200, "OK"),
          List(HttpHeader("transfer-encoding", "chunked"), HttpHeader("user-agent", "curl/7.19.7")),
          None
        )
      }
    }

    "reject a response with" in {
      "HTTP version 1.2" in {
        parse("HTTP/1.2 200 OK\r\n") mustEqual ErrorState("HTTP Version not supported", 505)
      }

      "an illegal status code" in {
        parse("HTTP/1.1 700 Something") mustEqual ErrorState("Illegal response status code")
        parse("HTTP/1.1 2000 Something") mustEqual ErrorState("Illegal response status code")
      }

      "a response status reason longer than 64 chars" in {
        parse("HTTP/1.1 250 x" + "xxxx" * 16 + "\r\n") mustEqual
                ErrorState("Reason phrase exceeds the configured limit of 64 characters")
      }

      "with an illegal char in a header name" in {
        parse {
          """|HTTP/1.1 200 OK
             |User@Agent: curl/7.19.7"""
        } mustEqual ErrorState("Invalid character '@', expected TOKEN CHAR, LWS or COLON")
      }

      "with a header name longer than 64 chars" in {
        parse {
          """|HTTP/1.1 200 OK
             |UserxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxAgent: curl/7.19.7"""
        } mustEqual ErrorState("HTTP header name exceeds the configured limit of 64 characters (userxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx...)")
      }

      "with a header-value longer than 8192 chars" in {
        parse {
          """|HTTP/1.1 200 OK
             |Fancy: 0""" + ("12345678" * 1024) + "\r\n"
        } mustEqual ErrorState("HTTP header value exceeds the configured limit of 8192 characters (header 'fancy')", 400)
      }

      "with an invalid Content-Length header value" in {
        parse {
          """|HTTP/1.1 200 OK
             |Content-Length: 1.5
             |
             |abc"""
        } mustEqual ErrorState("Invalid Content-Length header value: For input string: \"1.5\"", 400)
        parse {
          """|HTTP/1.1 200 OK
             |Content-Length: -3
             |
             |abc"""
        } mustEqual ErrorState("Invalid Content-Length header value: " +
                "requirement failed: Content-Length must not be negative", 400)
      }
    }
  }

  def parse = RequestParserSpec.parse(new EmptyResponseParser(new ParserSettings()), extractFromCompleteMessage _) _

  def extractFromCompleteMessage(completeMessage: CompleteMessageState) = {
    val CompleteMessageState(StatusLine(protocol, status, reason), headers, connectionHeader, body) = completeMessage
    (protocol, status, reason, headers, connectionHeader, new String(body, "ISO-8859-1"))
  }

}
