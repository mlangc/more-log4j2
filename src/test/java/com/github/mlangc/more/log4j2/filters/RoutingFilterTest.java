/*-
 * #%L
 * more-log4j2
 * %%
 * Copyright (C) 2025 Matthias Langer
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.mlangc.more.log4j2.test.helpers.TestHelpers.logWithAllOverloads;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@LoggerContextSource("RoutingFilterTest.xml")
class RoutingFilterTest {
    static final Marker COLORED = MarkerManager.getMarker("color");
    static final Marker RED = MarkerManager.getMarker("red").addParents(COLORED);
    static final Marker YELLOW = MarkerManager.getMarker("yellow").addParents(COLORED);
    static final Marker GREEN = MarkerManager.getMarker("green").addParents(COLORED);
    static final Marker BLUE = MarkerManager.getMarker("blue").addParents(COLORED);

    static final Marker TOP_LEVEL = MarkerManager.getMarker("topLevel");
    static final Marker APPENDER_CTL = MarkerManager.getMarker("appenderCtl");

    static final Marker APPENDER = MarkerManager.getMarker("appender");

    private final ListAppender throttledListAppender;
    private final LoggerContext loggerContext;

    RoutingFilterTest(LoggerContext loggerContext, @Named("ThrottledListAppender") ListAppender throttledListAppender) {
        this.loggerContext = loggerContext;
        this.throttledListAppender = throttledListAppender;
    }

    @BeforeEach
    void beforeEach() {
        throttledListAppender.clear();
    }

    @Test
    void logsWithoutMarkersShouldNotBeFiltered() {
        ExtendedLogger log = loggerContext.getLogger(getClass());

        int numLogs = logWithAllOverloads(log, null, "boring");
        assertThat(throttledListAppender.getEvents())
                .hasSize(numLogs)
                .allSatisfy(evt -> assertThat(evt.getMessage().getFormattedMessage()).contains("boring"));
    }

    @Test
    void logsForColoredLoggerShouldBeFilteredBasedOnTheirColorMarker() {
        Logger log = loggerContext.getLogger(getClass().getCanonicalName() + ".Colored");

        int logLines = logWithAllOverloads(log, RED, "red lips");
        logLines += logWithAllOverloads(log, RED, "red lips");
        logLines += logWithAllOverloads(log, GREEN, "green grass");
        logLines += logWithAllOverloads(log, BLUE, "blue sky");
        logLines += logWithAllOverloads(log, YELLOW, "yellow submarine");

        assertThat(throttledListAppender.getEvents())
                .filteredOn(e -> e.getMarker().isInstanceOf(COLORED))
                .hasSize(logLines);

        throttledListAppender.clear();
        logWithAllOverloads(log, RED, "blue lips");
        logWithAllOverloads(log, GREEN, "brown grass");
        logWithAllOverloads(log, BLUE, "red sky");
        logWithAllOverloads(log, YELLOW, "blue submarine");
        assertThat(throttledListAppender.getEvents()).isEmpty();

        logLines = logWithAllOverloads(log, null, "all is grey");
        logLines += logWithAllOverloads(log, null, "black as midnight on a moonless night");
        assertThat(throttledListAppender.getEvents()).hasSize(logLines);

        throttledListAppender.clear();
        logWithAllOverloads(log, null, "colors, give me colors");
        assertThat(throttledListAppender.getEvents()).isEmpty();
    }

    @Test
    void logsForStdLoggerShouldNotBeFilteredBasedOnTheirColorMarker() {
        ExtendedLogger log = loggerContext.getLogger(getClass());

        int logLines = logWithAllOverloads(log, RED, "blue lips");
        logLines += logWithAllOverloads(log, GREEN, "brown grass");
        logLines += logWithAllOverloads(log, BLUE, "red sky");
        logLines += logWithAllOverloads(log, YELLOW, "blue submarine");

        assertThat(throttledListAppender.getEvents())
                .filteredOn(evt -> evt.getMarker().isInstanceOf(COLORED))
                .hasSize(logLines);
    }

    @Test
    void logsWithTopLevelMarkersShouldBeFilteredIfNotContainingTop() {
        ExtendedLogger log = loggerContext.getLogger(getClass());

        int logLines = logWithAllOverloads(log, TOP_LEVEL, "top gun");
        logWithAllOverloads(log, TOP_LEVEL, "nakte kanone");
        assertThat(throttledListAppender.getEvents())
                .filteredOn(evt -> evt.getMarker().isInstanceOf(TOP_LEVEL))
                .hasSize(logLines)
                .allSatisfy(evt -> assertThat(evt.getMessage().getFormattedMessage()).contains("top gun"));
    }

    @Test
    void logsWithAppenderCtlMarkersShouldBeFilteredIfNotContainingAppenderControl() {
        ExtendedLogger log = loggerContext.getLogger(getClass());

        int logLines = logWithAllOverloads(log, APPENDER_CTL, "appender control");
        logWithAllOverloads(log, APPENDER_CTL, "out of control");

        assertThat(throttledListAppender.getEvents())
                .filteredOn(evt -> evt.getMarker().isInstanceOf(APPENDER_CTL))
                .hasSize(logLines)
                .anySatisfy(evt -> assertThat(evt.getMessage().getFormattedMessage()).contains("appender control"));
    }

    @Test
    void logsWithAppenderMarkersShouldBeFilteredIfNotContainingAppenderStage() {
        ExtendedLogger log = loggerContext.getLogger(getClass());

        int logLines = logWithAllOverloads(log, APPENDER, "appender stage");
        logWithAllOverloads(log, APPENDER, "back stage");

        assertThat(throttledListAppender.getEvents())
                .filteredOn(evt -> evt.getMarker().isInstanceOf(APPENDER))
                .hasSize(logLines)
                .anySatisfy(evt -> assertThat(evt.getMessage().getFormattedMessage()).contains("appender stage"));
    }

    @Test
    void onMatchOrMismatchShouldThrow() {
        RoutingFilter routingFilter = (RoutingFilter) loggerContext.getConfiguration().getFilter();
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(routingFilter::getOnMatch);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(routingFilter::getOnMismatch);
    }
}
