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
package com.github.mlangc.more.log4j2.test.helpers;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;

import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;

public class WireMockHelpers {
    public static void configureMappingWithRandomFailuresAndTimeouts(
            MappingBuilder mappingBuilder, int triggerTimeoutMs, double failureRate,
            int medianResponseTime, int responseTimeSigma,
            double timeoutRatio, double nonRetryableRatio) {

        ResponseDefWithMetadata respDefWithMetadata;
        if (failureRate <= 0) {
            respDefWithMetadata = new ResponseDefWithMetadata(ok(), false);
        } else if (failureRate >= 1) {
            respDefWithMetadata = randomFailure(triggerTimeoutMs, timeoutRatio, nonRetryableRatio);
        } else {
            if (ThreadLocalRandom.current().nextDouble() < failureRate) {
                respDefWithMetadata = randomFailure(triggerTimeoutMs, timeoutRatio, nonRetryableRatio);
            } else {
                respDefWithMetadata = new ResponseDefWithMetadata(ok(), false);
            }
        }

        var resp = respDefWithMetadata.respDef;
        if (medianResponseTime > 0 && !respDefWithMetadata.timeoutSet) {
            resp.withLogNormalRandomDelay(medianResponseTime, responseTimeSigma);
        }

        mappingBuilder.willReturn(resp);
    }

    private record ResponseDefWithMetadata(ResponseDefinitionBuilder respDef, boolean timeoutSet) { }

    private static ResponseDefWithMetadata randomFailure(int triggerTimeoutMs, double timeoutRatio, double nonRetryableRatio) {
        var selectFailure = ThreadLocalRandom.current().nextDouble();

        boolean timeoutSet = false;
        ResponseDefinitionBuilder respDef;
        if (selectFailure < timeoutRatio) {
            respDef = aResponse().withStatus(408).withBody("Request Timeout").withFixedDelay(triggerTimeoutMs);
            timeoutSet = true;
        } else if (selectFailure < timeoutRatio + nonRetryableRatio) {
            respDef = aResponse().withStatus(418).withBody("I'm a teapot");
        } else if (ThreadLocalRandom.current().nextBoolean()) {
            respDef = aResponse().withStatus(429).withBody("Too Many Requests");
        } else {
            respDef = aResponse().withStatus(508).withFault(Fault.values()[ThreadLocalRandom.current().nextInt(Fault.values().length)]);
        }

        return new ResponseDefWithMetadata(respDef, timeoutSet);
    }
}
