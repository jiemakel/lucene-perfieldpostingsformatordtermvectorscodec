/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.codecs.compressing;


import java.io.Closeable;
import java.io.IOException;
import java.util.*;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.TermVectorsReader;
import org.apache.lucene.index.*;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.Accountables;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LongsRef;
import org.apache.lucene.util.packed.BlockPackedReaderIterator;
import org.apache.lucene.util.packed.PackedInts;

import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VERSION_OFFHEAP_INDEX;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.FLAGS_BITS;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.META_VERSION_START;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.OFFSETS;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.PACKED_BLOCK_SIZE;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.PAYLOADS;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.POSITIONS;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VECTORS_EXTENSION;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VECTORS_INDEX_CODEC_NAME;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VECTORS_INDEX_EXTENSION;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VECTORS_META_EXTENSION;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VERSION_CURRENT;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VERSION_META;
import static org.apache.lucene.codecs.compressing.CompressingTermVectorsWriter.VERSION_START;

/**
 * {@link TermVectorsReader} for {@link CompressingTermVectorsFormat}.
 * @lucene.experimental
 */
public final class OrdTermVectorsReader extends TermVectorsReader implements Closeable {

  private final FieldInfos fieldInfos;
  final FieldsIndex indexReader;
  final IndexInput vectorsStream;
  private final int version;
  private final int packedIntsVersion;
  private final CompressionMode compressionMode;
  private final Decompressor decompressor;
  private final int chunkSize;
  private final int numDocs;
  private boolean closed;
  private final BlockPackedReaderIterator reader;
  private final long numDirtyChunks; // number of incomplete compressed blocks written
  private final long numDirtyDocs; // cumulative number of missing docs in incomplete chunks
  private final long maxPointer; // end of the data section

  // used by clone
  private OrdTermVectorsReader(OrdTermVectorsReader reader) {
    this.fieldInfos = reader.fieldInfos;
    this.vectorsStream = reader.vectorsStream.clone();
    this.indexReader = reader.indexReader.clone();
    this.packedIntsVersion = reader.packedIntsVersion;
    this.compressionMode = reader.compressionMode;
    this.decompressor = reader.decompressor.clone();
    this.chunkSize = reader.chunkSize;
    this.numDocs = reader.numDocs;
    this.reader = new BlockPackedReaderIterator(vectorsStream, packedIntsVersion, PACKED_BLOCK_SIZE, 0);
    this.version = reader.version;
    this.numDirtyChunks = reader.numDirtyChunks;
    this.numDirtyDocs = reader.numDirtyDocs;
    this.maxPointer = reader.maxPointer;
    this.closed = false;
    this.termDicts = reader.termDicts;
  }

  private final Map<String, Terms> termDicts;

  /** Sole constructor. */
  public OrdTermVectorsReader(Directory d, SegmentInfo si, String segmentSuffix, FieldInfos fn,
                                      IOContext context, String formatName, CompressionMode compressionMode, PostingsFormat postingsFormat) throws IOException {
    this.termDicts = new HashMap<String, Terms>();
    for (FieldInfo f : fn) if (f.hasVectors()) {
      Terms terms = postingsFormat.fieldsProducer(new SegmentReadState(d, si, fn, context)).terms(f.name); // fast on heap FST
      if (terms != null) termDicts.put(f.name, terms);
    }
    this.compressionMode = compressionMode;
    final String segment = si.name;
    boolean success = false;
    fieldInfos = fn;
    numDocs = si.maxDoc();

    ChecksumIndexInput metaIn = null;
    try {
      // Open the data file
      final String vectorsStreamFN = IndexFileNames.segmentFileName(segment, segmentSuffix, VECTORS_EXTENSION);
      vectorsStream = d.openInput(vectorsStreamFN, context);
      version = CodecUtil.checkIndexHeader(vectorsStream, formatName, VERSION_START, VERSION_CURRENT, si.getId(), segmentSuffix);
      assert CodecUtil.indexHeaderLength(formatName, segmentSuffix) == vectorsStream.getFilePointer();

      if (version >= VERSION_OFFHEAP_INDEX) {
        final String metaStreamFN = IndexFileNames.segmentFileName(segment, segmentSuffix, VECTORS_META_EXTENSION);
        metaIn = d.openChecksumInput(metaStreamFN, IOContext.READONCE);
        CodecUtil.checkIndexHeader(metaIn, VECTORS_INDEX_CODEC_NAME + "Meta", META_VERSION_START, version, si.getId(), segmentSuffix);
      }

      if (version >= VERSION_META) {
        packedIntsVersion = metaIn.readVInt();
        chunkSize = metaIn.readVInt();
      } else {
        packedIntsVersion = vectorsStream.readVInt();
        chunkSize = vectorsStream.readVInt();
      }

      // NOTE: data file is too costly to verify checksum against all the bytes on open,
      // but for now we at least verify proper structure of the checksum footer: which looks
      // for FOOTER_MAGIC + algorithmID. This is cheap and can detect some forms of corruption
      // such as file truncation.
      CodecUtil.retrieveChecksum(vectorsStream);

      FieldsIndex indexReader = null;
      long maxPointer = -1;

      if (version < VERSION_OFFHEAP_INDEX) {
        // Load the index into memory
        final String indexName = IndexFileNames.segmentFileName(segment, segmentSuffix, "tvx");
        try (ChecksumIndexInput indexStream = d.openChecksumInput(indexName, context)) {
          Throwable priorE = null;
          try {
            assert formatName.endsWith("Data");
            final String codecNameIdx = formatName.substring(0, formatName.length() - "Data".length()) + "Index";
            final int version2 = CodecUtil.checkIndexHeader(indexStream, codecNameIdx, VERSION_START, VERSION_CURRENT, si.getId(), segmentSuffix);
            if (version != version2) {
              throw new CorruptIndexException("Version mismatch between stored fields index and data: " + version + " != " + version2, indexStream);
            }
            assert CodecUtil.indexHeaderLength(codecNameIdx, segmentSuffix) == indexStream.getFilePointer();
            indexReader = new LegacyFieldsIndexReader(indexStream, si);
            maxPointer = indexStream.readVLong(); // the end of the data section
          } catch (Throwable exception) {
            priorE = exception;
          } finally {
            CodecUtil.checkFooter(indexStream, priorE);
          }
        }
      } else {
        FieldsIndexReader fieldsIndexReader = new FieldsIndexReader(d, si.name, segmentSuffix, VECTORS_INDEX_EXTENSION, VECTORS_INDEX_CODEC_NAME, si.getId(), metaIn);
        indexReader = fieldsIndexReader;
        maxPointer = fieldsIndexReader.getMaxPointer();
      }

      this.indexReader = indexReader;
      this.maxPointer = maxPointer;

      if (version >= VERSION_META) {
        numDirtyChunks = metaIn.readVLong();
        numDirtyDocs = metaIn.readVLong();
      } else {
        // Old versions of this format did not record numDirtyDocs. Since bulk
        // merges are disabled on version increments anyway, we make no effort
        // to get valid values of numDirtyChunks and numDirtyDocs.
        numDirtyChunks = numDirtyDocs = -1;
      }

      decompressor = compressionMode.newDecompressor();
      this.reader = new BlockPackedReaderIterator(vectorsStream, packedIntsVersion, PACKED_BLOCK_SIZE, 0);

      if (metaIn != null) {
        CodecUtil.checkFooter(metaIn, null);
        metaIn.close();
      }

      success = true;
    } catch (Throwable t) {
      if (metaIn != null) {
        CodecUtil.checkFooter(metaIn, t);
        throw new AssertionError("unreachable");
      } else {
        throw t;
      }
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(this, metaIn);
      }
    }
  }

  CompressionMode getCompressionMode() {
    return compressionMode;
  }

  int getChunkSize() {
    return chunkSize;
  }

  int getPackedIntsVersion() {
    return packedIntsVersion;
  }

  int getVersion() {
    return version;
  }

  FieldsIndex getIndexReader() {
    return indexReader;
  }

  IndexInput getVectorsStream() {
    return vectorsStream;
  }

  long getMaxPointer() {
    return maxPointer;
  }

  long getNumDirtyDocs() {
    if (version != VERSION_CURRENT) {
      throw new IllegalStateException("getNumDirtyDocs should only ever get called when the reader is on the current version");
    }
    assert numDirtyDocs >= 0;
    return numDirtyDocs;
  }

  long getNumDirtyChunks() {
    if (version != VERSION_CURRENT) {
      throw new IllegalStateException("getNumDirtyChunks should only ever get called when the reader is on the current version");
    }
    assert numDirtyChunks >= 0;
    return numDirtyChunks;
  }

  int getNumDocs() {
    return numDocs;
  }

  /**
   * @throws AlreadyClosedException if this TermVectorsReader is closed
   */
  private void ensureOpen() throws AlreadyClosedException {
    if (closed) {
      throw new AlreadyClosedException("this FieldsReader is closed");
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      IOUtils.close(indexReader, vectorsStream);
      closed = true;
    }
  }

  @Override
  public TermVectorsReader clone() {
    return new OrdTermVectorsReader(this);
  }

  @Override
  public Fields get(int doc) throws IOException {
    ensureOpen();

    // seek to the right place
    {
      final long startPointer = indexReader.getStartPointer(doc);
      vectorsStream.seek(startPointer);
    }

    // decode
    // - docBase: first doc ID of the chunk
    // - chunkDocs: number of docs of the chunk
    final int docBase = vectorsStream.readVInt();
    final int chunkDocs = vectorsStream.readVInt();
    if (doc < docBase || doc >= docBase + chunkDocs || docBase + chunkDocs > numDocs) {
      throw new CorruptIndexException("docBase=" + docBase + ",chunkDocs=" + chunkDocs + ",doc=" + doc, vectorsStream);
    }

    final int skip; // number of fields to skip
    final int numFields; // number of fields of the document we're looking for
    final int totalFields; // total number of fields of the chunk (sum for all docs)
    if (chunkDocs == 1) {
      skip = 0;
      numFields = totalFields = vectorsStream.readVInt();
    } else {
      reader.reset(vectorsStream, chunkDocs);
      int sum = 0;
      for (int i = docBase; i < doc; ++i) {
        sum += reader.next();
      }
      skip = sum;
      numFields = (int) reader.next();
      sum += numFields;
      for (int i = doc + 1; i < docBase + chunkDocs; ++i) {
        sum += reader.next();
      }
      totalFields = sum;
    }

    if (numFields == 0) {
      // no vectors
      return null;
    }

    // read field numbers that have term vectors
    final int[] fieldNums;
    {
      final int token = vectorsStream.readByte() & 0xFF;
      assert token != 0; // means no term vectors, cannot happen since we checked for numFields == 0
      final int bitsPerFieldNum = token & 0x1F;
      int totalDistinctFields = token >>> 5;
      if (totalDistinctFields == 0x07) {
        totalDistinctFields += vectorsStream.readVInt();
      }
      ++totalDistinctFields;
      final PackedInts.ReaderIterator it = PackedInts.getReaderIteratorNoHeader(vectorsStream, PackedInts.Format.PACKED, packedIntsVersion, totalDistinctFields, bitsPerFieldNum, 1);
      fieldNums = new int[totalDistinctFields];
      for (int i = 0; i < totalDistinctFields; ++i) {
        fieldNums[i] = (int) it.next();
      }
    }

    // read field numbers and flags
    final int[] fieldNumOffs = new int[numFields];
    final PackedInts.Reader flags;
    {
      final int bitsPerOff = PackedInts.bitsRequired(fieldNums.length - 1);
      final PackedInts.Reader allFieldNumOffs = PackedInts.getReaderNoHeader(vectorsStream, PackedInts.Format.PACKED, packedIntsVersion, totalFields, bitsPerOff);
      switch (vectorsStream.readVInt()) {
        case 0:
          final PackedInts.Reader fieldFlags = PackedInts.getReaderNoHeader(vectorsStream, PackedInts.Format.PACKED, packedIntsVersion, fieldNums.length, FLAGS_BITS);
          PackedInts.Mutable f = PackedInts.getMutable(totalFields, FLAGS_BITS, PackedInts.COMPACT);
          for (int i = 0; i < totalFields; ++i) {
            final int fieldNumOff = (int) allFieldNumOffs.get(i);
            assert fieldNumOff >= 0 && fieldNumOff < fieldNums.length;
            final int fgs = (int) fieldFlags.get(fieldNumOff);
            f.set(i, fgs);
          }
          flags = f;
          break;
        case 1:
          flags = PackedInts.getReaderNoHeader(vectorsStream, PackedInts.Format.PACKED, packedIntsVersion, totalFields, FLAGS_BITS);
          break;
        default:
          throw new AssertionError();
      }
      for (int i = 0; i < numFields; ++i) {
        fieldNumOffs[i] = (int) allFieldNumOffs.get(skip + i);
      }
    }

    // number of terms per field for all fields
    final PackedInts.Reader numTerms;
    final int totalTerms;
    {
      final int bitsRequired = vectorsStream.readVInt();
      numTerms = PackedInts.getReaderNoHeader(vectorsStream, PackedInts.Format.PACKED, packedIntsVersion, totalFields, bitsRequired);
      int sum = 0;
      for (int i = 0; i < totalFields; ++i) {
        sum += numTerms.get(i);
      }
      totalTerms = sum;
    }

    // terms
    final long[] terms = new long[totalTerms];
    {
      reader.reset(vectorsStream, totalTerms);
      for (int i = 0, termIndex = 0; i < totalFields; ++i) {
        final int termCount = (int) numTerms.get(i);
        long curTerm = -1;
        for (int j = 0; j < termCount;) {
          final LongsRef next = reader.next(termCount - j);
          j+=next.length;
          for (int k = 0; k < next.length; ++k) {
            curTerm += next.longs[next.offset + k] + 1;
            terms[termIndex++] = curTerm;
          }
        }
      }
    }

    // term freqs
    final int[] termFreqs = new int[totalTerms];
    {
      reader.reset(vectorsStream, totalTerms);
      for (int i = 0; i < totalTerms; ) {
        final LongsRef next = reader.next(totalTerms - i);
        for (int k = 0; k < next.length; ++k) {
          termFreqs[i++] = 1 + (int) next.longs[next.offset + k];
        }
      }
    }

    // total number of positions, offsets and payloads
    int totalPositions = 0, totalOffsets = 0, totalPayloads = 0;
    for (int i = 0, termIndex = 0; i < totalFields; ++i) {
      final int f = (int) flags.get(i);
      final int termCount = (int) numTerms.get(i);
      for (int j = 0; j < termCount; ++j) {
        final int freq = termFreqs[termIndex++];
        if ((f & POSITIONS) != 0) {
          totalPositions += freq;
        }
        if ((f & OFFSETS) != 0) {
          totalOffsets += freq;
        }
        if ((f & PAYLOADS) != 0) {
          totalPayloads += freq;
        }
      }
      assert i != totalFields - 1 || termIndex == totalTerms : termIndex + " " + totalTerms;
    }

    final int[][] positionIndex = positionIndex(skip, numFields, numTerms, termFreqs);
    final int[][] positions, startOffsets, lengths;
    if (totalPositions > 0) {
      positions = readPositions(skip, numFields, flags, numTerms, termFreqs, POSITIONS, totalPositions, positionIndex);
    } else {
      positions = new int[numFields][];
    }

    if (totalOffsets > 0) {
      // average number of chars per term
      final float[] charsPerTerm = new float[fieldNums.length];
      for (int i = 0; i < charsPerTerm.length; ++i) {
        charsPerTerm[i] = Float.intBitsToFloat(vectorsStream.readInt());
      }
      startOffsets = readPositions(skip, numFields, flags, numTerms, termFreqs, OFFSETS, totalOffsets, positionIndex);
      lengths = readPositions(skip, numFields, flags, numTerms, termFreqs, OFFSETS, totalOffsets, positionIndex);

      for (int i = 0; i < numFields; ++i) {
        final int[] fStartOffsets = startOffsets[i];
        final int[] fPositions = positions[i];
        // patch offsets from positions
        if (fStartOffsets != null && fPositions != null) {
          final float fieldCharsPerTerm = charsPerTerm[fieldNumOffs[i]];
          for (int j = 0; j < startOffsets[i].length; ++j) {
            fStartOffsets[j] += (int) (fieldCharsPerTerm * fPositions[j]);
          }
        }
        if (fStartOffsets != null) {
          for (int j = 0, end = (int) numTerms.get(skip + i); j < end; ++j) {
            for (int k = positionIndex[i][j] + 1; k < positionIndex[i][j + 1]; ++k) {
              fStartOffsets[k] += fStartOffsets[k - 1];
            }
          }
        }
      }
    } else {
      startOffsets = lengths = new int[numFields][];
    }
    if (totalPositions > 0) {
      // delta-decode positions
      for (int i = 0; i < numFields; ++i) {
        final int[] fPositions = positions[i];
        final int[] fpositionIndex = positionIndex[i];
        if (fPositions != null) {
          for (int j = 0, end = (int) numTerms.get(skip + i); j < end; ++j) {
            // delta-decode start offsets
            for (int k = fpositionIndex[j] + 1; k < fpositionIndex[j + 1]; ++k) {
              fPositions[k] += fPositions[k - 1];
            }
          }
        }
      }
    }

    // payload lengths
    final int[][] payloadIndex = new int[numFields][];
    int totalPayloadLength = 0;
    int payloadOff = 0;
    int payloadLen = 0;
    if (totalPayloads > 0) {
      reader.reset(vectorsStream, totalPayloads);
      // skip
      int termIndex = 0;
      for (int i = 0; i < skip; ++i) {
        final int f = (int) flags.get(i);
        final int termCount = (int) numTerms.get(i);
        if ((f & PAYLOADS) != 0) {
          for (int j = 0; j < termCount; ++j) {
            final int freq = termFreqs[termIndex + j];
            for (int k = 0; k < freq; ++k) {
              final int l = (int) reader.next();
              payloadOff += l;
            }
          }
        }
        termIndex += termCount;
      }
      totalPayloadLength = payloadOff;
      // read doc payload lengths
      for (int i = 0; i < numFields; ++i) {
        final int f = (int) flags.get(skip + i);
        final int termCount = (int) numTerms.get(skip + i);
        if ((f & PAYLOADS) != 0) {
          final int totalFreq = positionIndex[i][termCount];
          payloadIndex[i] = new int[totalFreq + 1];
          int posIdx = 0;
          payloadIndex[i][posIdx] = payloadLen;
          for (int j = 0; j < termCount; ++j) {
            final int freq = termFreqs[termIndex + j];
            for (int k = 0; k < freq; ++k) {
              final int payloadLength = (int) reader.next();
              payloadLen += payloadLength;
              payloadIndex[i][posIdx+1] = payloadLen;
              ++posIdx;
            }
          }
          assert posIdx == totalFreq;
        }
        termIndex += termCount;
      }
      totalPayloadLength += payloadLen;
      for (int i = skip + numFields; i < totalFields; ++i) {
        final int f = (int) flags.get(i);
        final int termCount = (int) numTerms.get(i);
        if ((f & PAYLOADS) != 0) {
          for (int j = 0; j < termCount; ++j) {
            final int freq = termFreqs[termIndex + j];
            for (int k = 0; k < freq; ++k) {
              totalPayloadLength += reader.next();
            }
          }
        }
        termIndex += termCount;
      }
      assert termIndex == totalTerms : termIndex + " " + totalTerms;
    }

    // decompress data
    final BytesRef payloadBytes = new BytesRef();
    decompressor.decompress(vectorsStream, totalPayloadLength, payloadOff, payloadLen, payloadBytes);
    payloadBytes.length = payloadLen;

    final int[] fieldFlags = new int[numFields];
    for (int i = 0; i < numFields; ++i) {
      fieldFlags[i] = (int) flags.get(skip + i);
    }

    final int[] fieldNumTerms = new int[numFields];
    for (int i = 0; i < numFields; ++i) {
      fieldNumTerms[i] = (int) numTerms.get(skip + i);
    }

    final long[][] fieldTerms = new long[numFields][];
    final int[][] fieldTermFreqs = new int[numFields][];
    {
      int termIdx = 0;
      for (int i = 0; i < skip; ++i) {
        termIdx += numTerms.get(i);
      }
      for (int i = 0; i < numFields; ++i) {
        final int termCount = (int) numTerms.get(skip + i);
        fieldTermFreqs[i] = new int[termCount];
        fieldTerms[i] = new long[termCount];
        for (int j = 0; j < termCount; ++j) {
          fieldTerms[i][j] = terms[termIdx];
          fieldTermFreqs[i][j] = termFreqs[termIdx++];
        }
      }
    }

    return new TVFields(fieldNums, fieldFlags, fieldNumOffs, fieldNumTerms, fieldTerms, fieldTermFreqs,
            positionIndex, positions, startOffsets, lengths,
            payloadBytes, payloadIndex);
  }

  // field -> term index -> position index
  private int[][] positionIndex(int skip, int numFields, PackedInts.Reader numTerms, int[] termFreqs) {
    final int[][] positionIndex = new int[numFields][];
    int termIndex = 0;
    for (int i = 0; i < skip; ++i) {
      final int termCount = (int) numTerms.get(i);
      termIndex += termCount;
    }
    for (int i = 0; i < numFields; ++i) {
      final int termCount = (int) numTerms.get(skip + i);
      positionIndex[i] = new int[termCount + 1];
      for (int j = 0; j < termCount; ++j) {
        final int freq = termFreqs[termIndex+j];
        positionIndex[i][j + 1] = positionIndex[i][j] + freq;
      }
      termIndex += termCount;
    }
    return positionIndex;
  }

  private int[][] readPositions(int skip, int numFields, PackedInts.Reader flags, PackedInts.Reader numTerms, int[] termFreqs, int flag, final int totalPositions, int[][] positionIndex) throws IOException {
    final int[][] positions = new int[numFields][];
    reader.reset(vectorsStream, totalPositions);
    // skip
    int toSkip = 0;
    int termIndex = 0;
    for (int i = 0; i < skip; ++i) {
      final int f = (int) flags.get(i);
      final int termCount = (int) numTerms.get(i);
      if ((f & flag) != 0) {
        for (int j = 0; j < termCount; ++j) {
          final int freq = termFreqs[termIndex+j];
          toSkip += freq;
        }
      }
      termIndex += termCount;
    }
    reader.skip(toSkip);
    // read doc positions
    for (int i = 0; i < numFields; ++i) {
      final int f = (int) flags.get(skip + i);
      final int termCount = (int) numTerms.get(skip + i);
      if ((f & flag) != 0) {
        final int totalFreq = positionIndex[i][termCount];
        final int[] fieldPositions = new int[totalFreq];
        positions[i] = fieldPositions;
        for (int j = 0; j < totalFreq; ) {
          final LongsRef nextPositions = reader.next(totalFreq - j);
          for (int k = 0; k < nextPositions.length; ++k) {
            fieldPositions[j++] = (int) nextPositions.longs[nextPositions.offset + k];
          }
        }
      }
      termIndex += termCount;
    }
    reader.skip(totalPositions - reader.ord());
    return positions;
  }

  private class TVFields extends Fields {

    private final int[] fieldNums, fieldFlags, fieldNumOffs, numTerms;
    private final int[][] termFreqs, positionIndex, positions, startOffsets, lengths, payloadIndex;
    private final long[][] terms;
    private final BytesRef payloadBytes;

    public TVFields(int[] fieldNums, int[] fieldFlags, int[] fieldNumOffs, int[] numTerms, long[][] terms, int[][] termFreqs,
                    int[][] positionIndex, int[][] positions, int[][] startOffsets, int[][] lengths,
                    BytesRef payloadBytes, int[][] payloadIndex) {
      this.fieldNums = fieldNums;
      this.fieldFlags = fieldFlags;
      this.fieldNumOffs = fieldNumOffs;
      this.numTerms = numTerms;
      this.terms = terms;
      this.termFreqs = termFreqs;
      this.positionIndex = positionIndex;
      this.positions = positions;
      this.startOffsets = startOffsets;
      this.lengths = lengths;
      this.payloadBytes = payloadBytes;
      this.payloadIndex = payloadIndex;
    }

    @Override
    public Iterator<String> iterator() {
      return new Iterator<String>() {
        int i = 0;
        @Override
        public boolean hasNext() {
          return i < fieldNumOffs.length;
        }
        @Override
        public String next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          final int fieldNum = fieldNums[fieldNumOffs[i++]];
          return fieldInfos.fieldInfo(fieldNum).name;
        }
        @Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public Terms terms(String field) throws IOException {
      final FieldInfo fieldInfo = fieldInfos.fieldInfo(field);
      if (fieldInfo == null) {
        return null;
      }
      int idx = -1;
      for (int i = 0; i < fieldNumOffs.length; ++i) {
        if (fieldNums[fieldNumOffs[i]] == fieldInfo.number) {
          idx = i;
          break;
        }
      }

      if (idx == -1 || numTerms[idx] == 0) {
        // no term
        return null;
      }
      return new TVTerms(numTerms[idx], fieldFlags[idx],
              terms[idx], termFreqs[idx],
              positionIndex[idx], positions[idx], startOffsets[idx], lengths[idx],
              payloadIndex[idx], payloadBytes, termDicts.get(field));
    }

    @Override
    public int size() {
      return fieldNumOffs.length;
    }

  }

  private static class TVTerms extends Terms {

    private final int numTerms, flags;
    private final long totalTermFreq;
    private final int[] termFreqs, positionIndex, positions, startOffsets, lengths, payloadIndex;
    private final BytesRef payloadBytes;

    private final long[] terms;
    private final Terms termDict;

    TVTerms(int numTerms, int flags, long[] terms, int[] termFreqs,
            int[] positionIndex, int[] positions, int[] startOffsets, int[] lengths,
            int[] payloadIndex, BytesRef payloadBytes, Terms termDict) {
      this.numTerms = numTerms;
      this.flags = flags;
      this.termFreqs = termFreqs;
      this.positionIndex = positionIndex;
      this.positions = positions;
      this.startOffsets = startOffsets;
      this.lengths = lengths;
      this.payloadIndex = payloadIndex;
      this.payloadBytes = payloadBytes;

      this.terms = terms;
      this.termDict = termDict;

      long ttf = 0;
      for (int tf : termFreqs) {
        ttf += tf;
      }
      this.totalTermFreq = ttf;
    }

    @Override
    public TVTermsEnum iterator() throws IOException {
      TVTermsEnum termsEnum = new TVTermsEnum();
      termsEnum.reset(numTerms, flags, terms, termFreqs, positionIndex, positions, startOffsets, lengths,
              payloadIndex, payloadBytes, termDict);
      return termsEnum;
    }

    @Override
    public long size() throws IOException {
      return numTerms;
    }

    @Override
    public long getSumTotalTermFreq() throws IOException {
      return totalTermFreq;
    }

    @Override
    public long getSumDocFreq() throws IOException {
      return numTerms;
    }

    @Override
    public int getDocCount() throws IOException {
      return 1;
    }

    @Override
    public boolean hasFreqs() {
      return true;
    }

    @Override
    public boolean hasOffsets() {
      return (flags & OFFSETS) != 0;
    }

    @Override
    public boolean hasPositions() {
      return (flags & POSITIONS) != 0;
    }

    @Override
    public boolean hasPayloads() {
      return (flags & PAYLOADS) != 0;
    }

  }

  public static class TVTermsEnum extends BaseTermsEnum {

    private int numTerms, ord;
    private int[] termFreqs, positionIndex, positions, startOffsets, lengths, payloadIndex;
    private BytesRef payloads;

    private long[] terms;
    private Terms termDict;
    private TermsEnum curTermDict;

    void reset(int numTerms, int flags, long[] terms, int[] termFreqs, int[] positionIndex, int[] positions, int[] startOffsets, int[] lengths,
               int[] payloadIndex, BytesRef payloads, Terms termDict) {
      this.numTerms = numTerms;
      this.termFreqs = termFreqs;
      this.positionIndex = positionIndex;
      this.positions = positions;
      this.startOffsets = startOffsets;
      this.lengths = lengths;
      this.payloadIndex = payloadIndex;
      this.payloads = payloads;

      this.terms = terms;
      this.termDict = termDict;
      this.curTermDict = null;

      reset();
    }

    void reset() {
      ord = -1;
    }

    @Override
    public BytesRef next() throws IOException {
      if (ord == numTerms - 1) {
        return null;
      } else {
        assert ord < numTerms;
        ++ord;
      }

      if (this.curTermDict == null) this.curTermDict = termDict.iterator();
      curTermDict.seekExact(terms[ord]);
      return curTermDict.term();
    }

    public long nextOrd() throws IOException {
      if (ord == numTerms - 1) {
        return -1;
      } else {
        assert ord < numTerms;
        ++ord;
      }
      return terms[ord];
    }

    @Override
    public SeekStatus seekCeil(BytesRef text)
            throws IOException {
      if (ord < numTerms && ord >= 0) {
        final int cmp = term().compareTo(text);
        if (cmp == 0) {
          return SeekStatus.FOUND;
        } else if (cmp > 0) {
          reset();
        }
      }
      // linear scan
      while (true) {
        final BytesRef term = next();
        if (term == null) {
          return SeekStatus.END;
        }
        final int cmp = term.compareTo(text);
        if (cmp > 0) {
          return SeekStatus.NOT_FOUND;
        } else if (cmp == 0) {
          return SeekStatus.FOUND;
        }
      }
    }

    @Override
    public void seekExact(long ord) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public BytesRef term() throws IOException {
      return curTermDict.term();
    }

    @Override
    public long ord() throws IOException {
      return terms[ord];
    }

    @Override
    public int docFreq() throws IOException {
      return 1;
    }

    @Override
    public long totalTermFreq() throws IOException {
      return termFreqs[ord];
    }

    @Override
    public final PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
      final TVPostingsEnum docsEnum;
      if (reuse != null && reuse instanceof TVPostingsEnum) {
        docsEnum = (TVPostingsEnum) reuse;
      } else {
        docsEnum = new TVPostingsEnum();
      }

      docsEnum.reset(termFreqs[ord], positionIndex[ord], positions, startOffsets, lengths, payloads, payloadIndex);
      return docsEnum;
    }

    @Override
    public ImpactsEnum impacts(int flags) throws IOException {
      final PostingsEnum delegate = postings(null, PostingsEnum.FREQS);
      return new SlowImpactsEnum(delegate);
    }

  }

  private static class TVPostingsEnum extends PostingsEnum {

    private int doc = -1;
    private int termFreq;
    private int positionIndex;
    private int[] positions;
    private int[] startOffsets;
    private int[] lengths;
    private final BytesRef payload;
    private int[] payloadIndex;
    private int basePayloadOffset;
    private int i;

    TVPostingsEnum() {
      payload = new BytesRef();
    }

    public void reset(int freq, int positionIndex, int[] positions,
                      int[] startOffsets, int[] lengths, BytesRef payloads,
                      int[] payloadIndex) {
      this.termFreq = freq;
      this.positionIndex = positionIndex;
      this.positions = positions;
      this.startOffsets = startOffsets;
      this.lengths = lengths;
      this.basePayloadOffset = payloads.offset;
      this.payload.bytes = payloads.bytes;
      payload.offset = payload.length = 0;
      this.payloadIndex = payloadIndex;

      doc = i = -1;
    }

    private void checkDoc() {
      if (doc == NO_MORE_DOCS) {
        throw new IllegalStateException("DocsEnum exhausted");
      } else if (doc == -1) {
        throw new IllegalStateException("DocsEnum not started");
      }
    }

    private void checkPosition() {
      checkDoc();
      if (i < 0) {
        throw new IllegalStateException("Position enum not started");
      } else if (i >= termFreq) {
        throw new IllegalStateException("Read past last position");
      }
    }

    @Override
    public int nextPosition() throws IOException {
      if (doc != 0) {
        throw new IllegalStateException();
      } else if (i >= termFreq - 1) {
        throw new IllegalStateException("Read past last position");
      }

      ++i;

      if (payloadIndex != null) {
        payload.offset = basePayloadOffset + payloadIndex[positionIndex + i];
        payload.length = payloadIndex[positionIndex + i + 1] - payloadIndex[positionIndex + i];
      }

      if (positions == null) {
        return -1;
      } else {
        return positions[positionIndex + i];
      }
    }

    @Override
    public int startOffset() throws IOException {
      checkPosition();
      if (startOffsets == null) {
        return -1;
      } else {
        return startOffsets[positionIndex + i];
      }
    }

    @Override
    public int endOffset() throws IOException {
      checkPosition();
      if (startOffsets == null) {
        return -1;
      } else {
        return startOffsets[positionIndex + i] + lengths[positionIndex + i];
      }
    }

    @Override
    public BytesRef getPayload() throws IOException {
      checkPosition();
      if (payloadIndex == null || payload.length == 0) {
        return null;
      } else {
        return payload;
      }
    }

    @Override
    public int freq() throws IOException {
      checkDoc();
      return termFreq;
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() throws IOException {
      if (doc == -1) {
        return (doc = 0);
      } else {
        return (doc = NO_MORE_DOCS);
      }
    }

    @Override
    public int advance(int target) throws IOException {
      return slowAdvance(target);
    }

    @Override
    public long cost() {
      return 1;
    }
  }

  private static int sum(int[] arr) {
    int sum = 0;
    for (int el : arr) {
      sum += el;
    }
    return sum;
  }

  @Override
  public long ramBytesUsed() {
    return indexReader.ramBytesUsed();
  }

  @Override
  public Collection<Accountable> getChildResources() {
    return Collections.singleton(Accountables.namedAccountable("term vector index", indexReader));
  }

  @Override
  public void checkIntegrity() throws IOException {
    indexReader.checkIntegrity();
    CodecUtil.checksumEntireFile(vectorsStream);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(mode=" + compressionMode + ",chunksize=" + chunkSize + ")";
  }
}