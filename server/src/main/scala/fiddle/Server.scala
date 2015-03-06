package fiddle
import acyclic.file
import spray.http._
import spray.http.HttpHeaders._
import spray.httpx.encoding.Gzip
import spray.routing.directives.CachingDirectives._
import akka.actor.ActorSystem
import spray.routing.directives.CacheKeyer
import scala.collection.mutable
import spray.client.pipelining._

import spray.http.HttpRequest
import scala.Some
import spray.http.HttpResponse
import spray.routing._
import upickle._
import org.scalajs.core.tools.classpath.PartialClasspath
import scala.annotation.{ClassfileAnnotation, StaticAnnotation, Annotation}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Properties
import scala.concurrent.Future

import akka.util.Timeout
import akka.pattern.ask
import akka.io.IO

import spray.can.Http
import spray.http._
import spray.http.StatusCodes._
import HttpMethods._
import java.security.MessageDigest

object Server extends SimpleRoutingApp with Api{
  implicit val system = ActorSystem()
  import system.dispatcher
  val clientFiles = Seq("client-opt.js")

  private object AutowireServer
      extends autowire.Server[String, upickle.Reader, upickle.Writer] {
    def write[Result: Writer](r: Result) = upickle.write(r)
    def read[Result: Reader](p: String) = upickle.read[Result](p)

    val routes = AutowireServer.route[Api](Server)
  }

  def main(args: Array[String]): Unit = {
    implicit val Default: CacheKeyer = CacheKeyer {
      case RequestContext(HttpRequest(_, uri, _, entity, _), _, _) => (uri, entity)
    }

    val simpleCache = routeCache()
//    println("Power On Self Test")
//    val res = Compiler.compile(fiddle.Shared.default.getBytes, println)
//    val optimized = res.get |> Compiler.fullOpt |> Compiler.export
//    assert(optimized.contains("Looks like"))
//    println("Power On Self Test complete: " + optimized.length + " bytes")

    val p = Properties.envOrElse("PORT", "8080").toInt
    startServer("0.0.0.0", port = p) {
      cache(simpleCache) {
        encodeResponse(Gzip) {
          get {
            pathSingleSlash {
              complete{
                HttpEntity(
                  MediaTypes.`text/html`,
                  Static.page(
                    s"Client().gistMain('.', [])",
                    clientFiles,
                    "Loading gist..."
                  )
                )
              }
            }
          } ~
          path("gist" / Segments /){ i =>
            get {
              complete{
                HttpEntity(
                  MediaTypes.`text/html`,
                  Static.page(
                    s"Client().gistMain('../..', ${write(i)})",
                    clientFiles,
                    "Loading gist...",
                    relativePathToAssets = "../.."
                  )
                )
              }
            }
          } ~
          path("gist" / Segments){ segments =>
            post {
              segments match {
                case gid :: "api" :: s => respondToApiCall(s)
                case _ => complete("FAIL")
              }
            } ~
            get {
              segments match {
                case gid :: "compiled" :: Nil => loadCompileComplete(Seq(gid))
                case _ => complete("Don't know what you want :p")
              }
            }
          } ~
          getFromResourceDirectory("") ~
          post {
            path("api" / Segments){ s =>
              respondToApiCall(s)
            }
          } ~
          get {
            path("compiled" / Segments){ s =>
              loadCompileComplete(s)
            }
          }
        }
      }
    }
  }

  def respondToApiCall(s: List[String]) = {
    extract(_.request.entity.asString) { e =>
      complete {
        AutowireServer.routes(
          autowire.Core.Request(s, upickle.read[Map[String, String]](e))
        )
      }
    }
  }

  def loadCompileComplete(s: Seq[String]) = {
    val path = s.mkString("/")
    implicit val timeout: Timeout = Timeout(15.seconds)
    val gistUrl = s"https://api.github.com/gists/$path"
    val response: Future[HttpResponse] =
      (IO(Http) ? Get(gistUrl)).mapTo[HttpResponse]
    onSuccess(response) { resp =>
      val gist = read[Gist](resp.entity.asString)
      val scalaCode = gist.files.head._2.content
      val theMd5 = md5(scalaCode)
      val fileName = s"${path}_$theMd5.js"
      if (new java.io.File(fileName).exists) {
        val source = scala.io.Source.fromFile(fileName)
        val jsCode = source.mkString
        source.close()
        complete(jsCode)
      } else {
        fastOpt(scalaCode) match {
          case (_, Some(jsCode)) =>
            persistCode(jsCode, fileName)
            complete(jsCode)
          case (error, None) =>
            complete(InternalServerError, s"Could not compile to JS:\n$error")
        }
      }
    }
  }

  case class GistFile(content: String)
  case class Gist(files: Map[String, GistFile])

  def fastOpt(txt: String) = compileStuff(txt, _ |> Compiler.fastOpt |> Compiler.export)
  def fullOpt(txt: String) = compileStuff(txt, _ |> Compiler.fullOpt |> Compiler.export)
  def export(compiled: String, source: String) = {
    renderCode(compiled, Nil, source, "Page().exportMain(); ScalaJSExample().main();", analytics = false)
  }
  def `import`(compiled: String, source: String) = {
    renderCode(compiled, clientFiles, source, "Client().importMain(); ScalaJSExample().main();", analytics = true)
  }
  def renderCode(compiled: String, srcFiles: Seq[String], source: String, bootFunc: String, analytics: Boolean) = {
    Static.page(bootFunc, srcFiles, source, compiled, analytics)
  }

  def completeStuff(txt: String, flag: String, offset: Int): List[(String, String)] = {
    Await.result(Compiler.autocomplete(txt, flag, offset), 100.seconds)
  }

  def compileStuff(code: String, processor: PartialClasspath => String) = {

    val output = mutable.Buffer.empty[String]

    val res = Compiler.compile(
      code.getBytes,
      output.append(_)
    )

    (output.mkString, res.map(processor))
  }

  def md5(s: String) = {
    MessageDigest.getInstance("MD5").digest(s.getBytes).map("%02X".format(_)).mkString
  }

  def persistCode(code: String, fileName: String): Unit = {
    scala.tools.nsc.io.File(fileName).writeAll(code)
  }
}
