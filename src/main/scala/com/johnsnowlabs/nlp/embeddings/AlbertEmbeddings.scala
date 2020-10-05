package com.johnsnowlabs.nlp.embeddings

import java.io.File

import com.johnsnowlabs.ml.tensorflow._
import com.johnsnowlabs.ml.tensorflow.sentencepiece._
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common._
import com.johnsnowlabs.nlp.annotators.ld.dl.LanguageDetectorDL
import com.johnsnowlabs.storage.HasStorageRef
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.param.{IntArrayParam, IntParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, SparkSession}

/** ALBERT: A LITE BERT FOR SELF-SUPERVISED LEARNING OF LANGUAGE REPRESENTATIONS - Google Research, Toyota Technological Institute at Chicago
  * This these embeddings represent the outputs generated by the Albert model.
  * All offical Albert releases by google in TF-HUB are supported with this Albert Wrapper:
  *
  * '''TF-hub Models :'''
  *
  * albert_base     = [[https://tfhub.dev/google/albert_base/3]]    |  768-embed-dim,   12-layer,  12-heads, 12M parameters
  *
  * albert_large    = [[https://tfhub.dev/google/albert_large/3]]   |  1024-embed-dim,  24-layer,  16-heads, 18M parameters
  *
  * albert_xlarge   = [[https://tfhub.dev/google/albert_xlarge/3]]  |  2048-embed-dim,  24-layer,  32-heads, 60M parameters
  *
  * albert_xxlarge  = [[https://tfhub.dev/google/albert_xxlarge/3]] |  4096-embed-dim,  12-layer,  64-heads, 235M parameters
  *
  * This model requires input tokenization with SentencePiece model, which is provided by Spark-NLP (See tokenizers package)
  *
  * '''Sources :'''
  *
  * [[https://arxiv.org/pdf/1909.11942.pdf]]
  *
  * [[https://github.com/google-research/ALBERT]]
  *
  * [[https://tfhub.dev/s?q=albert]]
  *
  * '''Paper abstract :'''
  *
  * Increasing model size when pretraining natural language representations often results in improved performance on downstream tasks. However, at some point further model increases become harder due to GPU/TPU memory limitations and
  * longer training times. To address these problems, we present two parameterreduction techniques to lower memory consumption and increase the training
  * speed of BERT (Devlin et al., 2019). Comprehensive empirical evidence shows
  * that our proposed methods lead to models that scale much better compared to
  * the original BERT. We also use a self-supervised loss that focuses on modeling
  * inter-sentence coherence, and show it consistently helps downstream tasks with
  * multi-sentence inputs. As a result, our best model establishes new state-of-the-art
  * results on the GLUE, RACE, and SQuAD benchmarks while having fewer parameters compared to BERT-large.
  *
  * ''Tips :''
  * ALBERT uses repeating layers which results in a small memory footprint,
  * however the computational cost remains similar to a BERT-like architecture with
  * the same number of hidden layers as it has to iterate through the same number of (repeating) layers.
  */
class AlbertEmbeddings(override val uid: String)
  extends AnnotatorModel[AlbertEmbeddings]
    with WithAnnotate[AlbertEmbeddings]
    with WriteTensorflowModel
    with WriteSentencePieceModel
    with HasEmbeddingsProperties
    with HasStorageRef
    with HasCaseSensitiveProperties {

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val inputAnnotatorTypes: Array[String] = Array(AnnotatorType.DOCUMENT, AnnotatorType.TOKEN)
  override val outputAnnotatorType: AnnotatorType = AnnotatorType.WORD_EMBEDDINGS

  val batchSize = new IntParam(this, "batchSize", "Batch size. Large values allows faster processing but requires more memory.")
  val configProtoBytes = new IntArrayParam(this, "configProtoBytes", "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()")
  val maxSentenceLength = new IntParam(this, "maxSentenceLength", "Max sentence length to process")

  private var _model: Option[Broadcast[TensorflowAlbert]] = None

  def this() = this(Identifiable.randomUID("ALBERT_EMBEDDINGS"))

  def setBatchSize(size: Int): this.type = {
    if (get(batchSize).isEmpty)
      set(batchSize, size)
    this
  }

  override def setDimension(value: Int): this.type = {
    if (get(dimension).isEmpty)
      set(this.dimension, value)
    this

  }

  def setMaxSentenceLength(value: Int): this.type = {
    require(value <= 512, "ALBERT models do not support sequences longer than 512 because of trainable positional embeddings")

    if(get(maxSentenceLength).isEmpty)
      set(maxSentenceLength, value)
    this
  }

  def getMaxSentenceLength: Int = $(maxSentenceLength)

  def setConfigProtoBytes(bytes: Array[Int]): AlbertEmbeddings.this.type = set(this.configProtoBytes, bytes)

  def getConfigProtoBytes: Option[Array[Byte]] = get(this.configProtoBytes).map(_.map(_.toByte))

  setDefault(
    batchSize -> 32,
    dimension -> 768,
    maxSentenceLength -> 128,
    caseSensitive -> false
  )

  def setModelIfNotSet(spark: SparkSession, tensorflow: TensorflowWrapper, spp: SentencePieceWrapper): this.type = {
    if (_model.isEmpty) {

      _model = Some(
        spark.sparkContext.broadcast(
          new TensorflowAlbert(
            tensorflow,
            spp,
            batchSize = $(batchSize),
            configProtoBytes = getConfigProtoBytes
          )
        )
      )
    }

    this
  }
  def getModelIfNotSet: TensorflowAlbert = _model.get.value

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val tokenizedSentences = TokenizedWithSentence.unpack(annotations)

    /*Return empty if the real tokens are empty*/
    if(tokenizedSentences.nonEmpty) {
      val embeddings = getModelIfNotSet.calculateEmbeddings(
        tokenizedSentences,
        $(batchSize),
        $(maxSentenceLength),
        $(caseSensitive)
      )
      WordpieceEmbeddingsSentence.pack(embeddings)
    } else {
      Seq.empty[Annotation]
    }
  }

  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)
    writeTensorflowModel(path, spark, getModelIfNotSet.tensorflow, "_albert", AlbertEmbeddings.tfFile, configProtoBytes = getConfigProtoBytes)
    writeSentencePieceModel(path, spark, getModelIfNotSet.spp, "_albert",  AlbertEmbeddings.sppFile)

  }

  override protected def afterAnnotate(dataset: DataFrame): DataFrame = {
    dataset.withColumn(getOutputCol, wrapEmbeddingsMetadata(dataset.col(getOutputCol), $(dimension), Some($(storageRef))))
  }

}

trait ReadablePretrainedAlbertModel extends ParamsAndFeaturesReadable[AlbertEmbeddings] with HasPretrained[AlbertEmbeddings] {
  override val defaultModelName: Some[String] = Some("albert_base_uncased")
  /** Java compliant-overrides */
  override def pretrained(): AlbertEmbeddings = super.pretrained()
  override def pretrained(name: String): AlbertEmbeddings = super.pretrained(name)
  override def pretrained(name: String, lang: String): AlbertEmbeddings = super.pretrained(name, lang)
  override def pretrained(name: String, lang: String, remoteLoc: String): AlbertEmbeddings = super.pretrained(name, lang, remoteLoc)
}

trait ReadAlbertTensorflowModel extends ReadTensorflowModel with ReadSentencePieceModel {
  this: ParamsAndFeaturesReadable[AlbertEmbeddings] =>

  override val tfFile: String = "albert_tensorflow"
  override val sppFile: String = "albert_spp"

  def readTensorflow(instance: AlbertEmbeddings, path: String, spark: SparkSession): Unit = {
    val tf = readTensorflowModel(path, spark, "_albert_tf", initAllTables = true)
    val spp = readSentencePieceModel(path, spark, "_albert_spp", sppFile)
    instance.setModelIfNotSet(spark, tf, spp)
  }

  addReader(readTensorflow)

  def loadSavedModel(folder: String, spark: SparkSession): AlbertEmbeddings = {

    val f = new File(folder)
    val sppModelPath = folder+"/assets"
    val savedModel = new File(folder, "saved_model.pb")
    val sppModel = new File(sppModelPath, "30k-clean.model")

    require(f.exists, s"Folder $folder not found")
    require(f.isDirectory, s"File $folder is not folder")
    require(
      savedModel.exists(),
      s"savedModel file saved_model.pb not found in folder $folder"
    )
    require(sppModel.exists(), s"SentencePiece model 30k-clean.model not found in folder $sppModelPath")

    val wrapper = TensorflowWrapper.read(folder, zipped = false, useBundle = true, tags = Array("serve"), initAllTables = true)
    val spp = SentencePieceWrapper.read(sppModel.toString)

    val albert = new AlbertEmbeddings()
      .setModelIfNotSet(spark, wrapper, spp)
    albert
  }
}


object AlbertEmbeddings extends ReadablePretrainedAlbertModel with ReadAlbertTensorflowModel with ReadSentencePieceModel
