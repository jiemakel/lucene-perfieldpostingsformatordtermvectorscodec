package fi.hsci.lucene

import org.apache.lucene.codecs.{Codec, PostingsFormat}

trait PerFieldPostingsFormatOrdTermVectorsCodec extends Codec {
  var perFieldPostingsFormat: Map[String, PostingsFormat]
}
