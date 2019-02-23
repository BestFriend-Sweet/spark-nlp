package com.johnsnowlabs.nlp.annotators.sbd.deep

import com.johnsnowlabs.nlp.AnnotatorType.{CHUNK, DOCUMENT, TOKEN}
import com.johnsnowlabs.nlp.annotator.SentenceDetector
import com.johnsnowlabs.nlp.annotators.common.SentenceSplit
import com.johnsnowlabs.nlp.annotators.sbd.SentenceDetectorParams
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel}
import org.apache.spark.ml.param.{BooleanParam, StringArrayParam}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}

class DeepSentenceDetector(override val uid: String) extends AnnotatorModel[DeepSentenceDetector] with SentenceDetectorParams {

  def this() = this(Identifiable.randomUID("DEEP SENTENCE DETECTOR"))

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val requiredAnnotatorTypes: Array[AnnotatorType] = Array(DOCUMENT, TOKEN, CHUNK)
  override val annotatorType: AnnotatorType = DOCUMENT

  val includesPragmaticSegmenter = new BooleanParam(this, "includesPragmaticSegmenter",
    "Whether to include rule-based sentence detector as first filter")

  val endPunctuation = new StringArrayParam(this, "endPunctuation",
    "An array of symbols that deep sentence detector will consider as an end of sentence punctuation")

  def setIncludePragmaticSegmenter(value: Boolean): this.type = set(includesPragmaticSegmenter, value)

  def setEndPunctuation(value: Array[String]): this.type = set(endPunctuation, value)

  setDefault(
    includesPragmaticSegmenter -> false,
    endPunctuation -> Array(".", "!", "?")
  )

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {

    if ($(includesPragmaticSegmenter)) {

      val document = getDocument(annotations)
      val pragmaticSegmentedSentences = new SentenceDetector()
        .setUseAbbreviations($(useAbbrevations))
        .setUseCustomBoundsOnly($(useCustomBoundsOnly))
        .setMaxLength($(maxLength))
        .setCustomBounds($(customBounds))
        .annotate(document)
      val unpunctuatedSentences = getUnpunctuatedSentences(pragmaticSegmentedSentences)

      if (unpunctuatedSentences.isEmpty){
        pragmaticSegmentedSentences
      } else {
        getDeepSegmentedSentences(annotations, unpunctuatedSentences, pragmaticSegmentedSentences)
      }

    } else {
      deepSentenceDetector(annotations)
    }

  }

  def getDocument(annotations: Seq[Annotation]): Seq[Annotation] = {
    annotations.filter(annotation => annotation.annotatorType == DOCUMENT)
  }

  def getDeepSegmentedSentences(annotations: Seq[Annotation], unpunctuatedSentences: Seq[Annotation],
                                pragmaticSegmentedSentences: Seq[Annotation] ): Seq[Annotation] = {

    val validNerEntities = retrieveValidNerEntities(annotations, unpunctuatedSentences)
    if (validNerEntities.nonEmpty && unpunctuatedSentences.length == validNerEntities.length ){
      val deepSegmentedSentences = deepSentenceDetector(unpunctuatedSentences, validNerEntities)
      val mergedSegmentedSentences = mergeSentenceDetectors(pragmaticSegmentedSentences, deepSegmentedSentences)
      mergedSegmentedSentences
    } else {
      //When NER does not find entities, it will use just pragmatic sentence
      pragmaticSegmentedSentences
    }
  }

  def deepSentenceDetector(annotations: Seq[Annotation]): Seq[Annotation] = {
    val nerEntities = getNerEntities(annotations)
    val sentence = retrieveSentence(annotations)
    segmentSentence(nerEntities, sentence)
  }

  def getNerEntities(annotations: Seq[Annotation]): Seq[Annotation] = {
    annotations.filter(annotation => annotation.annotatorType == CHUNK)
  }

  def retrieveSentence(annotations: Seq[Annotation]): String = {
    val sentences = SentenceSplit.unpack(annotations)
    val sentence = sentences.map(sentence => sentence.content)
    sentence.mkString(" ")
  }

  def segmentSentence(nerEntities: Seq[Annotation], sentence: String): Seq[Annotation] = {
    nerEntities.zipWithIndex.flatMap{ case (nerEntity, index) =>
      val segmentedSentence = {
        if (index != nerEntities.length-1) {
          val beginIndex = nerEntity.begin
          val endIndex = nerEntities(index+1).begin-1
          sentence.substring(beginIndex, endIndex)
        } else {
          val beginIndex = nerEntity.begin
          sentence.substring(beginIndex)
        }
      }
      segmentedSentence.grouped($(maxLength)).map(limitedSentence => {
        Annotation(
          annotatorType,
          0,
          limitedSentence.length-1,
          limitedSentence,
          Map("sentence" -> "")
        )
      })
    }
  }

  def deepSentenceDetector(unpunctuatedSentences: Seq[Annotation], nerEntities: Seq[Seq[Annotation]]):
  Seq[Annotation] = {
    unpunctuatedSentences.zipWithIndex.flatMap{ case (unpunctuatedSentence, index) =>
      segmentSentence(nerEntities(index), unpunctuatedSentence.result)
    }
  }

  def getUnpunctuatedSentences(pragmaticSegmentedSentences: Seq[Annotation]): Seq[Annotation] = {
    pragmaticSegmentedSentences.filterNot(annotatedSentence =>
      sentenceHasPunctuation(annotatedSentence.result))
  }

  def sentenceHasPunctuation(sentence: String): Boolean = {
    var hasPunctuation = false

    $(endPunctuation).foreach { punctuation =>
      if (sentence.contains(punctuation)) {
        hasPunctuation = true
      }
      if (punctuation == "") {
        hasPunctuation = false
      }
    }

    hasPunctuation
  }

  def retrieveValidNerEntities(annotations: Seq[Annotation], unpunctuatedSentences: Seq[Annotation]):
  Seq[Seq[Annotation]] = {

    def updateIndex(validNerEntities: Seq[Seq[Annotation]]): Seq[Seq[Annotation]] = {
      validNerEntities.map{ validNerEntity =>
          val offset = validNerEntity.head.begin
          validNerEntity.map(nerEntity =>
            Annotation(nerEntity.annotatorType, nerEntity.begin-offset, nerEntity.end-offset,
              nerEntity.result, nerEntity.metadata)
          )
      }
    }

    val nerEntities = getNerEntities(annotations)

    if (nerEntities.nonEmpty) {
      val validNerEntities = unpunctuatedSentences.map{ unpunctuatedSentence =>
        nerEntities.filter{entity =>
          val beginSentence = unpunctuatedSentence.begin
          val endSentence = unpunctuatedSentence.end
          entity.begin >= beginSentence && entity.end <= endSentence
        }
      }.filterNot(_.isEmpty)

      updateIndex(validNerEntities)
    } else {
      Seq[Seq[Annotation]]()
    }
  }

  def mergeSentenceDetectors(pragmaticSegmentedSentences: Seq[Annotation], deepSegmentedSentences: Seq[Annotation]):
  Seq[Annotation] = {

    val mergeSentences = pragmaticSegmentedSentences.foldRight(Seq.empty[Annotation])
      { (pragmaticSegmentedSentence, annotations) =>
        if (sentenceHasPunctuation(pragmaticSegmentedSentence.result)) {
          annotations ++ Seq(pragmaticSegmentedSentence)
        } else {
          annotations
        }
      } ++ deepSegmentedSentences

    mergeSentences.zipWithIndex.map{ case (mergeSentence, sentenceNumber) =>
      Annotation(
        mergeSentence.annotatorType,
        mergeSentence.begin,
        mergeSentence.end,
        mergeSentence.result,
        Map("sentence"->sentenceNumber.toString)
      )
    }
  }

}

object DeepSentenceDetector extends DefaultParamsReadable[DeepSentenceDetector]