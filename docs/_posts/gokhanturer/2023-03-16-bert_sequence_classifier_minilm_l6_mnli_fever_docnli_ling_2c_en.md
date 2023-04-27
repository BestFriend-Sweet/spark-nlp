---
layout: model
title: English BertForSequenceClassification Mini Cased model (from MoritzLaurer)
author: John Snow Labs
name: bert_sequence_classifier_minilm_l6_mnli_fever_docnli_ling_2c
date: 2023-03-16
tags: [en, open_source, bert, sequence_classification, ner, tensorflow]
task: Named Entity Recognition
language: en
edition: Spark NLP 4.3.1
spark_version: 3.0
supported: true
engine: tensorflow
annotator: BertForSequenceClassification
article_header:
  type: cover
use_language_switcher: "Python-Scala-Java"
---

## Description

Pretrained BertForSequenceClassification model, adapted from Hugging Face and curated to provide scalability and production-readiness using Spark NLP. `MiniLM-L6-mnli-fever-docnli-ling-2c` is a English model originally trained by `MoritzLaurer`.

## Predicted Entities

`not_entailment`, `entailment`

{:.btn-box}
<button class="button button-orange" disabled>Live Demo</button>
<button class="button button-orange" disabled>Open in Colab</button>
[Download](https://s3.amazonaws.com/auxdata.johnsnowlabs.com/public/models/bert_sequence_classifier_minilm_l6_mnli_fever_docnli_ling_2c_en_4.3.1_3.0_1678984522800.zip){:.button.button-orange}
[Copy S3 URI](s3://auxdata.johnsnowlabs.com/public/models/bert_sequence_classifier_minilm_l6_mnli_fever_docnli_ling_2c_en_4.3.1_3.0_1678984522800.zip){:.button.button-orange.button-orange-trans.button-icon.button-copy-s3}

## How to use



<div class="tabs-box" markdown="1">
{% include programmingLanguageSelectScalaPythonNLU.html %}
```python
documentAssembler = DocumentAssembler() \
    .setInputCols(["text"]) \
    .setOutputCols("document")

tokenizer = Tokenizer() \
    .setInputCols("document") \
    .setOutputCol("token")

sequenceClassifier = BertForSequenceClassification.pretrained("bert_sequence_classifier_minilm_l6_mnli_fever_docnli_ling_2c","en") \
    .setInputCols(["document", "token"]) \
    .setOutputCol("class")

pipeline = Pipeline(stages=[documentAssembler, tokenizer, sequenceClassifier])

data = spark.createDataFrame([["PUT YOUR STRING HERE"]]).toDF("text")

result = pipeline.fit(data).transform(data)
```
```scala
val documentAssembler = new DocumentAssembler() 
    .setInputCols(Array("text")) 
    .setOutputCols(Array("document"))
      
val tokenizer = new Tokenizer()
    .setInputCols("document")
    .setOutputCol("token")
 
val sequenceClassifier = BertForSequenceClassification.pretrained("bert_sequence_classifier_minilm_l6_mnli_fever_docnli_ling_2c","en") 
    .setInputCols(Array("document", "token"))
    .setOutputCol("ner")
   
val pipeline = new Pipeline().setStages(Array(documentAssembler, tokenizer, sequenceClassifier))

val data = Seq("PUT YOUR STRING HERE").toDS.toDF("text")

val result = pipeline.fit(data).transform(data)
```
</div>

{:.model-param}
## Model Information

{:.table-model}
|---|---|
|Model Name:|bert_sequence_classifier_minilm_l6_mnli_fever_docnli_ling_2c|
|Compatibility:|Spark NLP 4.3.1+|
|License:|Open Source|
|Edition:|Official|
|Input Labels:|[document, token]|
|Output Labels:|[ner]|
|Language:|en|
|Size:|85.0 MB|
|Case sensitive:|true|
|Max sentence length:|128|

## References

- https://huggingface.co/MoritzLaurer/MiniLM-L6-mnli-fever-docnli-ling-2c
- https://github.com/easonnie/combine-FEVER-NSMN/blob/master/other_resources/nli_fever.md
- https://arxiv.org/abs/2104.07179
- https://arxiv.org/pdf/2106.09449.pdf
- https://github.com/facebookresearch/anli
- https://github.com/easonnie/combine-FEVER-NSMN/blob/master/other_resources/nli_fever.md
- https://arxiv.org/abs/2104.07179
- https://arxiv.org/pdf/2106.09449.pdf
- https://github.com/facebookresearch/anli
- https://www.linkedin.com/in/moritz-laurer/