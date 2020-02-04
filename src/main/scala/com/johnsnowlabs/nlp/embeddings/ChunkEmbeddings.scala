package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.annotators.common.WordpieceEmbeddingsSentence
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel}
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.ml.param.{BooleanParam, Param}
import org.apache.spark.sql.{DataFrame, Dataset}

import scala.collection.Map

object PoolingStrategy {
  object AnnotatorType {
    val AVERAGE = "AVERAGE"
    val SUM = "SUM"
  }
}

class ChunkEmbeddings (override val uid: String) extends AnnotatorModel[ChunkEmbeddings] {

  import com.johnsnowlabs.nlp.AnnotatorType._
  override val outputAnnotatorType: AnnotatorType = WORD_EMBEDDINGS

  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(CHUNK, WORD_EMBEDDINGS)

  val poolingStrategy = new Param[String](this, "poolingStrategy",
    "Choose how you would like to aggregate Word Embeddings to Chunk Embeddings: AVERAGE or SUM")

  val skipOOV = new BooleanParam(this, "skipOOV",
    "Whether to discard default vectors for OOV words from the aggregation / pooling")

  def setPoolingStrategy(strategy: String): this.type = {
    strategy.toLowerCase() match {
      case "average" => set(poolingStrategy, "AVERAGE")
      case "sum" => set(poolingStrategy, "SUM")
      case _ => throw new MatchError("poolingStrategy must be either AVERAGE or SUM")
    }
  }

  def setSkipOOV(value: Boolean): this.type = set(skipOOV, value)

  def getPoolingStrategy = $(poolingStrategy)
  def getSkipOOV= $(skipOOV)

  setDefault(
    inputCols -> Array(CHUNK, WORD_EMBEDDINGS),
    outputCol -> "chunk_embeddings",
    poolingStrategy -> "AVERAGE",
    skipOOV -> true
  )

  /** Internal constructor to submit a random UID */
  def this() = this(Identifiable.randomUID("CHUNK_EMBEDDINGS"))

  private def calculateChunkEmbeddings(matrix : Array[Array[Float]]):Array[Float] = {
    val res = Array.ofDim[Float](matrix(0).length)
    matrix(0).indices.foreach {
      j =>
        matrix.indices.foreach {
          i =>
            res(j) += matrix(i)(j)
        }
        if($(poolingStrategy) == "AVERAGE")
          res(j) /= matrix.length
    }
    res
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {

    val documentsWithChunks = annotations
      .filter(token => token.annotatorType == CHUNK)
      .groupBy(_.metadata.getOrElse[String]("sentence", "0").toInt)
      .toSeq
      .sortBy(_._1)

    val embeddingsSentences = WordpieceEmbeddingsSentence.unpack(annotations)

    documentsWithChunks.flatMap { sentences =>
      sentences._2.flatMap { chunk =>

        val sentenceId = chunk.metadata("sentence")

        //TODO: Check why some chunks end up without WordEmbeddings
        if(sentenceId.toInt < embeddingsSentences.length) {

          val tokensWithEmbeddings = embeddingsSentences(sentenceId.toInt).tokens.filter(
            token => token.begin >= chunk.begin && token.end <= chunk.end
          )

          val allEmbeddings = tokensWithEmbeddings.flatMap(tokenEmbedding =>
            if (!tokenEmbedding.isOOV || !$(skipOOV))
              Some(tokenEmbedding.embeddings)
            else
              None
          )

          val finalEmbeddings = if (allEmbeddings.length > 0) allEmbeddings else tokensWithEmbeddings.map(_.embeddings)
          if(finalEmbeddings.length > 0) {
            Some(Annotation(
              annotatorType = outputAnnotatorType,
              begin = chunk.begin,
              end = chunk.end,
              result = chunk.result,
              metadata = Map("sentence" -> sentenceId.toString,
                "token" -> chunk.result.toString,
                "pieceId" -> "-1",
                "isWordStart" -> "true"
              ),
              embeddings = calculateChunkEmbeddings(finalEmbeddings)
            ))
          } else{
            None
          }
        } else {
          None
        }
      }
    }
  }

  override protected def afterAnnotate(dataset: DataFrame): DataFrame = {
    val embeddingsCol = Annotation.getColumnByType(dataset, $(inputCols), WORD_EMBEDDINGS)
    dataset.withColumn(getOutputCol, dataset.col(getOutputCol).as(getOutputCol, embeddingsCol.metadata))
  }

}

object ChunkEmbeddings extends DefaultParamsReadable[ChunkEmbeddings]