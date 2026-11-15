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

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation for all tests using the {@link AsyncHttpAppender}.
 *
 * @implNote {@link CheckForAsyncHttpAppenderResourceLeaks} relies on {@link AsyncHttpAppender} related tests being executed sequentially.
 * The used {@link ResourceLock} is meant to take care of this, even if tests are generally executed in parallel.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ResourceLock("AsyncHttpAppender")
@ExtendWith(CheckForAsyncHttpAppenderResourceLeaks.class)
@interface UseAsyncHttpAppender {

}
