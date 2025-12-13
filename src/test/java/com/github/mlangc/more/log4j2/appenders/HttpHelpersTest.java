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
package com.github.mlangc.more.log4j2.appenders;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static com.github.mlangc.more.log4j2.appenders.HttpHelpers.parseHttpStatusCodes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HttpHelpersTest {
    record ParseHttpStatusCodeTestCase(String input, int[] expected) {
        static ParseHttpStatusCodeTestCase withValidInput(String input, int... expexted) {
            return new ParseHttpStatusCodeTestCase(input, expexted);
        }

        static ParseHttpStatusCodeTestCase withInvalidInput(String input) {
            return new ParseHttpStatusCodeTestCase(input, null);
        }
    }

    static List<ParseHttpStatusCodeTestCase> parseHttpStatusCodeTestCases() {
        return List.of(
                ParseHttpStatusCodeTestCase.withValidInput(""),
                ParseHttpStatusCodeTestCase.withValidInput("200", 200),
                ParseHttpStatusCodeTestCase.withInvalidInput("99"),
                ParseHttpStatusCodeTestCase.withInvalidInput("1000"),
                ParseHttpStatusCodeTestCase.withInvalidInput("-1"),
                ParseHttpStatusCodeTestCase.withValidInput("200, 202", 200, 202),
                ParseHttpStatusCodeTestCase.withValidInput("200, 200, 202, 204", 200, 202, 204),
                ParseHttpStatusCodeTestCase.withInvalidInput("200,,201,")
        );
    }

    @ParameterizedTest
    @MethodSource("parseHttpStatusCodeTestCases")
    void parseHttpStatusCodesShouldWork(ParseHttpStatusCodeTestCase testCase) {
        if (testCase.expected != null) {
            assertThat(parseHttpStatusCodes(testCase.input)).isEqualTo(testCase.expected);
        } else {
            assertThatIllegalArgumentException().isThrownBy(() -> parseHttpStatusCodes(testCase.input));
        }
    }
}
