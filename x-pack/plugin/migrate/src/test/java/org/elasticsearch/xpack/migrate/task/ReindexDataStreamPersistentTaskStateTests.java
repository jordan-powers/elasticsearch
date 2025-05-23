/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.migrate.task;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractXContentSerializingTestCase;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;

public class ReindexDataStreamPersistentTaskStateTests extends AbstractXContentSerializingTestCase<ReindexDataStreamPersistentTaskState> {
    @Override
    protected ReindexDataStreamPersistentTaskState doParseInstance(XContentParser parser) throws IOException {
        return ReindexDataStreamPersistentTaskState.fromXContent(parser);
    }

    @Override
    protected Writeable.Reader<ReindexDataStreamPersistentTaskState> instanceReader() {
        return ReindexDataStreamPersistentTaskState::new;
    }

    @Override
    protected ReindexDataStreamPersistentTaskState createTestInstance() {
        return new ReindexDataStreamPersistentTaskState(
            randomBoolean() ? null : randomNonNegativeInt(),
            randomBoolean() ? null : randomNonNegativeInt(),
            randomBoolean() ? null : randomNonNegativeLong()
        );
    }

    @Override
    protected ReindexDataStreamPersistentTaskState mutateInstance(ReindexDataStreamPersistentTaskState instance) throws IOException {
        return switch (randomInt(2)) {
            case 0 -> new ReindexDataStreamPersistentTaskState(
                instance.totalIndices() == null ? randomNonNegativeInt() : instance.totalIndices() + 1,
                instance.totalIndicesToBeUpgraded(),
                instance.completionTime()
            );
            case 1 -> new ReindexDataStreamPersistentTaskState(
                instance.totalIndices(),
                instance.totalIndicesToBeUpgraded() == null ? randomNonNegativeInt() : instance.totalIndicesToBeUpgraded() + 1,
                instance.completionTime()
            );
            case 2 -> new ReindexDataStreamPersistentTaskState(
                instance.totalIndices(),
                instance.totalIndicesToBeUpgraded(),
                instance.completionTime() == null ? randomNonNegativeLong() : instance.completionTime() + 1
            );
            default -> throw new IllegalArgumentException("Should never get here");
        };
    }
}
