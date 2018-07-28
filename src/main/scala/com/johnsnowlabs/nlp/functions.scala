package com.johnsnowlabs.nlp

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.{col, udf}

import scala.reflect.runtime.universe._

object functions {



  def filterByAnnotations(dataset: DataFrame, column: String, function: Seq[Annotation] => Boolean): DataFrame = {
    val meta = dataset.schema(column).metadata
    val func = udf {
      annotatorProperties: Seq[Row] =>
        function(annotatorProperties.map(Annotation(_)))
    }
    dataset.filter(func(col(column)).as(column, meta))
  }

  def mapAnnotations[T: TypeTag](dataset: DataFrame, column: String, outputCol: String, function: Seq[Annotation] => T): DataFrame = {
    val meta = dataset.schema(column).metadata
    val func = udf {
      annotatorProperties: Seq[Row] =>
        function(annotatorProperties.map(Annotation(_)))
    }
    dataset.withColumn(outputCol, func(col(column)).as(outputCol, meta))
  }


}
