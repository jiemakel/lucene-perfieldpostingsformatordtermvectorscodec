package fi.hsci.lucene

import org.apache.lucene.codecs.compressing.{CompressionMode, OrdTermVectorsReader, OrdTermVectorsWriter}
import org.apache.lucene.codecs.lucene84.Lucene84PostingsFormat
import org.apache.lucene.codecs.lucene87.Lucene87Codec
import org.apache.lucene.codecs.perfield.PerFieldPostingsFormat
import org.apache.lucene.codecs.{FilterCodec, PostingsFormat, TermVectorsFormat}
import org.apache.lucene.util.BytesRef

import java.util.function.Predicate

class Lucene87PerFieldPostingsFormatOrdTermVectorsCodec extends FilterCodec("Lucene87PerFieldPostingsFormatOrdTermVectorsCodec",new Lucene87Codec(Lucene87Codec.Mode.BEST_COMPRESSION)) with PerFieldPostingsFormatOrdTermVectorsCodec {

  var perFieldPostingsFormat: Map[String, PostingsFormat] = Map.empty
  var termVectorFilter: Predicate[BytesRef] = _
  
  val lucene84PostingsFormat = new Lucene84PostingsFormat()

  private val pfPostingsFormat = new PerFieldPostingsFormat() {
    override def getPostingsFormatForField(field: String): PostingsFormat = perFieldPostingsFormat.getOrElse(field,lucene84PostingsFormat)
  }

  override def postingsFormat() = pfPostingsFormat

  private val ordTermVectorsFormat = new TermVectorsFormat {
     override def vectorsReader(directory: org.apache.lucene.store.Directory,segmentInfo: org.apache.lucene.index.SegmentInfo,fieldInfos: org.apache.lucene.index.FieldInfos,context: org.apache.lucene.store.IOContext): org.apache.lucene.codecs.TermVectorsReader =
       new OrdTermVectorsReader(directory, segmentInfo, "",fieldInfos, context, "OrdTermVectors", CompressionMode.FAST_DECOMPRESSION, pfPostingsFormat)
     override def vectorsWriter(directory: org.apache.lucene.store.Directory,segmentInfo: org.apache.lucene.index.SegmentInfo,context: org.apache.lucene.store.IOContext): org.apache.lucene.codecs.TermVectorsWriter =
       new OrdTermVectorsWriter(directory, segmentInfo, "", context, "OrdTermVectors", CompressionMode.FAST_DECOMPRESSION, 1 << 12, 10, pfPostingsFormat, termVectorFilter)
  }

  override def termVectorsFormat() = ordTermVectorsFormat
  
}