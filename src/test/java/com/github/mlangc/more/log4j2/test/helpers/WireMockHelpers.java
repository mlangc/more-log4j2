package com.github.mlangc.more.log4j2.test.helpers;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;

import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;

public class WireMockHelpers {
    public static void configureMappingWithRandomFailuresAndTimeouts(
            MappingBuilder mappingBuilder, int triggerTimeoutMs, double failureRate, int medianResponseTime,
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
            resp.withLogNormalRandomDelay(medianResponseTime, 1);
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
            respDef = aResponse().withFault(Fault.values()[ThreadLocalRandom.current().nextInt(Fault.values().length)]);
        }

        return new ResponseDefWithMetadata(respDef, timeoutSet);
    }
}
