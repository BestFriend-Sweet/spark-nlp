package com.johnsnowlabs.ml.tensorflow

import java.lang.reflect.Modifier

import com.johnsnowlabs.ml.tensorflow.TensorResources.extractFloats
import com.johnsnowlabs.nlp.annotators.ner.Verbose

class TensorflowSpell(
  val tensorflow: TensorflowWrapper,
  val verboseLevel: Verbose.Value
  ) extends Logging with Serializable {

  val testInitOp = "test/init"
  val validWords = "valid_words"
  val fileNameTest = "file_name"
  val inMemoryInput = "in-memory-input"
  val batchesKey = "batches"
  val lossKey = "Add:0"
  val dropoutRate = "dropout_rate"

  /* returns the loss associated with the last word, given previous history  */
  def predict(dataset: Array[Array[Int]], cids: Array[Array[Int]], cwids:Array[Array[Int]]) = this.synchronized {

    println(s"""hash: ${tensorflow.session.hashCode()}""")
    println(s"""threadId: ${Thread.currentThread().getId}""")

    val fields = tensorflow.session.getClass.getDeclaredFields
    for (field <- fields) {
      if (Modifier.isPrivate(field.getModifiers)) {
        field.setAccessible(true)
        System.out.println(field.getName + " : " + field.get(tensorflow.session))
      }
    }

    val packed = dataset.zip(cids).zip(cwids).map {
      case ((_ids, _cids), _cwids) => Array(_ids, _cids, _cwids)
    }

    val tensors = new TensorResources()
    val inputTensor = tensors.createTensor(packed)

    tensorflow.session.runner
      .feed(inMemoryInput, inputTensor)
      .addTarget(testInitOp)
      .run()

    val lossWords = tensorflow.session.runner
      .feed(dropoutRate, tensors.createTensor(1.0f))
      .fetch(lossKey)
      .fetch(validWords)
      .run()

    tensors.clearTensors()

    val result = extractFloats(lossWords.get(0))
    val width = inputTensor.shape()(2)
    result.grouped(width.toInt - 1).map(_.last)

  }
}
