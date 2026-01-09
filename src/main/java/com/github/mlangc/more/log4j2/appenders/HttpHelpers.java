/*-
 * #%L
 * more-log4j2
 * %%
 * Copyright (C) 2025 - 2026 Matthias Langer
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

import java.util.Arrays;

class HttpHelpers {
    static int[] parseHttpStatusCodes(String statusCodes) {
        try {
            statusCodes = statusCodes.trim();

            if (statusCodes.isEmpty()) {
                return new int[0];
            }

            return Arrays.stream(statusCodes.split("\\s*,\\s*"))
                    .mapToInt(Integer::parseInt)
                    .sorted()
                    .distinct()
                    .peek(value -> {
                        if (value < 100 || value > 600) {
                            throw new NumberFormatException("Not a HTTP status code: " + value);
                        }
                    })
                    .toArray();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse `" + statusCodes + "` into a list of HTTP status codes", e);
        }
    }
}
