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

import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BrokenConfigsTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "BrokenConfigsTest.reference.xml",
            "BrokenConfigsTest.routingFilterWithoutChildren.xml",
            "BrokenConfigsTest.routingFilterWithEmptyDefaultFilterRoute.xml",
    })
    void brokenRoutingFiltersShouldNotBeInitialized(String configPath) {
        try (var context = TestHelpers.loggerContextFromTestResource(configPath)) {
            assertThat(context.getConfiguration().getFilter())
                    .isInstanceOfSatisfying(CompositeFilter.class, f -> assertThat(f.getFiltersArray()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BrokenConfigsTest.routingFilterWithEmptyFilterRoute.xml",
            "BrokenConfigsTest.routingFilterWithSingleBrokenFilterRoute1.xml",
            "BrokenConfigsTest.routingFilterWithSingleBrokenFilterRoute2.xml",
            "BrokenConfigsTest.routingFilterWithSingleBrokenFilterRoute3.xml"
    })
    void singleBrokenRoutingFilterRouteShouldNotBeInitialized(String configPath) {
        try (var context = TestHelpers.loggerContextFromTestResource(configPath)) {
            var compositeFilter = (CompositeFilter) context.getConfiguration().getFilter();
            assertThat(compositeFilter.getFiltersArray()).hasSize(1);

            var routingFilter = (RoutingFilter) compositeFilter.getFiltersArray()[0];
            assertThat(routingFilter.filterRoutes()).isEmpty();
        }
    }

    @Test
    @LoggerContextSource("BrokenConfigsTest.routingFilterWithBrokenAndWorkingFilterRoute.xml")
    void brokenRoutingFilterRouteShouldNotBeInitializedButNotKeepOkRouteFromBeingUsed(Configuration configuration) {
        var compositeFilter = (CompositeFilter) configuration.getFilter();
        assertThat(compositeFilter.getFiltersArray()).hasSize(1);

        var routingFilter = (RoutingFilter) compositeFilter.getFiltersArray()[0];
        assertThat(routingFilter.filterRoutes()).hasSize(1);
    }

    @Test
    @LoggerContextSource("BrokenConfigsTest.filtersWithIllegalOnMatchOnMismatch.xml")
    void constantAndRoutingFilterShouldNotAllowSettingOnMatchOrMismatch(Configuration configuration) {

    }
}
