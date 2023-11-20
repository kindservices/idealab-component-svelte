//> using scala "3.3.1"
//> using lib "com.lihaoyi::requests:0.8.0"
//> using lib "com.lihaoyi::cask:0.9.1"
//> using lib "com.lihaoyi::upickle:3.0.0"

import cask.model.Response
import ujson.Value
import upickle.*
import upickle.default.{macroRW, ReadWriter as RW, *}

import java.time.*
import java.time.format.*
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.ExecutionContext
import scala.util.*
import scala.util.Properties.*
import scala.util.control.NonFatal


// format: off
/**
  * This "backend for front-end" for querying Pinot
  */
// format: on
def env(key: String, default: String = null) =
  def bang = sys.error(
    s"$key env variable not set: ${sys.env.mkString("\n", "\n", "\n")}\n properties:\n ${sys.props.mkString("\n")}"
  )

  sys.env.get(key).orElse(Option(default)).getOrElse(bang)

def countByBucket(
    url: String,
    fromMillis: Long,
    toMillis: Long,
    bucketSizeMinutes: Int
): ujson.Value = {
  require(fromMillis > 0)
  require(toMillis > 0)
  require(bucketSizeMinutes > 0)

  // NOTE: Don't do this! SQL Injection-tastic.
  // pinot has a 'proper' Java client, but we'll just use this for now for convenience
  val sql = s"""SELECT bucket, COUNT(*) as count FROM (
                    SELECT DATETIMECONVERT(timestampInEpoch,'1:MILLISECONDS:EPOCH',
                           '1:MILLISECONDS:SIMPLE_DATE_FORMAT:yyyy-MM-dd HH:mm:ss.SSS','${bucketSizeMinutes}:MINUTES') AS bucket,
                           slug
                    FROM usertrackingdata
                    WHERE timestampInEpoch >= ${fromMillis}
                    AND  timestampInEpoch <= ${toMillis}
                ) GROUP BY bucket"""

  val request =
    ujson.Obj("sql" -> ujson.Str(sql), "queryOptions" -> ujson.Str("useMultistageEngine=true"))

  println(s"count $url using:\n${sql}\n")
  var started = System.currentTimeMillis
  val response = requests.post(
    s"$url/query/sql",
    data = request,
    headers = Map(
      "Content-Type" -> "application/json",
      "Access-Control-Allow-Origin" -> "*" // TODO - security no-no, but hard-coded for now for port-forwarding/debugging
    )
  )
  var took = System.currentTimeMillis - started
  println(s"result took ${took}ms:\n\n ${response.text()}\n")

  val jsonStr = response
    .ensuring(_.statusCode == 200, s"${url} returned ${response.statusCode}: $response")
    .text()

  ujson.read(jsonStr)
}

object App extends cask.MainRoutes {

  val PinotUrl = env("PINOT_BROKER_HOSTPORT")

  def reply(body: ujson.Value = ujson.Null, statusCode: Int = 200) = cask.Response(
    data = body,
    statusCode = statusCode,
    headers = Seq("Access-Control-Allow-Origin" -> "*", "Content-Type" -> "application/json")
  )

  @cask.get("/")
  def poorMansOpenAPI() = cask.Response(
    data = s"""
    <html>
    <ul>
  <li><a href="/health">GET /health</a></li>
  <li>GET /count/:fromEpoch/:toEpoch/:minutesPerBucket <-- query pinot between timestamps</li>
  </ul>
  </html>
  """,
    statusCode = 200,
    headers = Seq("Access-Control-Allow-Origin" -> "*", "Content-Type" -> "text/html")
  )

  @cask.get("/check/:fromEpoch/:toEpoch/:minutesPerBucket")
  def check(fromEpoch: Long, toEpoch: Long, minutesPerBucket: Int, params: Seq[String]) = {
    val url  = params.headOption.getOrElse(PinotUrl)
    val json = countByBucket(url, fromEpoch, toEpoch, minutesPerBucket)
    reply(json)
  }

//
//  override def defaultHandleNotFound(request: cask.Request) : Response.Raw = reply("not found")
//
//  override def defaultHandleMethodNotAllowed(request: cask.Request) : Response.Raw = reply("not allowed")

  @cask.get("/count/:fromEpoch/:toEpoch/:minutesPerBucket")
  def countRange(fromEpoch: Long, toEpoch: Long, minutesPerBucket: Int) = reply {
    countByBucket(PinotUrl, fromEpoch, toEpoch, minutesPerBucket)
  }

  // TODO - this isn't a great health check
  // we'd like to know if we can connect to pinot -- but also want to avoid cascading failures
  @cask.get("/health")
  def getHealthCheck() = s"${ZonedDateTime.now(ZoneId.of("UTC"))}"

  override def host: String = "0.0.0.0"

  override def port = envOrElse("PORT", propOrElse("PORT", 8081.toString)).toInt

  initialize()

  println(
    s""" 🚀 running Pinot BFF on $host:$port {verbose : $verbose, debugMode : $debugMode }  🚀"""
  )
}
