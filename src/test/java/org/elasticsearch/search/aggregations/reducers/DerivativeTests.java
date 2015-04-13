/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.reducers;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram.Bucket;
import org.elasticsearch.search.aggregations.metrics.sum.Sum;
import org.elasticsearch.search.aggregations.reducers.BucketHelpers.GapPolicy;
import org.elasticsearch.search.aggregations.support.AggregationPath;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.sum;
import static org.elasticsearch.search.aggregations.reducers.ReducerBuilders.derivative;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;

@ElasticsearchIntegrationTest.SuiteScopeTest
public class DerivativeTests extends ElasticsearchIntegrationTest {

    private static final String SINGLE_VALUED_FIELD_NAME = "l_value";

    private static int interval;
    private static int numValueBuckets;
    private static int numFirstDerivValueBuckets;
    private static int numSecondDerivValueBuckets;
    private static long[] valueCounts;
    private static long[] firstDerivValueCounts;
    private static long[] secondDerivValueCounts;

    private static Long[] valueCounts_empty;
    private static long numDocsEmptyIdx;
    private static Double[] firstDerivValueCounts_empty;

    // expected bucket values for random setup with gaps
    private static int numBuckets_empty_rnd;
    private static Long[] valueCounts_empty_rnd;
    private static Double[] firstDerivValueCounts_empty_rnd;
    private static long numDocsEmptyIdx_rnd;

    @Override
    public void setupSuiteScopeCluster() throws Exception {
        createIndex("idx");
        createIndex("idx_unmapped");

        interval = 5;
        numValueBuckets = randomIntBetween(6, 80);

        valueCounts = new long[numValueBuckets];
        for (int i = 0; i < numValueBuckets; i++) {
            valueCounts[i] = randomIntBetween(1, 20);
        }

        numFirstDerivValueBuckets = numValueBuckets - 1;
        firstDerivValueCounts = new long[numFirstDerivValueBuckets];
        Long lastValueCount = null;
        for (int i = 0; i < numValueBuckets; i++) {
            long thisValue = valueCounts[i];
            if (lastValueCount != null) {
                long diff = thisValue - lastValueCount;
                firstDerivValueCounts[i - 1] = diff;
            }
            lastValueCount = thisValue;
        }

        numSecondDerivValueBuckets = numFirstDerivValueBuckets - 1;
        secondDerivValueCounts = new long[numSecondDerivValueBuckets];
        Long lastFirstDerivativeValueCount = null;
        for (int i = 0; i < numFirstDerivValueBuckets; i++) {
            long thisFirstDerivativeValue = firstDerivValueCounts[i];
            if (lastFirstDerivativeValueCount != null) {
                long diff = thisFirstDerivativeValue - lastFirstDerivativeValueCount;
                secondDerivValueCounts[i - 1] = diff;
            }
            lastFirstDerivativeValueCount = thisFirstDerivativeValue;
        }

        List<IndexRequestBuilder> builders = new ArrayList<>();
        for (int i = 0; i < numValueBuckets; i++) {
            for (int docs = 0; docs < valueCounts[i]; docs++) {
                builders.add(client().prepareIndex("idx", "type").setSource(newDocBuilder(i * interval)));
            }
        }

        // setup for index with empty buckets
        valueCounts_empty = new Long[] { 1l, 1l, 2l, 0l, 2l, 2l, 0l, 0l, 0l, 3l, 2l, 1l };
        firstDerivValueCounts_empty = new Double[] { null, 0d, 1d, -2d, 2d, 0d, -2d, 0d, 0d, 3d, -1d, -1d };

        assertAcked(prepareCreate("empty_bucket_idx").addMapping("type", SINGLE_VALUED_FIELD_NAME, "type=integer"));
        for (int i = 0; i < valueCounts_empty.length; i++) {
            for (int docs = 0; docs < valueCounts_empty[i]; docs++) {
                builders.add(client().prepareIndex("empty_bucket_idx", "type").setSource(newDocBuilder(i)));
                numDocsEmptyIdx++;
            }
        }

        // randomized setup for index with empty buckets
        numBuckets_empty_rnd = randomIntBetween(20, 100);
        valueCounts_empty_rnd = new Long[numBuckets_empty_rnd];
        firstDerivValueCounts_empty_rnd = new Double[numBuckets_empty_rnd];
        firstDerivValueCounts_empty_rnd[0] = null;

        assertAcked(prepareCreate("empty_bucket_idx_rnd").addMapping("type", SINGLE_VALUED_FIELD_NAME, "type=integer"));
        for (int i = 0; i < numBuckets_empty_rnd; i++) {
            valueCounts_empty_rnd[i] = (long) randomIntBetween(1, 10);
            // make approximately half of the buckets empty
            if (randomBoolean())
                valueCounts_empty_rnd[i] = 0l;
            for (int docs = 0; docs < valueCounts_empty_rnd[i]; docs++) {
                builders.add(client().prepareIndex("empty_bucket_idx_rnd", "type").setSource(newDocBuilder(i)));
                numDocsEmptyIdx_rnd++;
            }
            if (i > 0) {
                firstDerivValueCounts_empty_rnd[i] = (double) valueCounts_empty_rnd[i] - valueCounts_empty_rnd[i - 1];
            }
        }

        indexRandom(true, builders);
        ensureSearchable();
    }

    private XContentBuilder newDocBuilder(int singleValueFieldValue) throws IOException {
        return jsonBuilder().startObject().field(SINGLE_VALUED_FIELD_NAME, singleValueFieldValue).endObject();
    }

    /**
     * test first and second derivative on the sing
     */
    @Test
    public void singleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx")
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(interval).minDocCount(0)
                                .subAggregation(derivative("deriv").setBucketsPaths("_count"))
                                .subAggregation(derivative("2nd_deriv").setBucketsPaths("deriv"))).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> deriv = response.getAggregations().get("histo");
        assertThat(deriv, notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = deriv.getBuckets();
        assertThat(buckets.size(), equalTo(numValueBuckets));

        for (int i = 0; i < numValueBuckets; ++i) {
            Histogram.Bucket bucket = buckets.get(i);
            checkBucketKeyAndDocCount("Bucket " + i, bucket, i * interval, valueCounts[i]);
            SimpleValue docCountDeriv = bucket.getAggregations().get("deriv");
            if (i > 0) {
                assertThat(docCountDeriv, notNullValue());
                assertThat(docCountDeriv.value(), equalTo((double) firstDerivValueCounts[i - 1]));
            } else {
                assertThat(docCountDeriv, nullValue());
            }
            SimpleValue docCount2ndDeriv = bucket.getAggregations().get("2nd_deriv");
            if (i > 1) {
                assertThat(docCount2ndDeriv, notNullValue());
                assertThat(docCount2ndDeriv.value(), equalTo((double) secondDerivValueCounts[i - 2]));
            } else {
                assertThat(docCount2ndDeriv, nullValue());
            }
        }
    }

    /**
     * test first and second derivative on the sing
     */
    @Test
    public void singleValuedField_normalised() {

        SearchResponse response = client()
                .prepareSearch("idx")
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(interval).minDocCount(0)
                                .subAggregation(derivative("deriv").setBucketsPaths("_count").units("1"))
                                .subAggregation(derivative("2nd_deriv").setBucketsPaths("deriv"))).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> deriv = response.getAggregations().get("histo");
        assertThat(deriv, notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = deriv.getBuckets();
        assertThat(buckets.size(), equalTo(numValueBuckets));

        for (int i = 0; i < numValueBuckets; ++i) {
            Histogram.Bucket bucket = buckets.get(i);
            checkBucketKeyAndDocCount("Bucket " + i, bucket, i * interval, valueCounts[i]);
            SimpleValue docCountDeriv = bucket.getAggregations().get("deriv");
            if (i > 0) {
                assertThat(docCountDeriv, notNullValue());
                assertThat(docCountDeriv.value(), closeTo((double) (firstDerivValueCounts[i - 1]) / 5, 0.00001));
            } else {
                assertThat(docCountDeriv, nullValue());
            }
            SimpleValue docCount2ndDeriv = bucket.getAggregations().get("2nd_deriv");
            if (i > 1) {
                assertThat(docCount2ndDeriv, notNullValue());
                assertThat(docCount2ndDeriv.value(), closeTo((double) (secondDerivValueCounts[i - 2]) / 5, 0.00001));
            } else {
                assertThat(docCount2ndDeriv, nullValue());
            }
        }
    }

    @Test
    public void singleValuedField_WithSubAggregation() throws Exception {
        SearchResponse response = client()
                .prepareSearch("idx")
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(interval).minDocCount(0)
                                .subAggregation(sum("sum").field(SINGLE_VALUED_FIELD_NAME))
                                .subAggregation(derivative("deriv").setBucketsPaths("sum"))).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> deriv = response.getAggregations().get("histo");
        assertThat(deriv, notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        assertThat(deriv.getBuckets().size(), equalTo(numValueBuckets));
        Object[] propertiesKeys = (Object[]) deriv.getProperty("_key");
        Object[] propertiesDocCounts = (Object[]) deriv.getProperty("_count");
        Object[] propertiesSumCounts = (Object[]) deriv.getProperty("sum.value");

        List<Bucket> buckets = new ArrayList<Bucket>(deriv.getBuckets());
        Long expectedSumPreviousBucket = Long.MIN_VALUE; // start value, gets
                                                         // overwritten
        for (int i = 0; i < numValueBuckets; ++i) {
            Histogram.Bucket bucket = buckets.get(i);
            checkBucketKeyAndDocCount("Bucket " + i, bucket, i * interval, valueCounts[i]);
            Sum sum = bucket.getAggregations().get("sum");
            assertThat(sum, notNullValue());
            long expectedSum = valueCounts[i] * (i * interval);
            assertThat(sum.getValue(), equalTo((double) expectedSum));
            SimpleValue sumDeriv = bucket.getAggregations().get("deriv");
            if (i > 0) {
                assertThat(sumDeriv, notNullValue());
                long sumDerivValue = expectedSum - expectedSumPreviousBucket;
                assertThat(sumDeriv.value(), equalTo((double) sumDerivValue));
                assertThat((double) bucket.getProperty("histo", AggregationPath.parse("deriv.value").getPathElementsAsStringList()),
                        equalTo((double) sumDerivValue));
            } else {
                assertThat(sumDeriv, nullValue());
            }
            expectedSumPreviousBucket = expectedSum;
            assertThat((long) propertiesKeys[i], equalTo((long) i * interval));
            assertThat((long) propertiesDocCounts[i], equalTo(valueCounts[i]));
            assertThat((double) propertiesSumCounts[i], equalTo((double) expectedSum));
        }
    }

    @Test
    public void unmapped() throws Exception {
        SearchResponse response = client()
                .prepareSearch("idx_unmapped")
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(interval).minDocCount(0)
                                .subAggregation(derivative("deriv").setBucketsPaths("_count"))).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> deriv = response.getAggregations().get("histo");
        assertThat(deriv, notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        assertThat(deriv.getBuckets().size(), equalTo(0));
    }

    @Test
    public void partiallyUnmapped() throws Exception {
        SearchResponse response = client()
                .prepareSearch("idx", "idx_unmapped")
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(interval).minDocCount(0)
                                .subAggregation(derivative("deriv").setBucketsPaths("_count"))).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> deriv = response.getAggregations().get("histo");
        assertThat(deriv, notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = deriv.getBuckets();
        assertThat(deriv.getBuckets().size(), equalTo(numValueBuckets));

        for (int i = 0; i < numValueBuckets; ++i) {
            Histogram.Bucket bucket = buckets.get(i);
            checkBucketKeyAndDocCount("Bucket " + i, bucket, i * interval, valueCounts[i]);
            SimpleValue docCountDeriv = bucket.getAggregations().get("deriv");
            if (i > 0) {
                assertThat(docCountDeriv, notNullValue());
                assertThat(docCountDeriv.value(), equalTo((double) firstDerivValueCounts[i - 1]));
            } else {
                assertThat(docCountDeriv, nullValue());
            }
        }
    }

    @Test
    public void singleValuedFieldWithGaps() throws Exception {
        SearchResponse searchResponse = client()
                .prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(1).minDocCount(0)
                                .subAggregation(derivative("deriv").setBucketsPaths("_count"))).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(numDocsEmptyIdx));

        InternalHistogram<Bucket> deriv = searchResponse.getAggregations().get("histo");
        assertThat(deriv, Matchers.notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        List<Bucket> buckets = deriv.getBuckets();
        assertThat(buckets.size(), equalTo(valueCounts_empty.length));

        for (int i = 0; i < valueCounts_empty.length; i++) {
            Histogram.Bucket bucket = buckets.get(i);
            checkBucketKeyAndDocCount("Bucket " + i, bucket, i, valueCounts_empty[i]);
            SimpleValue docCountDeriv = bucket.getAggregations().get("deriv");
            if (firstDerivValueCounts_empty[i] == null) {
                assertThat(docCountDeriv, nullValue());
            } else {
                assertThat(docCountDeriv.value(), equalTo(firstDerivValueCounts_empty[i]));
            }
        }
    }

    @Test
    public void singleValuedFieldWithGaps_random() throws Exception {
        SearchResponse searchResponse = client()
                .prepareSearch("empty_bucket_idx_rnd")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(1).minDocCount(0)
                                .extendedBounds(0l, (long) numBuckets_empty_rnd - 1)
                                .subAggregation(derivative("deriv").setBucketsPaths("_count"))).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(numDocsEmptyIdx_rnd));

        InternalHistogram<Bucket> deriv = searchResponse.getAggregations().get("histo");
        assertThat(deriv, Matchers.notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        List<Bucket> buckets = deriv.getBuckets();
        assertThat(buckets.size(), equalTo(numBuckets_empty_rnd));

        for (int i = 0; i < valueCounts_empty_rnd.length; i++) {
            Histogram.Bucket bucket = buckets.get(i);
            System.out.println(bucket.getDocCount());
            checkBucketKeyAndDocCount("Bucket " + i, bucket, i, valueCounts_empty_rnd[i]);
            SimpleValue docCountDeriv = bucket.getAggregations().get("deriv");
            if (firstDerivValueCounts_empty_rnd[i] == null) {
                assertThat(docCountDeriv, nullValue());
            } else {
                assertThat(docCountDeriv.value(), equalTo(firstDerivValueCounts_empty_rnd[i]));
            }
        }
    }

    @Test
    public void singleValuedFieldWithGaps_insertZeros() throws Exception {
        SearchResponse searchResponse = client()
                .prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(1).minDocCount(0)
                                .subAggregation(derivative("deriv").setBucketsPaths("_count").gapPolicy(GapPolicy.INSERT_ZEROS))).execute()
                                .actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(numDocsEmptyIdx));

        InternalHistogram<Bucket> deriv = searchResponse.getAggregations().get("histo");
        assertThat(deriv, Matchers.notNullValue());
        assertThat(deriv.getName(), equalTo("histo"));
        List<Bucket> buckets = deriv.getBuckets();
        assertThat(buckets.size(), equalTo(valueCounts_empty.length));

        for (int i = 0; i < valueCounts_empty.length; i++) {
            Histogram.Bucket bucket = buckets.get(i);
            checkBucketKeyAndDocCount("Bucket " + i + ": ", bucket, i, valueCounts_empty[i]);
            SimpleValue docCountDeriv = bucket.getAggregations().get("deriv");
            if (firstDerivValueCounts_empty[i] == null) {
                assertThat(docCountDeriv, nullValue());
            } else {
                assertThat(docCountDeriv.value(), equalTo(firstDerivValueCounts_empty[i]));
            }
        }
    }

    private void checkBucketKeyAndDocCount(final String msg, final Histogram.Bucket bucket, final long expectedKey,
            final long expectedDocCount) {
        assertThat(msg, bucket, notNullValue());
        assertThat(msg + " key", ((Number) bucket.getKey()).longValue(), equalTo(expectedKey));
        assertThat(msg + " docCount", bucket.getDocCount(), equalTo(expectedDocCount));
    }
}
