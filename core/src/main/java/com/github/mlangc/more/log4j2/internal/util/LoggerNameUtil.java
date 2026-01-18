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
package com.github.mlangc.more.log4j2.internal.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.util.InternalApi;

/**
 * Consider this class private.
 */
@InternalApi
public class LoggerNameUtil {
    public static boolean isNameEqualOrAncestorOf(String potentialAncestor, String potentialChild) {
        if (LogManager.ROOT_LOGGER_NAME.equals(potentialAncestor)) {
            return true;
        } else if (potentialAncestor == null) {
            return potentialChild == null;
        } else if (potentialChild == null) {
            return false;
        }

        if (!potentialChild.startsWith(potentialAncestor)) {
            return false;
        }

        return potentialAncestor.length() == potentialChild.length() || potentialChild.charAt(potentialAncestor.length()) == '.';
    }
}
