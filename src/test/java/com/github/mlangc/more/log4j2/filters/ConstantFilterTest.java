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

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.filter.DenyAllFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class ConstantFilterTest {
    record TestCase(Filter filter, Result result) { }

    @ParameterizedTest
    @MethodSource("testCases")
    void allFilterMethodsShouldReturnSameResult(TestCase testCase) throws InvocationTargetException, IllegalAccessException {
        for (Method method : Filter.class.getMethods()) {
            if (!"filter".equals(method.getName()) || !Result.class.equals(method.getReturnType())) {
                continue;
            }

            assertThat(method.invoke(testCase.filter, new Object[method.getParameterCount()]))
                    .as("method=%s", method)
                    .isEqualTo(testCase.result);
        }
    }

    static List<TestCase> testCases() {
        return List.of(
                new TestCase(new AcceptAllFilter(), Result.ACCEPT),
                new TestCase(DenyAllFilter.newBuilder().build(), Result.DENY),
                new TestCase(new NeutralFilter(), Result.NEUTRAL));
    }
}
