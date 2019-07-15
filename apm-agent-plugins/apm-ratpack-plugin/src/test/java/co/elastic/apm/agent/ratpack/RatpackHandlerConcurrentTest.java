/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2019 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.agent.ratpack;

import co.elastic.apm.agent.MockReporter;
import co.elastic.apm.agent.bci.ElasticApmAgent;
import co.elastic.apm.agent.configuration.CoreConfiguration;
import co.elastic.apm.agent.configuration.SpyConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.ElasticApmTracerBuilder;
import co.elastic.apm.agent.impl.transaction.Id;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.util.HexUtils;
import co.elastic.apm.agent.web.WebConfiguration;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.stagemonitor.configuration.ConfigurationRegistry;
import ratpack.exec.Blocking;
import ratpack.exec.Promise;
import ratpack.server.RatpackServer;
import ratpack.test.ServerBackedApplicationUnderTest;
import ratpack.test.http.TestHttpClient;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;

import static co.elastic.apm.agent.impl.transaction.TraceContext.TRACE_PARENT_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class RatpackHandlerConcurrentTest {

    @Nullable
    private static MockReporter reporter;
    @Nullable
    private static ConfigurationRegistry config;
    @Nullable
    private static ServerBackedApplicationUnderTest applicationUnderTest;
    @Nullable
    private static TestHttpClient client;

    @BeforeAll
    static void initInstrumentation() throws IOException {
        reporter = new MockReporter();
        config = SpyConfiguration.createSpyConfig();
        final ElasticApmTracer tracer = new ElasticApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(reporter)
            .build();
        ElasticApmAgent.initInstrumentation(tracer, ByteBuddyAgent.install());

        final CoreConfiguration coreConfiguration = tracer.getConfig(CoreConfiguration.class);
        coreConfiguration.getSampleRate().update(1d, SpyConfiguration.CONFIG_SOURCE_NAME);

        final WebConfiguration webConfiguration = tracer.getConfig(WebConfiguration.class);
        when(webConfiguration.getCaptureBody()).thenReturn(WebConfiguration.EventType.ALL);
    }

    @BeforeAll
    static void createServer() throws Exception {
        applicationUnderTest = ServerBackedApplicationUnderTest.of(
            RatpackServer.of(s -> {
                s.serverConfig(c ->
                    c.port(0));
                s.handlers(c -> {
                    c.get("happy/:iteration", ctx -> {

                        final String iteration = ctx.getPathTokens().getOrDefault("iteration", "-1");

                        Blocking.get(() ->
                            Blocking.on(Promise.value("Hello World! (" + iteration + ")"))
                        ).then(ctx::render);
                    });
                });
            })
        );

        client = applicationUnderTest.getHttpClient();
    }

    @AfterAll
    static void closeServer() {
        assert applicationUnderTest != null;
        applicationUnderTest.close();
    }

    @AfterAll
    static void reset() {
        SpyConfiguration.reset(config);
        reporter.reset();
        ElasticApmAgent.reset();
    }

    // Repetition count should be greater than 2 * available processors (size of compute thread pool)
    @ParameterizedTest
    @MethodSource("tracepaths")
    @Execution(ExecutionMode.CONCURRENT)
    void shouldInstrumentRequestWithTransaction(String traceParent) throws Exception {
        assert client != null;

        // given
        final String path = "/happy/" + traceParent;

        // when
        client.request(path, requestSpec -> {
            requestSpec.headers(mutableHeaders -> {
                mutableHeaders.add(TRACE_PARENT_HEADER, traceParent);
            });
        });

        // then
        assertHasTransaction(traceParent, "GET /happy/:iteration", 200);
    }

    private void assertHasTransaction(final String traceParent, final String transactionName, final int status) {
        final Optional<Transaction> optionalTransaction = findTransactionInTrace(traceParent);

        assertThat(optionalTransaction).isPresent();

        final Transaction transaction = optionalTransaction.get();

        assertThat(transaction).isNotNull();
        assertThat(transaction.getName().toString()).isEqualTo(transactionName);
        assertThat(transaction.getResult()).contains(String.valueOf(status));
        assertThat(transaction.getContext().getResponse().isFinished()).isTrue();
        assertThat(transaction.getContext().getResponse().getStatusCode()).isEqualTo(status);
    }

    private Optional<Transaction> findTransactionInTrace(final String traceParent) {

        Optional<Transaction> transaction = Optional.empty();

        while (!transaction.isPresent()) {

            transaction = reporter.getTransactions().stream().filter(input -> {
                final String outgoing = input.getTraceContext().getOutgoingTraceParentHeader().toString();
                return findTraceId(outgoing).equals(findTraceId(traceParent));
            }).findFirst();
        }

        return transaction;
    }

    static Stream<String> tracepaths() {
        return Stream
            .generate(RatpackHandlerConcurrentTest::generatedTracepath)
            .limit(100);
    }

    static String generatedTracepath() {
        final StringBuilder sb = new StringBuilder();
        sb.append("00-");

        final Id traceId = Id.new128BitId();
        traceId.setToRandomValue();
        traceId.writeAsHex(sb);

        sb.append('-');

        final Id spanId = Id.new64BitId();
        spanId.setToRandomValue();
        spanId.writeAsHex(sb);

        sb.append('-');

        HexUtils.writeByteAsHex((byte) 1, sb);
        return sb.toString();
    }

    static String findTraceId(final String tracePath) {
        return tracePath.substring(3, 35);
    }

}
