package fiddle

import scala.annotation.ClassfileAnnotation

object Shared{
  val prelude =
    """
      |import scalatags.JsDom.all._
      |import org.scalajs.dom
      |import fiddle.Page
      |import Page.{red, green, blue, yellow, orange, println}
      |import scalajs.js
    """.stripMargin

  val default = """
    |import scalajs.js
    |object ScalaJSExample extends js.JSApp{
    |  def main() = {
    |    println("Looks like there was an error loading the default Gist!")
    |    println("Loading an empty application so you can get started")
    |  }
    |}
  """.stripMargin

  val gistId = "1d32e23cacb79abc2ec5"
  val githubClientId = "6dd4bf6f1dcd622ddfc3"
  val tokenCookieName = "github_token"
  val url = "."
//  val url = "http://localhost:8080"
}

trait Api{
  def fastOpt(txt: String): (String, Option[String])
  def fullOpt(txt: String): (String, Option[String])
  def export(compiled: String, source: String): String
  def `import`(compiled: String, source: String): String
  def completeStuff(txt: String, flag: String, offset: Int): List[(String, String)]
}
