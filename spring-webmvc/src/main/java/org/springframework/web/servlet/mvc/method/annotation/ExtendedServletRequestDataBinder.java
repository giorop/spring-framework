/*
 * Copyright 2002-2023 the original author or authors.
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

package org.springframework.web.servlet.mvc.method.annotation;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletRequest;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Subclass of {@link ServletRequestDataBinder} that adds URI template variables
 * to the values used for data binding.
 *
 * <p><strong>WARNING</strong>: Data binding can lead to security issues by exposing
 * parts of the object graph that are not meant to be accessed or modified by
 * external clients. Therefore the design and use of data binding should be considered
 * carefully with regard to security. For more details, please refer to the dedicated
 * sections on data binding for
 * <a href="https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#mvc-ann-initbinder-model-design">Spring Web MVC</a> and
 * <a href="https://docs.spring.io/spring-framework/docs/current/reference/html/web-reactive.html#webflux-ann-initbinder-model-design">Spring WebFlux</a>
 * in the reference manual.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 * @see ServletRequestDataBinder
 * @see HandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE
 */
public class ExtendedServletRequestDataBinder extends ServletRequestDataBinder {

	/**
	 * Create a new instance, with default object name.
	 * @param target the target object to bind onto (or {@code null}
	 * if the binder is just used to convert a plain parameter value)
	 * @see #DEFAULT_OBJECT_NAME
	 */
	public ExtendedServletRequestDataBinder(@Nullable Object target) {
		super(target);
	}

	/**
	 * Create a new instance.
	 * @param target the target object to bind onto (or {@code null}
	 * if the binder is just used to convert a plain parameter value)
	 * @param objectName the name of the target object
	 * @see #DEFAULT_OBJECT_NAME
	 */
	public ExtendedServletRequestDataBinder(@Nullable Object target, String objectName) {
		super(target, objectName);
	}


	@Override
	protected ServletRequestValueResolver createValueResolver(ServletRequest request) {
		return new ExtendedServletRequestValueResolver(request, this);
	}

	/**
	 * Merge URI variables into the property values to use for data binding.
	 */
	@Override
	protected void addBindValues(MutablePropertyValues mpvs, ServletRequest request) {
		Map<String, String> uriVars = getUriVars(request);
		if (uriVars != null) {
			uriVars.forEach((name, value) -> {
				if (mpvs.contains(name)) {
					if (logger.isDebugEnabled()) {
						logger.debug("URI variable '" + name + "' overridden by request bind value.");
					}
				}
				else {
					mpvs.addPropertyValue(name, value);
				}
			});
		}
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private static Map<String, String> getUriVars(ServletRequest request) {
		String attr = HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE;
		return (Map<String, String>) request.getAttribute(attr);
	}


	/**
	 * Resolver of values that looks up URI path variables.
	 */
	private static class ExtendedServletRequestValueResolver extends ServletRequestValueResolver {

		ExtendedServletRequestValueResolver(ServletRequest request, WebDataBinder dataBinder) {
			super(request, dataBinder);
		}

		@Override
		protected Object getRequestParameter(String name, Class<?> type) {//扩展 同时兼具模板解析得到的uri变量
			Object value = super.getRequestParameter(name, type);
			if (value == null) {
				Map<String, String> uriVars = getUriVars(getRequest());
				if (uriVars != null) {
					value = uriVars.get(name);
				}
			}
			return value;
		}

		@Override
		protected Set<String> initParameterNames(ServletRequest request) {
			Set<String> set = super.initParameterNames(request);
			Map<String, String> uriVars = getUriVars(getRequest());
			if (uriVars != null) {
				set.addAll(uriVars.keySet());
			}
			return set;
		}
	}

}
