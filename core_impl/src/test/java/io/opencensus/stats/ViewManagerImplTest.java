/*
 * Copyright 2017, Google Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opencensus.stats;

import static com.google.common.truth.Truth.assertThat;
import static io.opencensus.stats.StatsTestUtil.createContext;

import io.opencensus.common.Duration;
import io.opencensus.common.Timestamp;
import io.opencensus.internal.SimpleEventQueue;
import io.opencensus.stats.Measure.DoubleMeasure;
import io.opencensus.stats.View.DistributionView;
import io.opencensus.stats.ViewDescriptor.DistributionViewDescriptor;
import io.opencensus.stats.ViewDescriptor.IntervalViewDescriptor;
import io.opencensus.testing.common.TestClock;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ViewManagerImpl}. */
@RunWith(JUnit4.class)
public class ViewManagerImplTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  private static final TagKey KEY = TagKey.create("KEY");

  private static final TagValue VALUE = TagValue.create("VALUE");
  private static final TagValue VALUE_2 = TagValue.create("VALUE_2");

  private static final String MEASUREMENT_NAME = "my measurement";

  private static final String MEASUREMENT_NAME_2 = "my measurement 2";

  private static final String MEASUREMENT_UNIT = "us";

  private static final String MEASUREMENT_DESCRIPTION = "measurement description";

  private static final DoubleMeasure MEASUREMENT_DESCRIPTOR =
      Measure.DoubleMeasure.create(MEASUREMENT_NAME, MEASUREMENT_DESCRIPTION, MEASUREMENT_UNIT);

  private static final ViewDescriptor.Name VIEW_NAME = ViewDescriptor.Name.create("my view");
  private static final ViewDescriptor.Name VIEW_NAME_2 = ViewDescriptor.Name.create("my view 2");

  private static final String VIEW_DESCRIPTION = "view description";

  private static final BucketBoundaries BUCKET_BOUNDARIES =
      BucketBoundaries.create(
          Arrays.asList(
              0.0, 0.2, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 7.0, 10.0, 15.0, 20.0, 30.0, 40.0, 50.0));

  private static final DistributionAggregationDescriptor DISTRIBUTION_AGGREGATION_DESCRIPTOR =
      DistributionAggregationDescriptor.create(BUCKET_BOUNDARIES.getBoundaries());

  private final TestClock clock = TestClock.create();

  private final StatsComponentImplBase statsComponent =
      new StatsComponentImplBase(new SimpleEventQueue(), clock);

  private final StatsContextFactoryImpl factory = statsComponent.getStatsContextFactory();
  private final ViewManagerImpl viewManager = statsComponent.getViewManager();
  private final StatsRecorder statsRecorder = statsComponent.getStatsRecorder();

  private static DistributionViewDescriptor createDistributionViewDescriptor() {
    return createDistributionViewDescriptor(
        VIEW_NAME, MEASUREMENT_DESCRIPTOR, DISTRIBUTION_AGGREGATION_DESCRIPTOR, Arrays.asList(KEY));
  }

  private static DistributionViewDescriptor createDistributionViewDescriptor(
      ViewDescriptor.Name name,
      Measure measureDescr,
      DistributionAggregationDescriptor aggDescr,
      List<TagKey> keys) {
    return DistributionViewDescriptor.create(name, VIEW_DESCRIPTION, measureDescr, aggDescr, keys);
  }

  @Test
  public void testRegisterAndGetView() {
    DistributionViewDescriptor viewDescr = createDistributionViewDescriptor();
    viewManager.registerView(viewDescr);
    assertThat(viewManager.getView(VIEW_NAME).getViewDescriptor()).isEqualTo(viewDescr);
  }

  @Test
  public void preventRegisteringIntervalView() {
    ViewDescriptor intervalView =
        IntervalViewDescriptor.create(
            VIEW_NAME,
            VIEW_DESCRIPTION,
            MEASUREMENT_DESCRIPTOR,
            IntervalAggregationDescriptor.create(Arrays.asList(Duration.fromMillis(1000))),
            Arrays.asList(KEY));
    thrown.expect(UnsupportedOperationException.class);
    viewManager.registerView(intervalView);
  }

  @Test
  public void allowRegisteringSameViewDescriptorTwice() {
    DistributionViewDescriptor viewDescr = createDistributionViewDescriptor();
    viewManager.registerView(viewDescr);
    viewManager.registerView(viewDescr);
    assertThat(viewManager.getView(VIEW_NAME).getViewDescriptor()).isEqualTo(viewDescr);
  }

  @Test
  public void preventRegisteringDifferentViewDescriptorWithSameName() {
    ViewDescriptor view1 =
        DistributionViewDescriptor.create(
            VIEW_NAME,
            "View description.",
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY));
    viewManager.registerView(view1);
    ViewDescriptor view2 =
        DistributionViewDescriptor.create(
            VIEW_NAME,
            "This is a different description.",
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY));
    try {
      thrown.expect(IllegalArgumentException.class);
      thrown.expectMessage("A different view with the same name is already registered");
      viewManager.registerView(view2);
    } finally {
      assertThat(viewManager.getView(VIEW_NAME).getViewDescriptor()).isEqualTo(view1);
    }
  }

  @Test
  public void disallowGettingNonexistentView() {
    thrown.expect(IllegalArgumentException.class);
    viewManager.getView(VIEW_NAME);
  }

  @Test
  public void testRecord() {
    DistributionViewDescriptor viewDescr =
        createDistributionViewDescriptor(
            VIEW_NAME,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY));
    clock.setTime(Timestamp.create(1, 2));
    viewManager.registerView(viewDescr);
    StatsContextImpl tags = createContext(factory, KEY, VALUE);
    for (double val : Arrays.asList(10.0, 20.0, 30.0, 40.0)) {
      statsRecorder.record(tags, MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, val).build());
    }
    clock.setTime(Timestamp.create(3, 4));
    DistributionView view = (DistributionView) viewManager.getView(VIEW_NAME);
    assertThat(view.getViewDescriptor()).isEqualTo(viewDescr);
    assertThat(view.getStart()).isEqualTo(Timestamp.create(1, 2));
    assertThat(view.getEnd()).isEqualTo(Timestamp.create(3, 4));
    assertDistributionAggregationsEquivalent(
        view.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)),
                BUCKET_BOUNDARIES,
                Arrays.asList(10.0, 20.0, 30.0, 40.0))));
  }

  @Test
  public void getViewDoesNotClearStats() {
    DistributionViewDescriptor viewDescr =
        createDistributionViewDescriptor(
            VIEW_NAME,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY));
    clock.setTime(Timestamp.create(10, 0));
    viewManager.registerView(viewDescr);
    StatsContextImpl tags = createContext(factory, KEY, VALUE);
    statsRecorder.record(tags, MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 0.1).build());
    clock.setTime(Timestamp.create(11, 0));
    DistributionView view1 = (DistributionView) viewManager.getView(VIEW_NAME);
    assertThat(view1.getStart()).isEqualTo(Timestamp.create(10, 0));
    assertThat(view1.getEnd()).isEqualTo(Timestamp.create(11, 0));
    assertDistributionAggregationsEquivalent(
        view1.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)), BUCKET_BOUNDARIES, Arrays.asList(0.1))));
    statsRecorder.record(tags, MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 0.2).build());
    clock.setTime(Timestamp.create(12, 0));
    DistributionView view2 = (DistributionView) viewManager.getView(VIEW_NAME);

    // The second view should have the same start time as the first view, and it should include both
    // recorded values:
    assertThat(view2.getStart()).isEqualTo(Timestamp.create(10, 0));
    assertThat(view2.getEnd()).isEqualTo(Timestamp.create(12, 0));
    assertDistributionAggregationsEquivalent(
        view2.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)),
                BUCKET_BOUNDARIES,
                Arrays.asList(0.1, 0.2))));
  }

  @Test
  public void testRecordMultipleTagValues() {
    viewManager.registerView(
        createDistributionViewDescriptor(
            VIEW_NAME,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY)));
    statsRecorder.record(
        createContext(factory, KEY, VALUE),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 10.0).build());
    statsRecorder.record(
        createContext(factory, KEY, VALUE_2),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 30.0).build());
    statsRecorder.record(
        createContext(factory, KEY, VALUE_2),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 50.0).build());
    DistributionView view = (DistributionView) viewManager.getView(VIEW_NAME);
    assertDistributionAggregationsEquivalent(
        view.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)), BUCKET_BOUNDARIES, Arrays.asList(10.0)),
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE_2)),
                BUCKET_BOUNDARIES,
                Arrays.asList(30.0, 50.0))));
  }

  // This test checks that StatsRecorder.record(...) does not throw an exception when no views are
  // registered.
  @Test
  public void allowRecordingWithoutRegisteringMatchingView() {
    statsRecorder.record(
        createContext(factory, KEY, VALUE),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 10).build());
  }

  @Test
  public void testRecordWithEmptyStatsContext() {
    viewManager.registerView(
        createDistributionViewDescriptor(
            VIEW_NAME,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY)));
    // DEFAULT doesn't have tags, but the view has tag key "KEY".
    statsRecorder.record(factory.getDefault(),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 10.0).build());
    DistributionView view = (DistributionView) viewManager.getView(VIEW_NAME);
    assertDistributionAggregationsEquivalent(
        view.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                // Tag is missing for associated measurementValues, should use default tag value
                // "unknown/not set"
                Arrays.asList(Tag.create(KEY, MutableView.UNKNOWN_TAG_VALUE)),
                BUCKET_BOUNDARIES,
                // Should record stats with default tag value: "KEY" : "unknown/not set".
                Arrays.asList(10.0))));
  }

  @Test
  public void testRecordWithNonExistentMeasurementDescriptor() {
    viewManager.registerView(
        createDistributionViewDescriptor(
            VIEW_NAME,
            Measure.DoubleMeasure.create(MEASUREMENT_NAME, "measurement", MEASUREMENT_UNIT),
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY)));
    DoubleMeasure measure2 =
        Measure.DoubleMeasure.create(MEASUREMENT_NAME_2, "measurement", MEASUREMENT_UNIT);
    statsRecorder.record(createContext(factory, KEY, VALUE),
        MeasureMap.builder().set(measure2, 10.0).build());
    DistributionView view = (DistributionView) viewManager.getView(VIEW_NAME);
    assertThat(view.getDistributionAggregations()).isEmpty();
  }

  @Test
  public void testRecordWithTagsThatDoNotMatchView() {
    viewManager.registerView(
        createDistributionViewDescriptor(
            VIEW_NAME,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY)));
    statsRecorder.record(
        createContext(factory, TagKey.create("wrong key"), VALUE),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 10.0).build());
    statsRecorder.record(
        createContext(factory, TagKey.create("another wrong key"), VALUE),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 50.0).build());
    DistributionView view = (DistributionView) viewManager.getView(VIEW_NAME);
    assertDistributionAggregationsEquivalent(
        view.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                // Won't record the unregistered tag key, will use default tag instead:
                // "KEY" : "unknown/not set".
                Arrays.asList(Tag.create(KEY, MutableView.UNKNOWN_TAG_VALUE)),
                BUCKET_BOUNDARIES,
                // Should record stats with default tag value: "KEY" : "unknown/not set".
                Arrays.asList(10.0, 50.0))));
  }

  @Test
  public void testViewWithMultipleTagKeys() {
    TagKey key1 = TagKey.create("Key-1");
    TagKey key2 = TagKey.create("Key-2");
    viewManager.registerView(
        createDistributionViewDescriptor(
            VIEW_NAME,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(key1, key2)));
    statsRecorder.record(
        createContext(factory, key1, TagValue.create("v1"), key2, TagValue.create("v10")),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 1.1).build());
    statsRecorder.record(
        createContext(factory, key1, TagValue.create("v1"), key2, TagValue.create("v20")),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 2.2).build());
    statsRecorder.record(
        createContext(factory, key1, TagValue.create("v2"), key2, TagValue.create("v10")),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 3.3).build());
    statsRecorder.record(
        createContext(factory, key1, TagValue.create("v1"), key2, TagValue.create("v10")),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 4.4).build());
    DistributionView view = (DistributionView) viewManager.getView(VIEW_NAME);
    assertDistributionAggregationsEquivalent(
        view.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(
                    Tag.create(key1, TagValue.create("v1")),
                    Tag.create(key2, TagValue.create("v10"))),
                BUCKET_BOUNDARIES,
                Arrays.asList(1.1, 4.4)),
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(
                    Tag.create(key1, TagValue.create("v1")),
                    Tag.create(key2, TagValue.create("v20"))),
                BUCKET_BOUNDARIES,
                Arrays.asList(2.2)),
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(
                    Tag.create(key1, TagValue.create("v2")),
                    Tag.create(key2, TagValue.create("v10"))),
                BUCKET_BOUNDARIES,
                Arrays.asList(3.3))));
  }

  @Test
  public void testMultipleViewsSameMeasure() {
    ViewDescriptor viewDescr1 =
        createDistributionViewDescriptor(
            VIEW_NAME,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY));
    ViewDescriptor viewDescr2 =
        createDistributionViewDescriptor(
            VIEW_NAME_2,
            MEASUREMENT_DESCRIPTOR,
            DISTRIBUTION_AGGREGATION_DESCRIPTOR,
            Arrays.asList(KEY));
    clock.setTime(Timestamp.create(1, 1));
    viewManager.registerView(viewDescr1);
    clock.setTime(Timestamp.create(2, 2));
    viewManager.registerView(viewDescr2);
    statsRecorder.record(
        createContext(factory, KEY, VALUE),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 5.0).build());
    List<DistributionAggregation> expectedAggs =
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)), BUCKET_BOUNDARIES, Arrays.asList(5.0)));
    clock.setTime(Timestamp.create(3, 3));
    DistributionView view1 = (DistributionView) viewManager.getView(VIEW_NAME);
    clock.setTime(Timestamp.create(4, 4));
    DistributionView view2 = (DistributionView) viewManager.getView(VIEW_NAME_2);
    assertThat(view1.getStart()).isEqualTo(Timestamp.create(1, 1));
    assertThat(view1.getEnd()).isEqualTo(Timestamp.create(3, 3));
    assertDistributionAggregationsEquivalent(view1.getDistributionAggregations(), expectedAggs);
    assertThat(view2.getStart()).isEqualTo(Timestamp.create(2, 2));
    assertThat(view2.getEnd()).isEqualTo(Timestamp.create(4, 4));
    assertDistributionAggregationsEquivalent(view2.getDistributionAggregations(), expectedAggs);
  }

  @Test
  public void testMultipleViewsDifferentMeasures() {
    DoubleMeasure measureDescr1 =
        Measure.DoubleMeasure.create(MEASUREMENT_NAME, MEASUREMENT_DESCRIPTION, MEASUREMENT_UNIT);
    DoubleMeasure measureDescr2 =
        Measure.DoubleMeasure.create(MEASUREMENT_NAME_2, MEASUREMENT_DESCRIPTION, MEASUREMENT_UNIT);
    ViewDescriptor viewDescr1 =
        createDistributionViewDescriptor(
            VIEW_NAME, measureDescr1, DISTRIBUTION_AGGREGATION_DESCRIPTOR, Arrays.asList(KEY));
    ViewDescriptor viewDescr2 =
        createDistributionViewDescriptor(
            VIEW_NAME_2, measureDescr2, DISTRIBUTION_AGGREGATION_DESCRIPTOR, Arrays.asList(KEY));
    clock.setTime(Timestamp.create(1, 0));
    viewManager.registerView(viewDescr1);
    clock.setTime(Timestamp.create(2, 0));
    viewManager.registerView(viewDescr2);
    statsRecorder.record(
        createContext(factory, KEY, VALUE),
        MeasureMap.builder().set(measureDescr1, 1.1).set(measureDescr2, 2.2).build());
    clock.setTime(Timestamp.create(3, 0));
    DistributionView view1 = (DistributionView) viewManager.getView(VIEW_NAME);
    clock.setTime(Timestamp.create(4, 0));
    DistributionView view2 = (DistributionView) viewManager.getView(VIEW_NAME_2);
    assertThat(view1.getStart()).isEqualTo(Timestamp.create(1, 0));
    assertThat(view1.getEnd()).isEqualTo(Timestamp.create(3, 0));
    assertDistributionAggregationsEquivalent(
        view1.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)), BUCKET_BOUNDARIES, Arrays.asList(1.1))));
    assertThat(view2.getStart()).isEqualTo(Timestamp.create(2, 0));
    assertThat(view2.getEnd()).isEqualTo(Timestamp.create(4, 0));
    assertDistributionAggregationsEquivalent(
        view2.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)), BUCKET_BOUNDARIES, Arrays.asList(2.2))));
  }

  @Test
  public void testGetDistributionViewWithoutBucketBoundaries() {
    ViewDescriptor viewDescr =
        createDistributionViewDescriptor(
            VIEW_NAME, MEASUREMENT_DESCRIPTOR, DistributionAggregationDescriptor.create(),
            Arrays.asList(KEY));
    clock.setTime(Timestamp.create(1, 0));
    viewManager.registerView(viewDescr);
    statsRecorder.record(
        createContext(factory, KEY, VALUE),
        MeasureMap.builder().set(MEASUREMENT_DESCRIPTOR, 1.1).build());
    clock.setTime(Timestamp.create(3, 0));
    DistributionView view = (DistributionView) viewManager.getView(VIEW_NAME);
    assertThat(view.getStart()).isEqualTo(Timestamp.create(1, 0));
    assertThat(view.getEnd()).isEqualTo(Timestamp.create(3, 0));
    assertDistributionAggregationsEquivalent(
        view.getDistributionAggregations(),
        Arrays.asList(
            StatsTestUtil.createDistributionAggregation(
                Arrays.asList(Tag.create(KEY, VALUE)), Arrays.asList(1.1))));
  }

  // TODO(sebright) Consider making this helper method work with larger ranges of double values and
  // moving it to StatsTestUtil.
  private static void assertDistributionAggregationsEquivalent(
      Collection<DistributionAggregation> actual, Collection<DistributionAggregation> expected) {
    StatsTestUtil.assertDistributionAggregationsEquivalent(1e-6, actual, expected);
  }
}