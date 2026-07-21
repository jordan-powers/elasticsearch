/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.logsdb;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.datageneration.DataGeneratorSpecification;
import org.elasticsearch.datageneration.DocumentGenerator;
import org.elasticsearch.datageneration.FieldType;
import org.elasticsearch.datageneration.Mapping;
import org.elasticsearch.datageneration.MappingGenerator;
import org.elasticsearch.datageneration.Template;
import org.elasticsearch.datageneration.TemplateGenerator;
import org.elasticsearch.datageneration.datasource.ASCIIStringsHandler;
import org.elasticsearch.datageneration.datasource.DataSourceHandler;
import org.elasticsearch.datageneration.datasource.DataSourceRequest;
import org.elasticsearch.datageneration.datasource.DataSourceResponse;
import org.elasticsearch.datageneration.datasource.DefaultMappingParametersHandler;
import org.elasticsearch.datageneration.datasource.DefaultObjectGenerationHandler;
import org.elasticsearch.datageneration.datasource.MultifieldAddonHandler;
import org.elasticsearch.datageneration.fields.PredefinedField;
import org.elasticsearch.datageneration.fields.leaf.DateFieldDataGenerator;
import org.elasticsearch.datageneration.fields.leaf.FlattenedFieldDataGenerator;
import org.elasticsearch.datageneration.matchers.MatchResult;
import org.elasticsearch.datageneration.matchers.Matcher;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperFeatures;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class RandomizedRollingUpgradeIT extends AbstractLogsdbRollingUpgradeTestCase {

    private record TestIndexConfig(
        String indexName,
        Template template,
        Settings.Builder settings,
        Mapping mapping,
        List<String> documents
    ) {
        TestIndexConfig(String indexName, Template template, Settings.Builder settings, Mapping mapping) {
            this(indexName, template, settings, mapping, new ArrayList<>());
        }
    }

    private static final int NUM_INDICES = 3;
    private static final int NUM_DOCS = 8;

    private static DataGeneratorSpecification buildDefaultSpec() {
        return buildIndexModeSpec(
            List.of(
                new PredefinedField.WithGeneratorProvider(
                    "flattened",
                    FieldType.FLATTENED,
                    Map.of("type", "flattened"),
                    FlattenedFieldDataGenerator::new
                )
            )
        );
    }

    /**
     * Builds a {@link DataGeneratorSpecification} suitable for logsdb (and logsdb_columnar) index mode.
     * Includes a predefined {@code @timestamp} field so documents are self-consistent and the source
     * matcher does not trip over the auto-populated timestamp injected by logsdb on the server side.
     * Includes a {@code synthetic_source_keep: all} keyword field to guarantee {@code _ignored_source}
     * is written and exercised by the source-fetch assertions in {@link #testQueryAll}.
     */
    private static DataGeneratorSpecification buildLogsdbSpec() {
        return buildIndexModeSpec(
            List.of(
                new PredefinedField.WithGeneratorProvider(
                    "@timestamp",
                    FieldType.DATE,
                    Map.of("type", "date"),
                    DateFieldDataGenerator::new
                ),
                new PredefinedField.WithGeneratorProvider(
                    "flattened",
                    FieldType.FLATTENED,
                    Map.of("type", "flattened"),
                    FlattenedFieldDataGenerator::new
                ),
                // synthetic_source_keep:all unconditionally writes to _ignored_source regardless of field type
                new PredefinedField.WithGenerator(
                    "keep_all",
                    FieldType.KEYWORD,
                    Map.of("type", "keyword", "synthetic_source_keep", "all"),
                    (mapping) -> ESTestCase.randomAlphaOfLengthBetween(3, 8)
                )
            )
        );
    }

    /**
     * Builds a {@link DataGeneratorSpecification} suitable for time_series index mode.
     * Includes {@code @timestamp} and a keyword dimension field ({@code ts_host}) so that every
     * document satisfies the routing-path requirement without multi-value dimension values.
     * Includes a {@code synthetic_source_keep: all} keyword field to guarantee {@code _ignored_source}
     * is written and exercised by the source-fetch assertions in {@link #testQueryAll}.
     */
    private static DataGeneratorSpecification buildTimeSeriesSpec() {
        return buildIndexModeSpec(
            List.of(
                new PredefinedField.WithGeneratorProvider(
                    "@timestamp",
                    FieldType.DATE,
                    Map.of("type", "date"),
                    DateFieldDataGenerator::new
                ),
                // Single-valued keyword dimension; lambda bypasses Wrappers.defaults to prevent array wrapping.
                new PredefinedField.WithGenerator(
                    "ts_host",
                    FieldType.KEYWORD,
                    Map.of("type", "keyword", "time_series_dimension", true),
                    (mapping) -> ESTestCase.randomAlphaOfLengthBetween(3, 8)
                ),
                new PredefinedField.WithGeneratorProvider(
                    "flattened",
                    FieldType.FLATTENED,
                    Map.of("type", "flattened"),
                    FlattenedFieldDataGenerator::new
                ),
                // synthetic_source_keep:all unconditionally writes to _ignored_source regardless of field type
                new PredefinedField.WithGenerator(
                    "keep_all",
                    FieldType.KEYWORD,
                    Map.of("type", "keyword", "synthetic_source_keep", "all"),
                    (mapping) -> ESTestCase.randomAlphaOfLengthBetween(3, 8)
                )
            )
        );
    }

    private static DataGeneratorSpecification buildIndexModeSpec(List<PredefinedField> predefinedFields) {
        return DataGeneratorSpecification.builder()
            .withMaxObjectDepth(2)
            .withMaxFieldCountPerLevel(6)
            .withPredefinedFields(predefinedFields)
            .withDataSourceHandlers(List.of(new DataSourceHandler() {
                @Override
                public DataSourceResponse.FieldTypeGenerator handle(DataSourceRequest.FieldTypeGenerator request) {
                    if (System.getProperty("tests.old_cluster_version", "").startsWith("9.0.") == false) {
                        return null;
                    }
                    var allowed = DefaultObjectGenerationHandler.ALLOWED_FIELD_TYPES.stream()
                        .filter(ft -> ft != FieldType.COUNTED_KEYWORD)
                        .toList();
                    return new DataSourceResponse.FieldTypeGenerator(
                        () -> new DataSourceResponse.FieldTypeGenerator.FieldTypeInfo(ESTestCase.randomFrom(allowed).toString())
                    );
                }
            }))
            .withDataSourceHandlers(List.of(new DefaultMappingParametersHandler() {
                @Override
                protected Object extendedDocValuesParams() {
                    if (oldClusterHasFeature(MapperFeatures.DOC_VALUES_MULTI_VALUE_RENAME)) {
                        return super.extendedDocValuesParams();
                    }
                    return ESTestCase.randomBoolean();
                }
            }))
            .withDataSourceHandlers(List.of(MultifieldAddonHandler.STRING_TYPE_HANDLER))
            .withDataSourceHandlers(List.of(new ASCIIStringsHandler()))
            .build();
    }

    @Override
    public String getEnsureGreenTimeout() {
        return "2m";
    }

    // -------------------------------------------------------------------------
    // Test methods
    // -------------------------------------------------------------------------

    public void testIndexingStandardSource() throws IOException {
        var spec = buildDefaultSpec();
        Settings.Builder builder = Settings.builder().put(IndexSettings.INDEX_MAPPER_SOURCE_MODE_SETTING.getKey(), "stored");
        testIndexing("test-index-standard-", builder, new DocumentGenerator(spec), new TemplateGenerator(spec), new MappingGenerator(spec));
    }

    public void testIndexingSyntheticSource() throws IOException {
        var spec = buildDefaultSpec();
        Settings.Builder builder = Settings.builder().put(IndexSettings.INDEX_MAPPER_SOURCE_MODE_SETTING.getKey(), "synthetic");
        if (randomBoolean()) {
            builder.put(Mapper.SYNTHETIC_SOURCE_KEEP_INDEX_SETTING.getKey(), "arrays");
        }
        testIndexing(
            "test-index-synthetic-",
            builder,
            new DocumentGenerator(spec),
            new TemplateGenerator(spec),
            new MappingGenerator(spec)
        );
    }

    /**
     * Tests logsdb index mode (synthetic source by default) across a rolling upgrade.
     * Logsdb uses {@code _ignored_source} in doc-values format for indices at or after
     * {@code IGNORED_SOURCE_AS_DOC_VALUES_NO_FF}. The predefined {@code keep_all} field uses
     * {@code synthetic_source_keep: all} to guarantee {@code _ignored_source} is written;
     * {@link #testQueryAll} reads {@code _source} back and catches format-flip regressions
     * that would make pre-upgrade docs unreadable after the upgrade.
     */
    public void testIndexingLogsdb() throws IOException {
        var spec = buildLogsdbSpec();
        Settings.Builder builder = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB.getName());
        testIndexing("test-index-logsdb-", builder, new DocumentGenerator(spec), new TemplateGenerator(spec), new MappingGenerator(spec));
    }

    /**
     * Tests time_series index mode (synthetic source by default) across a rolling upgrade.
     * Like logsdb, time_series uses the TSDB doc-values format for {@code _ignored_source}.
     * The predefined {@code keep_all} field uses {@code synthetic_source_keep: all} to guarantee
     * {@code _ignored_source} is written; {@link #testQueryAll} reads {@code _source} back and
     * catches format-flip regressions introduced by feature-flag removal or index-version gate changes.
     */
    public void testIndexingTimeSeries() throws IOException {
        var spec = buildTimeSeriesSpec();
        Settings.Builder builder = Settings.builder()
            .put(IndexSettings.MODE.getKey(), IndexMode.TIME_SERIES.getName())
            .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "ts_host");
        testIndexing("test-index-ts-", builder, new DocumentGenerator(spec), new TemplateGenerator(spec), new MappingGenerator(spec));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void testIndexing(
        String indexNameBase,
        Settings.Builder settings,
        DocumentGenerator docGen,
        TemplateGenerator tmplGen,
        MappingGenerator mapGen
    ) throws IOException {
        TestIndexConfig[] indexConfigs = new TestIndexConfig[NUM_INDICES];

        for (int i = 0; i < NUM_INDICES; i++) {
            indexConfigs[i] = createIndex(indexNameBase + i, settings, tmplGen, mapGen);
            indexAndQueryDocuments(indexConfigs[i], docGen);
        }

        flush(indexNameBase + "*", true);
        clusterRollingUpgrade(index -> {
            ensureGreen(indexNameBase + "*");
            for (int j = 0; j < NUM_INDICES; j++) {
                indexAndQueryDocuments(indexConfigs[j], docGen);
            }
        });
    }

    private TestIndexConfig createIndex(String indexName, Settings.Builder settings, TemplateGenerator tmplGen, MappingGenerator mapGen)
        throws IOException {
        var template = tmplGen.generate();
        TestIndexConfig indexConfig = new TestIndexConfig(indexName, template, settings, mapGen.generate(template));

        @SuppressWarnings("unchecked")
        Map<String, Object> mappingRaw = (Map<String, Object>) indexConfig.mapping.raw().get("_doc");
        String mappingStr = Strings.toString(XContentFactory.jsonBuilder().map(mappingRaw));
        logger.info(() -> indexName + " mappings: " + mappingStr);
        createIndex(indexName, settings.build(), mappingStr);

        return indexConfig;
    }

    private void indexAndQueryDocuments(TestIndexConfig indexConfig, DocumentGenerator docGen) throws IOException {
        indexDocuments(indexConfig, docGen);
        testQueryAll(indexConfig);
        testEsqlSource(indexConfig);
    }

    private void indexDocuments(TestIndexConfig indexConfig, DocumentGenerator docGen) throws IOException {
        StringBuilder bulkBuilder = new StringBuilder();
        for (int i = 0; i < NUM_DOCS; ++i) {
            int docId = indexConfig.documents.size();
            Map<String, Object> doc = docGen.generate(indexConfig.template, indexConfig.mapping);
            String docStr = Strings.toString(XContentFactory.jsonBuilder().map(doc));
            indexConfig.documents.add(docStr);
            bulkBuilder.append(Strings.format("{\"create\":{ \"_id\": %d }}\n", docId));
            bulkBuilder.append(docStr).append('\n');
        }

        String jsonBody = bulkBuilder.toString();
        var request = new Request("POST", "/" + indexConfig.indexName + "/_bulk");
        request.setJsonEntity(jsonBody);
        request.addParameter("refresh", "true");
        var response = client().performRequest(request);
        assertOK(response);
        var responseBody = entityAsMap(response);
        assertThat("errors in bulk response:\n " + responseBody, responseBody.get("errors"), equalTo(false));
    }

    private void testQueryAll(TestIndexConfig indexConfig) throws IOException {
        var xcontentMappings = XContentFactory.jsonBuilder().map(indexConfig.mapping().raw());

        var actualSettings = getIndexSettingsAsMap(indexConfig.indexName);
        var actualSettingsBuilder = Settings.builder().loadFromMap(actualSettings);

        var query = new SearchSourceBuilder().query(QueryBuilders.matchAllQuery()).size(indexConfig.documents.size());

        var expectedDocs = indexConfig.documents.stream()
            .map(d -> XContentHelper.convertToMap(XContentType.JSON.xContent(), d, true))
            .toList();

        var queryHits = getQueryHits(queryIndex(indexConfig.indexName, query));

        final MatchResult matchResult = Matcher.matchSource()
            .mappings(indexConfig.mapping().lookup(), xcontentMappings, xcontentMappings)
            .settings(actualSettingsBuilder, indexConfig.settings)
            .expected(expectedDocs)
            .ignoringSort(true)
            .isEqualTo(queryHits);
        assertTrue(matchResult.getMessage(), matchResult.isMatch());
    }

    private Response queryIndex(final String indexName, final SearchSourceBuilder search) throws IOException {
        final Request request = new Request("GET", "/" + indexName + "/_search");
        request.setJsonEntity(Strings.toString(search));
        return client().performRequest(request);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getQueryHits(final Response response) throws IOException {
        final Map<String, Object> map = XContentHelper.convertToMap(XContentType.JSON.xContent(), response.getEntity().getContent(), true);

        final List<Map<String, Object>> hitsList = (List<Map<String, Object>>) ((Map<String, Object>) map.get("hits")).get("hits");

        assertThat(hitsList.size(), greaterThan(0));

        return hitsList.stream()
            .sorted(Comparator.comparing((Map<String, Object> hit) -> Integer.valueOf((String) hit.get("_id"))))
            .map(hit -> (Map<String, Object>) hit.get("_source"))
            .toList();
    }

    private void testEsqlSource(TestIndexConfig indexConfig) throws IOException {
        var xcontentMappings = XContentFactory.jsonBuilder().map(indexConfig.mapping().raw());

        var actualSettings = getIndexSettingsAsMap(indexConfig.indexName);
        var actualSettingsBuilder = Settings.builder().loadFromMap(actualSettings);

        var expectedDocs = indexConfig.documents.stream()
            .map(d -> XContentHelper.convertToMap(XContentType.JSON.xContent(), d, true))
            .toList();

        final String query = "FROM "
            + indexConfig.indexName
            + " METADATA _source, _id | KEEP _source, _id | LIMIT "
            + indexConfig.documents.size();
        var queryHits = getEsqlSourceResults(esql(query));

        final MatchResult matchResult = Matcher.matchSource()
            .mappings(indexConfig.mapping().lookup(), xcontentMappings, xcontentMappings)
            .settings(actualSettingsBuilder, indexConfig.settings)
            .expected(expectedDocs)
            .ignoringSort(true)
            .isEqualTo(queryHits);
        assertTrue(matchResult.getMessage(), matchResult.isMatch());
    }

    private Response esql(final String query) throws IOException {
        final Request request = new Request("POST", "/_query");
        request.setJsonEntity("{\"query\": \"" + query + "\"}");
        return client().performRequest(request);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getEsqlSourceResults(final Response response) throws IOException {
        final Map<String, Object> map = XContentHelper.convertToMap(XContentType.JSON.xContent(), response.getEntity().getContent(), true);
        final List<List<Object>> values = (List<List<Object>>) map.get("values");
        assertThat(values.size(), greaterThan(0));

        // Results contain a list of [source, id] lists.
        return values.stream()
            .sorted(Comparator.comparing((List<Object> value) -> Integer.valueOf(((String) value.get(1)))))
            .map(value -> (Map<String, Object>) value.get(0))
            .toList();
    }
}
