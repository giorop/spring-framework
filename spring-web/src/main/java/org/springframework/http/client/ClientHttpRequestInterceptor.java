/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.client;

import java.io.IOException;

import org.springframework.http.HttpRequest;

/**
 * Contract to intercept client-side HTTP requests. Implementations can be
 * registered with {@link org.springframework.web.client.RestClient} or
 * {@link org.springframework.web.client.RestTemplate} to modify the outgoing
 * request and/or the incoming response.
 *
 * @author Arjen Poutsma
 * @since 3.1
 */
@FunctionalInterface
public interface ClientHttpRequestInterceptor {
	//增强 clientHttpRequest执行 拦截器 比如鉴权等
	/**
	 * Intercept the given request, and return a response. The given
	 * {@link ClientHttpRequestExecution} allows the interceptor to pass on the
	 * request and response to the next entity in the chain.
	 * <p>A typical implementation of this method would follow the following pattern:
	 * <ol>
	 * <li>Examine the {@linkplain HttpRequest request} and body.</li>
	 * <li>Optionally {@linkplain org.springframework.http.client.support.HttpRequestWrapper
	 * wrap} the request to filter HTTP attributes.</li>
	 * <li>Optionally modify the body of the request.</li>
	 * <ul>
	 * <li><strong>either</strong> execute the request using
	 * {@link ClientHttpRequestExecution#execute(HttpRequest, byte[])}</li>
	 * <li><strong>or</strong> do not execute the request to block the execution altogether</li>
	 * </ul>
	 * <li>Optionally wrap the response to filter HTTP attributes.</li>
	 * </ol>
	 * <p>Note: if the interceptor throws an exception after receiving a response,
	 * it must close the response via {@link ClientHttpResponse#close()}.
	 * @param request the request, containing method, URI, and headers
	 * @param body the body of the request
	 * @param execution the request execution
	 * @return the response
	 * @throws IOException in case of I/O errors
	 */
	ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException;

}
