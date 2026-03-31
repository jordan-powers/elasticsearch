/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.flattened;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.elasticsearch.common.io.stream.ByteArrayStreamInput;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldArrayContext;
import org.elasticsearch.index.mapper.MultiValuedBinaryDocValuesField;
import org.elasticsearch.simdvec.ESVectorUtil;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

public final class FlattenedFieldArrayContext extends FieldArrayContext {
    private final String offsetsFieldName;

    static String getOffsetsFieldName(String flattenedFieldName) {
        return flattenedFieldName + OFFSETS_FIELD_NAME_SUFFIX;
    }

    FlattenedFieldArrayContext(String flattenedFieldName) {
        this.offsetsFieldName = getOffsetsFieldName(flattenedFieldName);
    }

    void addToLuceneDocument(DocumentParserContext context) throws IOException {
        var field = new MultiValuedBinaryDocValuesField.IntegratedCount(offsetsFieldName, false);

        for (var entry : offsetsPerField.entrySet()) {
            String fieldName = entry.getKey();

            BytesRef fieldNamePrefix = new BytesRef(fieldName + FlattenedFieldParser.SEPARATOR);
            BytesRef offsetArray = FieldArrayContext.encodeOffsetArray(entry.getValue());

            BytesRefBuilder valueBuilder = new BytesRefBuilder();
            valueBuilder.append(fieldNamePrefix);
            valueBuilder.append(offsetArray);

            field.add(valueBuilder.get());
        }

        context.doc().add(field);
    }

    static Map<String, int[]> parseOffsetField(SortedBinaryDocValues docValues) throws IOException {
        Map<String, int[]> offsets = new TreeMap<>();

        try (ByteArrayStreamInput scratch = new ByteArrayStreamInput()) {
            for (int i = 0; i < docValues.docValueCount(); i++) {
                BytesRef keyedValue = docValues.nextValue();
                int sep = ESVectorUtil.indexOf(keyedValue.bytes, keyedValue.offset, keyedValue.length, FlattenedFieldParser.SEPARATOR_BYTE);
                BytesRef fieldName = new BytesRef(keyedValue.bytes, keyedValue.offset, sep);
                scratch.reset(keyedValue.bytes, keyedValue.offset + sep + 1, keyedValue.length - sep - 1);

                offsets.put(fieldName.utf8ToString(), FieldArrayContext.parseOffsetArray(scratch));
            }
        }

        return offsets;
    }
}
