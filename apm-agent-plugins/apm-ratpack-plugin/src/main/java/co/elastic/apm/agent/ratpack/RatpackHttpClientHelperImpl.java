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

import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.exec.Promise;
import ratpack.exec.Result;
import ratpack.func.Action;
import ratpack.http.client.HttpResponse;
import ratpack.http.client.RequestSpec;

import java.net.ProtocolException;
import java.net.URI;

import static co.elastic.apm.agent.http.client.HttpClientHelper.EXTERNAL_TYPE;
import static co.elastic.apm.agent.http.client.HttpClientHelper.HTTP_SUBTYPE;

@SuppressWarnings("unused")
public class RatpackHttpClientHelperImpl implements RatpackHttpClientInstrumentation.HttpClientHelper<Action<? super RequestSpec>, Promise<? extends HttpResponse>> {

    private static final Logger logger = LoggerFactory.getLogger(RatpackHttpClientHelperImpl.class);

    @Override
    public Action<? super RequestSpec> startHttpClientSpan(final Span span, final URI uri, final Action<? super RequestSpec> action) {

        return action.append(new RequestAction(span));
    }

    @Override
    public Promise<? extends HttpResponse> endHttpClientSpan(final Span span, final Promise<? extends HttpResponse> response) {

        return response.wiretap(new ErrorAction(span)).next(new ResponseAction(span));
    }

    private class RequestAction implements Action<RequestSpec> {
        private final Span span;
        private boolean recorded = false;

        RequestAction(final Span span) {
            this.span = span;
        }

        @Override
        public void execute(final RequestSpec requestSpec) {

            final String traceHeader = span.getTraceContext().getOutgoingTraceParentHeader().toString();
            requestSpec.getHeaders().add(TraceContext.TRACE_PARENT_HEADER, traceHeader);

            if (recorded) {
                return;
            }

            final String method = requestSpec.getMethod().getName();
            final String host = requestSpec.getUri().getHost();
            final String uri = requestSpec.getUri().toString();

            logger.debug("Recording span [{}] request [{}].", span, uri);

            span
                .withType(EXTERNAL_TYPE)
                .withSubtype(HTTP_SUBTYPE)
                .appendToName(method).appendToName(" ").appendToName(host);

            span.getContext().getHttp().withUrl(uri);

            recorded = true;
        }
    }

    private class ErrorAction implements Action<Result<? extends HttpResponse>> {
        private final Span span;

        ErrorAction(final Span span) {

            this.span = span;
        }

        @Override
        public void execute(final Result<? extends HttpResponse> result) {
            if (result.isError()) {

                final Throwable throwable = result.getThrowable();

                logger.debug("Capturing error for span [{}]: [{}].", span, throwable);

                span.captureException(throwable);

            } else if (isRedirect(result.getValue().getStatusCode())) {

                span.captureException(new ProtocolException("Too many follow-up requests."));
            }
        }

        private boolean isRedirect(int code) {
            return code == 301 || code == 302 || code == 303 || code == 307;
        }
    }

    private class ResponseAction implements Action<HttpResponse> {
        private final Span span;

        ResponseAction(final Span span) {

            this.span = span;
        }

        @Override
        public void execute(final HttpResponse response) {

            logger.debug("Ending span [{}] with response [{}].", span, response.getStatusCode());

            span.getContext().getHttp().withStatusCode(response.getStatusCode());
            span.end();
        }
    }
}
