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
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldArrayContext;
import org.elasticsearch.index.mapper.MultiValuedBinaryDocValuesField;
import org.elasticsearch.simdvec.ESVectorUtil;

import java.io.IOException;

public final class FlattenedFieldArrayContext extends FieldArrayContext {
    private final String offsetsFieldName;

    static String getOffsetsFieldName(String flattenedFieldName) {
        return flattenedFieldName + OFFSETS_FIELD_NAME_SUFFIX;
    }

    FlattenedFieldArrayContext(String flattenedFieldName) {
        this.offsetsFieldName = getOffsetsFieldName(flattenedFieldName);
    }

    void addToLuceneDocument(DocumentParserContext context) throws IOException {
        var field = (MultiValuedBinaryDocValuesField.IntegratedCount) context.doc().getField(offsetsFieldName);
        if (field == null) {
            field = new MultiValuedBinaryDocValuesField.IntegratedCount(offsetsFieldName, false);
            context.doc().addWithKey(offsetsFieldName, field);
        }

        for (var entry : offsetsPerField.entrySet()) {
            String fieldName = entry.getKey();
            var offsets = entry.getValue();

            if (offsets.currentOffset() <= 1 && offsets.hasNulls() == false) {
                // only 1 non-null value, no need to record offsets
                continue;
            }

            BytesRef fieldNamePrefix = new BytesRef(fieldName + FlattenedFieldParser.SEPARATOR);
            BytesRef offsetArray = FieldArrayContext.encodeOffsetArray(entry.getValue());

            BytesRefBuilder valueBuilder = new BytesRefBuilder();
            valueBuilder.append(fieldNamePrefix);
            valueBuilder.append(offsetArray);

            field.add(valueBuilder.get());
        }
    }

    public record KeyedOffsetField(String fieldName, int[] offsets) {}

    static KeyedOffsetField parseOffsetField(final BytesRef bytes) throws IOException {
        if (bytes == null) {
            return null;
        }

        try (ByteArrayStreamInput scratch = new ByteArrayStreamInput()) {
            int sep = ESVectorUtil.indexOf(bytes.bytes, bytes.offset, bytes.length, FlattenedFieldParser.SEPARATOR_BYTE);
            BytesRef fieldName = new BytesRef(bytes.bytes, bytes.offset, sep);
            scratch.reset(bytes.bytes, bytes.offset + sep + 1, bytes.length - sep - 1);

            return new KeyedOffsetField(fieldName.utf8ToString(), FieldArrayContext.parseOffsetArray(scratch));
        }
    }
}
