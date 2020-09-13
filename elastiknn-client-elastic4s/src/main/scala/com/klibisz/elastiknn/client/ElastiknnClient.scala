package com.klibisz.elastiknn.client

import com.klibisz.elastiknn.api._
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.bulk.{BulkResponse, BulkResponseItem}
import com.sksamuel.elastic4s.requests.indexes.PutMappingResponse
import com.sksamuel.elastic4s.requests.searches.{SearchRequest, SearchResponse}
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.elasticsearch.client.RestClient

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

trait ElastiknnClient[F[_]] extends AutoCloseable {

  /**
    * Underlying client from the elastic4s library.
    */
  val elasticClient: ElasticClient

  /**
    * Abstract method for executing a request.
    */
  def execute[T, U](t: T)(implicit handler: Handler[T, U], manifest: Manifest[U]): F[Response[U]]

  /**
    * See [[ElastiknnRequests.putMapping()]].
    */
  def putMapping(index: String, vecField: String, storedIdField: String, vecMapping: Mapping): F[Response[PutMappingResponse]] =
    execute(ElastiknnRequests.putMapping(index, vecField, storedIdField, vecMapping))

  /**
    * Index a batch of vectors as new Elasticsearch docs, one doc per vector.
    * Also see [[ElastiknnRequests.index()]].
    *
    * @param index Index where vectors are stored.
    * @param vecField Field in each doc where vector is stored.
    * @param vecs Sequence of vectors to store.
    * @param storedIdField Field in each doc where ID is stored as a doc value.
    * @param ids Sequence of ids. Assumed one-to-one correspondence to given vectors.
    * @return [[Response]] containing [[BulkResponse]] containing indexing responses.
    */
  def index(index: String, vecField: String, vecs: Seq[Vec], storedIdField: String, ids: Seq[String]): F[Response[BulkResponse]] = {
    val reqs = vecs.zip(ids).map {
      case (vec, id) => ElastiknnRequests.index(index, vecField, vec, storedIdField, id)
    }
    execute(bulk(reqs))
  }

  /**
    * See [[ElastiknnRequests.nearestNeighbors()]].
    */
  def nearestNeighbors(index: String, query: NearestNeighborsQuery, k: Int, storedIdField: String): F[Response[SearchResponse]] = {

    // TODO: make storedIdField optional and avoid the custom handler if None, since the benchmarks only require the score.

    // Handler that reads the id from the stored field and places it in the id field.
    // Otherwise it will be null since [[ElastiknnRequests.nearestNeighbors]] doesn't return stored fields.
    implicit val handler: Handler[SearchRequest, SearchResponse] = new Handler[SearchRequest, SearchResponse] {
      override def build(t: SearchRequest): ElasticRequest = SearchHandler.build(t)
      override def responseHandler: ResponseHandler[SearchResponse] = (response: HttpResponse) => {
        val handled: Either[ElasticError, SearchResponse] = SearchHandler.responseHandler.handle(response)
        handled.map { sr: SearchResponse =>
          val hitsWithIds = sr.hits.hits.map(h =>
            h.copy(id = h.fields.get(storedIdField) match {
              case Some(List(id: String)) => id
              case _                      => ""
            }))
          sr.copy(hits = sr.hits.copy(hits = hitsWithIds))
        }
      }
    }
    execute(ElastiknnRequests.nearestNeighbors(index, query, k, storedIdField))(handler, implicitly[Manifest[SearchResponse]])
  }

}

object ElastiknnClient {

  final case class StrictFailureException(message: String, cause: Throwable = None.orNull) extends RuntimeException(message, cause)

  def futureClient(host: String = "localhost", port: Int = 9200, strictFailure: Boolean = true, timeoutMillis: Int = 30000)(
      implicit ec: ExecutionContext): ElastiknnFutureClient = {
    val rc: RestClient = RestClient
      .builder(new HttpHost(host, port))
      .setRequestConfigCallback(
        (requestConfigBuilder: RequestConfig.Builder) => requestConfigBuilder.setSocketTimeout(timeoutMillis)
      )
      .build()
    val jc: JavaClient = new JavaClient(rc)
    new ElastiknnFutureClient {
      implicit val executor: Executor[Future] = Executor.FutureExecutor(ec)
      implicit val functor: Functor[Future] = Functor.FutureFunctor(ec)
      val elasticClient: ElasticClient = ElasticClient(jc)
      override def execute[T, U](req: T)(implicit handler: Handler[T, U], manifest: Manifest[U]): Future[Response[U]] = {
        val future: Future[Response[U]] = elasticClient.execute(req)
        if (strictFailure) future.flatMap { res =>
          checkResponse(req, res) match {
            case Left(ex) => Future.failed(ex)
            case Right(_) => Future.successful(res)
          }
        } else future
      }

      override def toString: String = s"${ElastiknnClient.getClass.getSimpleName} connected to $host:$port"
      override def close(): Unit = elasticClient.close()
    }
  }

  private def checkResponse[T, U](req: T, res: Response[U]): Either[Throwable, U] = {
    @tailrec
    def findBulkError(bulkResponseItems: Seq[BulkResponseItem], acc: Option[ElasticError] = None): Option[ElasticError] =
      if (bulkResponseItems.isEmpty) acc
      else
        bulkResponseItems.head.error match {
          case Some(err) =>
            Some(
              ElasticError(err.`type`,
                           err.reason,
                           Some(err.index_uuid),
                           Some(err.index),
                           Some(err.shard.toString),
                           Seq.empty,
                           None,
                           None,
                           None,
                           Seq.empty))
          case None => findBulkError(bulkResponseItems.tail, acc)
        }
    if (res.isError) {
      Left(res.error.asException)
    } else if (res.status >= 300) Left(StrictFailureException(s"Returned non-200 response [$res] for request [$req]."))
    else
      res.result match {
        case bulkResponse: BulkResponse if bulkResponse.hasFailures =>
          findBulkError(bulkResponse.items) match {
            case Some(err) => Left(err.asException)
            case None      => Left(StrictFailureException(s"Unknown bulk execution error [$res] for request [$req]."))
          }
        case other => Right(other)
      }
  }

}
