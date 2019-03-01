package com.johnsnowlabs.nlp.util.io

import java.io._
import java.net.{URL, URLDecoder}
import java.nio.file.{Files, Paths}
import java.util.jar.JarFile

import com.johnsnowlabs.nlp.annotators.Tokenizer
import com.johnsnowlabs.nlp.annotators.common.{TaggedSentence, TaggedWord}
import com.johnsnowlabs.nlp.util.io.ReadAs._
import com.johnsnowlabs.nlp.{DocumentAssembler, Finisher}
import org.apache.hadoop.fs.{FileSystem, LocatedFileStatus, Path, RemoteIterator}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.{udf, split, concat_ws, lit}
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.io.BufferedSource
import scala.io.Source

/**
  * Created by saif on 28/04/17.
  */

/**
  * Helper one-place for IO management. Streams, source and external input should be handled from here
  */
object ResourceHelper {

  val spark: SparkSession = SparkSession.builder()
    .appName("SparkNLP-Default-Spark")
    .master("local[*]")
    .config("spark.driver.memory","8G")
    .config("spark.driver.maxResultSize", "2G")
    .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .config("spark.kryoserializer.buffer.max", "500m")
    .getOrCreate()

  private def inputStreamOrSequence(fs: FileSystem, files: RemoteIterator[LocatedFileStatus]): InputStream = {
    val firstFile = files.next
    if (files.hasNext) {
      new SequenceInputStream(fs.open(firstFile.getPath), inputStreamOrSequence(fs, files))
    } else {
      fs.open(firstFile.getPath)
    }
  }

  /** Structure for a SourceStream coming from compiled content */
  case class SourceStream(resource: String) {
    val pipe: Option[InputStream] =
        /** Check whether it exists in file system */
      Option {
        val path = new Path(resource)
        val fs = FileSystem.get(path.toUri, spark.sparkContext.hadoopConfiguration)
        val files = fs.listFiles(new Path(resource), true)
        if (files.hasNext) inputStreamOrSequence(fs, files) else null
      }
    val content: BufferedSource = pipe.map(p => {
      new BufferedSource(p)("UTF-8")
    }).getOrElse(throw new FileNotFoundException(s"file or folder: $resource not found"))
    def close(): Unit = {
      content.close()
      pipe.foreach(_.close)
    }
  }

  private def fixTarget(path: String): String = {
    val toSearch = s"^.*target\\${File.separator}.*scala-.*\\${File.separator}.*classes\\${File.separator}"
    if (path.matches(toSearch + ".*")) {
      path.replaceFirst(toSearch, "")
    }
    else {
      path
    }
  }

  def getResourceStream(path: String): InputStream = {
    Option(getClass.getResourceAsStream(path))
      .getOrElse{
        getClass.getClassLoader().getResourceAsStream(path)
      }
  }

  def getResourceFile(path: String): URL = {
    var dirURL = getClass.getResource(path)

    if (dirURL == null)
      dirURL = getClass.getClassLoader.getResource(path)

    dirURL
  }

  def copyResourceToTmp(path: String): File = {
    val stream = getResourceStream(path)
    val tmp = File.createTempFile("spark-nlp", "")
    val target = new BufferedOutputStream(new FileOutputStream(tmp))

    val buffer = new Array[Byte](1 << 13)
    var read = stream.read(buffer)
    while (read > 0) {
      target.write(buffer, 0, read)
      read = stream.read(buffer)
    }
    stream.close()
    target.close()

    tmp
  }

  def listResourceDirectory(path: String): Seq[String] = {
    val dirURL = getResourceFile(path)

    if (dirURL != null && dirURL.getProtocol.equals("file") && new File(dirURL.toURI).exists()) {
      /* A file path: easy enough */
      return new File(dirURL.toURI).listFiles.sorted.map(_.getPath).map(fixTarget(_))
    } else if (dirURL == null) {
        /* path not in resources and not in disk */
        throw new FileNotFoundException(path)
    }

    if (dirURL.getProtocol.equals("jar")) {
      /* A JAR path */
      val jarPath = dirURL.getPath.substring(5, dirURL.getPath.indexOf("!")) //strip out only the JAR file
      val jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))
      val entries = jar.entries()
      val result = new ArrayBuffer[String]()

      val pathToCheck = path
        .stripPrefix(File.separator.replaceAllLiterally("\\", "/"))
        .stripSuffix(File.separator) +
        File.separator.replaceAllLiterally("\\", "/")

      while(entries.hasMoreElements) {
        val name = entries.nextElement().getName.stripPrefix(File.separator)
        if (name.startsWith(pathToCheck)) { //filter according to the path
          var entry = name.substring(pathToCheck.length())
          val checkSubdir = entry.indexOf("/")
          if (checkSubdir >= 0) {
            // if it is a subdirectory, we just return the directory name
            entry = entry.substring(0, checkSubdir)
          }
          if (entry.nonEmpty) {
            result.append(pathToCheck + entry)
          }
        }
      }
      return result.distinct.sorted
    }

    throw new UnsupportedOperationException(s"Cannot list files for URL $dirURL")
  }

  /**
    * General purpose key value parser from source
    * Currently read only text files
    * @return
    */
  def parseKeyValueText(
                         er: ExternalResource
                        ): Map[String, String] = {
    er.readAs match {
      case LINE_BY_LINE =>
        val sourceStream = SourceStream(er.path)
        val res = sourceStream.content.getLines.map (line => {
          val kv = line.split (er.options("delimiter")).map (_.trim)
          (kv.head, kv.last)
        }).toMap
        sourceStream.close()
        res
      case SPARK_DATASET =>
        import spark.implicits._
        val dataset = spark.read.options(er.options).format(er.options("format"))
          .options(er.options)
          .option("delimiter", er.options("delimiter"))
          .load(er.path)
          .toDF("key", "value")
        val keyValueStore = MMap.empty[String, String]
        dataset.as[(String, String)].foreach{kv => keyValueStore(kv._1) = kv._2}
        keyValueStore.toMap
      case _ =>
        throw new Exception("Unsupported readAs")
    }
  }

  /**
    * General purpose line parser from source
    * Currently read only text files
    * @return
    */
  def parseLines(
                      er: ExternalResource
                     ): Array[String] = {
    er.readAs match {
      case LINE_BY_LINE =>
        val sourceStream = SourceStream(er.path)
        val res = sourceStream.content.getLines.toArray
        sourceStream.close()
        res
      case SPARK_DATASET =>
        import spark.implicits._
        spark.read.options(er.options).format(er.options("format")).load(er.path).as[String].collect
      case _ =>
        throw new Exception("Unsupported readAs")
    }
  }

  /**
    * General purpose tuple parser from source
    * Currently read only text files
    * @return
    */
  def parseTupleText(
                         er: ExternalResource
                       ): Array[(String, String)] = {
    er.readAs match {
      case LINE_BY_LINE =>
        val sourceStream = SourceStream(er.path)
        val res = sourceStream.content.getLines.filter(_.nonEmpty).map (line => {
          val kv = line.split (er.options("delimiter")).map (_.trim)
          (kv.head, kv.last)
        }).toArray
        sourceStream.close()
        res
      case SPARK_DATASET =>
        import spark.implicits._
        val dataset = spark.read.options(er.options).format(er.options("format")).load(er.path)
        val lineStore = spark.sparkContext.collectionAccumulator[String]
        dataset.as[String].foreach(l => lineStore.add(l))
        val result = lineStore.value.toArray.map(line => {
          val kv = line.toString.split (er.options("delimiter")).map (_.trim)
          (kv.head, kv.last)
        })
        lineStore.reset()
        result
      case _ =>
        throw new Exception("Unsupported readAs")
    }
  }

  /**
    * General purpose tuple parser from source
    * Currently read only text files
    * @return
    */
  def parseTupleSentences(
                      er: ExternalResource
                    ): Array[TaggedSentence] = {
    er.readAs match {
      case LINE_BY_LINE =>
        val sourceStream = SourceStream(er.path)
        val result = sourceStream.content.getLines.filter(_.nonEmpty).map(line => {
          line.split("\\s+").filter(kv => {
            val s = kv.split(er.options("delimiter").head)
            s.length == 2 && s(0).nonEmpty && s(1).nonEmpty
          }).map(kv => {
            val p = kv.split(er.options("delimiter").head)
            TaggedWord(p(0), p(1))
          })
        }).toArray
        sourceStream.close()
        result.map(TaggedSentence(_))
      case SPARK_DATASET =>
        import spark.implicits._
        val dataset = spark.read.options(er.options).format(er.options("format")).load(er.path)
        val result = dataset.as[String].filter(_.nonEmpty).map(line => {
          line.split("\\s+").filter(kv => {
            val s = kv.split(er.options("delimiter").head)
            s.length == 2 && s(0).nonEmpty && s(1).nonEmpty
          }).map(kv => {
            val p = kv.split(er.options("delimiter").head)
            TaggedWord(p(0), p(1))
          })
        }).collect
        result.map(TaggedSentence(_))
      case _ =>
        throw new Exception("Unsupported readAs")
    }
  }

  def parseTupleSentencesDS(
                           er: ExternalResource
                         ): Dataset[TaggedSentence] = {
    er.readAs match {
      case SPARK_DATASET =>
        import spark.implicits._
        val dataset = spark.read.options(er.options).format(er.options("format")).load(er.path)
        val result = dataset.as[String].filter(_.nonEmpty).map(line => {
          line.split("\\s+").filter(kv => {
            val s = kv.split(er.options("delimiter").head)
            s.length == 2 && s(0).nonEmpty && s(1).nonEmpty
          }).map(kv => {
            val p = kv.split(er.options("delimiter").head)
            TaggedWord(p(0), p(1))
          })
        })
        result.map(TaggedSentence(_))
      case _ =>
        throw new Exception("Unsupported readAs. If you're training POS with large dataset, consider PerceptronApproachDistributed")
    }
  }

  /**
    * For multiple values per keys, this optimizer flattens all values for keys to have constant access
    */
  def flattenRevertValuesAsKeys(er: ExternalResource): Map[String, String] = {
    er.readAs match {
      case LINE_BY_LINE =>
        val m: MMap[String, String] = MMap()
        val sourceStream = SourceStream(er.path)
        sourceStream.content.getLines.foreach(line => {
          val kv = line.split(er.options("keyDelimiter")).map(_.trim)
          val key = kv(0)
          val values = kv(1).split(er.options("valueDelimiter")).map(_.trim)
          values.foreach(m(_) = key)
        })
        sourceStream.close()
        m.toMap
      case SPARK_DATASET =>
        import spark.implicits._
        val dataset = spark.read.options(er.options).format(er.options("format")).load(er.path)
        val valueAsKeys = MMap.empty[String, String]
        dataset.as[String].foreach(line => {
          val kv = line.split(er.options("keyDelimiter")).map(_.trim)
          val key = kv(0)
          val values = kv(1).split(er.options("valueDelimiter")).map(_.trim)
          values.foreach(v => valueAsKeys(v) = key)
        })
        valueAsKeys.toMap
      case _ =>
        throw new Exception("Unsupported readAs")
    }
  }

  def wordCount(externalResource: ExternalResource,
                m: MMap[String, Long] = MMap.empty[String, Long].withDefaultValue(0),
                p: Option[PipelineModel] = None
               ): MMap[String, Long] = {
    externalResource.readAs match {
      case LINE_BY_LINE =>
        val sourceStream = SourceStream(externalResource.path)
        val regex = externalResource.options("tokenPattern").r
        sourceStream.content.getLines.foreach(line => {
          val words = regex.findAllMatchIn(line).map(_.matched).toList
            words.foreach(w => {
              // Creates a Map of frequency words: word -> frequency based on ExternalResource
              m(w) += 1
            })
        })
        sourceStream.close()
        if (m.isEmpty)
          throw new FileNotFoundException("Word count dictionary for spell checker does not exist or is empty")
        m
      case SPARK_DATASET =>
        import spark.implicits._
        val dataset = spark.read.options(externalResource.options).format(externalResource.options("format"))
                      .load(externalResource.path)
        val transformation = {
          if (p.isDefined) {
            p.get.transform(dataset)
          } else {
            val documentAssembler = new DocumentAssembler()
              .setInputCol("value")
            val tokenizer = new Tokenizer()
              .setInputCols("document")
              .setOutputCol("token")
              .setTargetPattern(externalResource.options("tokenPattern"))
            val finisher = new Finisher()
              .setInputCols("token")
              .setOutputCols("finished")
              .setAnnotationSplitSymbol("--")
            new Pipeline()
              .setStages(Array(documentAssembler, tokenizer, finisher))
              .fit(dataset)
              .transform(dataset)
          }
        }
        val wordCount = MMap.empty[String, Long].withDefaultValue(0)
        transformation
          .select("finished").as[String]
          .foreach(text => text.split("--").foreach(t => {
            wordCount(t) += 1
          }))
        wordCount
      case _ => throw new IllegalArgumentException("format not available for word count")
    }
  }

  def getFilesContentAsArray(externalResource: ExternalResource): Array[String] = {
    externalResource.readAs match {
      case LINE_BY_LINE =>
        val sortedFiles = getSortedFiles(externalResource.path)
        val filesContent = sortedFiles.map(filePath => Source.fromFile(filePath).mkString)
        filesContent.toArray
      case _ =>
        throw new Exception("Unsupported readAs")
    }
  }

  def getSortedFiles(path: String): List[File] = {
    val filesPath = Option(new File(path).listFiles())
    val files = filesPath.getOrElse(throw new FileNotFoundException(s"folder: $path not found"))
    files.toList.sorted
  }

  def validFile(path: String): Boolean = {
    val isValid = Files.exists(Paths.get(path))

    if (isValid) {
      isValid
    } else {
      throw new FileNotFoundException(path)
    }

  }


  /*
  * This section is to help users to convert text files in token\tag style into DataFrame
  * with POS Annotation for training PerceptronApproach
  * */

  case class posTagAnnotation(annotatorType: String, begin: Int, end: Int, result: String, metadata: Map[String, String])

  private def annotateTokensTags: UserDefinedFunction = udf { (tokens: Seq[String], tags: Seq[String], text: String) =>

    lazy val strTokens = tokens.mkString("#")
    lazy val strPosTags = tags.mkString("#")

    require(tokens.length == tags.length, s"Cannot train from DataFrame since there" +
      s" is a row with different amount of tags and tokens:\n$strTokens\n$strPosTags")

    val tokenTagAnnotation: ArrayBuffer[posTagAnnotation] = ArrayBuffer()

    var lastIndex = 0

    for ((e, i) <- tokens.zipWithIndex) {

      val beginOfToken = text.indexOfSlice(e, lastIndex)
      val endOfToken = (beginOfToken + e.length) - 1

      tokenTagAnnotation += posTagAnnotation("pos", beginOfToken, endOfToken, tags(i), Map("word" -> e))

      lastIndex = text.indexOfSlice(e, lastIndex)

    }
    tokenTagAnnotation
  }

  private def extractTokensAndTags: UserDefinedFunction = udf { (tokensTags: Seq[String], delimiter: String, condition: String) =>

    val tempArray: ArrayBuffer[String] = ArrayBuffer()

    for (e <- tokensTags.zipWithIndex) {
      val splittedTokenTag: Array[String] = e._1.split(delimiter.mkString)
      if(splittedTokenTag.length > 1){
        condition.mkString match {
          case "token" =>
            tempArray += splittedTokenTag(0)

          case "tag" =>
            tempArray += splittedTokenTag(1)
        }
      }
    }
    tempArray
  }

  def annotateTokenTagTextFiles(path: String, delimiter: String): DataFrame = {
    import spark.implicits._

    spark.read.text(path).toDF
      .filter(row => !(row.mkString("").isEmpty && row.length>0))
      .withColumn("token_tags", split($"value", " "))
      .select("token_tags")
      .withColumn("tokens", extractTokensAndTags($"token_tags", lit(delimiter), lit("token")))
      .withColumn("tags", extractTokensAndTags($"token_tags", lit(delimiter), lit("tag")))
      .withColumn("text",  concat_ws(" ", $"tokens"))
      .withColumn("pos", annotateTokensTags($"tokens", $"tags", $"text"))
      .select("pos") // this will also generate ("text", "tokens", "tags")
  }

}
