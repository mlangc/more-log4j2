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