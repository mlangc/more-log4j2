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
package com.github.mlangc.more.log4j2.demos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

class MarkerBasedLogThrottlingDemo {
    private static final Logger LOG;

    static {
        System.setProperty("log4j2.configurationFile", "MarkerBasedThrottlingDemoConfig.xml");
        LOG = LoggerFactory.getLogger(MarkerBasedLogThrottlingDemo.class);
    }

    public static final Marker THROTTLED_1 = MarkerFactory.getMarker("throttled1");
    public static final Marker THROTTLED_10 = MarkerFactory.getMarker("throttled10");

    public static void main(String[] args) {
        for (int i = 0; i < 10000; i++) {
            LOG.info(THROTTLED_1, "something={}", i);
            LOG.info(THROTTLED_10, "something else={}", i);
        }

        LOG.info("and by the way....");
    }
}
