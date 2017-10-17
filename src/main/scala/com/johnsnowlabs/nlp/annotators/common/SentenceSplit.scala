package com.johnsnowlabs.nlp.annotators.common

import com.johnsnowlabs.nlp.{Annotation, AnnotatorType}

/**
  * structure representing a sentence and its boundaries
  */
case class Sentence(content: String, begin: Int, end: Int)

object Sentence {
  def fromTexts(texts: String*): Seq[Sentence] = {
    var idx = 0
    texts.map{ text =>
      val sentence = Sentence(text, idx, idx + text.length - 1)
      idx += text.length + 1
      sentence
    }
  }
}

/**
  * Helper object to work work with Sentence
  */
object SentenceSplit extends Annotated[Sentence] {
  override def annotatorType: String = AnnotatorType.DOCUMENT

  override def unpack(annotations: Seq[Annotation]): Seq[Sentence] = {
    annotations.filter(_.annotatorType == annotatorType)
      .map(annotation =>
        Sentence(annotation.metadata(annotatorType), annotation.begin, annotation.end)
      )
  }

  override def pack(items: Seq[Sentence]): Seq[Annotation] = {
    items.map(item => Annotation(annotatorType, item.begin, item.end, Map(annotatorType -> item.content)))
  }
}
