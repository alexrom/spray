package cc.spray
package examples.spraycan

import org.specs2.mutable._
import cc.spray._
import test._
import http._
import HttpMethods._
import StatusCodes._

class DemoServiceSpec extends Specification with SprayTest with DemoService {
  
  "The DemoService" should {
    "return a greeting for GET requests to the root path" in {
      testService(HttpRequest(GET, "/")) {
        demoService
      }.response.content.as[String].right.get must contain("Say hello")
    }
    "leave GET requests to other paths unhandled" in {
      testService(HttpRequest(GET, "/kermit")) {
        demoService
      }.handled must beFalse
    }
    "return a MethodNotAllowed error for PUT requests to the root path" in {
      testService(HttpRequest(PUT, "/")) {
        demoService
      }.response mustEqual HttpResponse(MethodNotAllowed, "HTTP method not allowed, supported methods: GET, POST")
    }
  }
  
}