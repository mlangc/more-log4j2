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
package com.github.mlangc.more.log4j2.filters;


import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.AbstractLifeCycle;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.message.Message;

import java.util.Arrays;

import static java.util.Objects.requireNonNull;

@Plugin(name = "RoutingFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
public class RoutingFilter extends AbstractLifeCycle implements Filter {
    private final DefaultFilterRoute defaultFilterRoute;
    private final FilterRoute[] filterRoutes;

    @Plugin(name = "DefaultFilterRoute", category = Node.CATEGORY)
    public static final class DefaultFilterRoute {
        private final Filter filter;

        public DefaultFilterRoute(Filter filter) {
            requireNonNull(filter);
            this.filter = filter;
        }

        @PluginFactory
        public static DefaultFilterRoute create(@PluginElement("DefaultFilterRoute") Filter filter) {
            return new DefaultFilterRoute(filter);
        }

        public Filter filter() {
            return filter;
        }

        @Override
        public String toString() {
            return "DefaultFilterRoute[" +
                   "filter=" + filter + ']';
        }
    }

    @Plugin(name = "FilterRoute", category = Node.CATEGORY, printObject = true)
    public static final class FilterRoute {
        private final FilterRouteIf filterRouteIf;
        private final FilterRouteThen filterRouteThen;

        public FilterRoute(FilterRouteIf filterRouteIf, FilterRouteThen filterRouteThen) {
            requireNonNull(filterRouteIf);
            requireNonNull(filterRouteThen);
            this.filterRouteIf = filterRouteIf;
            this.filterRouteThen = filterRouteThen;
        }

        @PluginFactory
        public static FilterRoute create(
                @PluginElement("FilterRouteIf") FilterRouteIf filterRouteIf,
                @PluginElement("FilterRouteThen") FilterRouteThen filterRouteThen) {
            return new FilterRoute(filterRouteIf, filterRouteThen);
        }

        public FilterRouteIf filterRouteIf() {
            return filterRouteIf;
        }

        public FilterRouteThen filterRouteThen() {
            return filterRouteThen;
        }

        @Override
        public String toString() {
            return "FilterRoute[" +
                   "filterRouteIf=" + filterRouteIf + ", " +
                   "filterRouteThen=" + filterRouteThen + ']';
        }

    }

    @Plugin(name = "FilterRouteIf", category = Node.CATEGORY, printObject = true)
    public static final class FilterRouteIf {
        private final Filter filter;

        public FilterRouteIf(Filter filter) {
            requireNonNull(filter);
            this.filter = filter;
        }

        @PluginFactory
        public static FilterRouteIf create(@PluginElement("FilterRouteIf") Filter filter) {
            return new FilterRouteIf(filter);
        }

        public Filter filter() {
            return filter;
        }

        @Override
        public String toString() {
            return "FilterRouteIf[" +
                   "filter=" + filter + ']';
        }

    }

    @Plugin(name = "FilterRouteThen", category = Node.CATEGORY, printObject = true)
    public static final class FilterRouteThen {
        private final Filter filter;

        public FilterRouteThen(Filter filter) {
            requireNonNull(filter);
            this.filter = filter;
        }

        @PluginFactory
        public static FilterRouteThen create(@PluginElement("FilterRouteThen") Filter filter) {
            return new FilterRouteThen(filter);
        }

        public Filter filter() {
            return filter;
        }

        @Override
        public String toString() {
            return "FilterRouteThen[" +
                   "filter=" + filter + ']';
        }

    }

    public RoutingFilter(DefaultFilterRoute defaultFilterRoute, FilterRoute[] filterRoutes) {
        this.defaultFilterRoute = requireNonNull(defaultFilterRoute);
        this.filterRoutes = requireNonNull(filterRoutes);
    }

    @PluginFactory
    public static RoutingFilter create(
            @PluginElement("DefaultFilterRoute") DefaultFilterRoute defaultFilterRoute,
            @PluginElement("FilterRoute") FilterRoute... filterRoutes
    ) {
        return new RoutingFilter(defaultFilterRoute, filterRoutes);
    }

    @Override
    public String toString() {
        return "RoutingFilter{" +
               "defaultFilterRoute=" + defaultFilterRoute +
               ", filterRoutes=" + Arrays.toString(filterRoutes) +
               '}';
    }

    @Override
    public Result getOnMismatch() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Result getOnMatch() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, msg, params))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, msg, params);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, msg, params);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, msg, p0))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, msg, p0);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, msg, p0);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2, p3))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2, p3);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2, p3);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7, p8))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7, p8);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7, p8);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, msg, t))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, msg, t);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, msg, t);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, msg, t))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, msg, t);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, msg, t);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(logger, level, marker, msg))) {
                return filterRoute.filterRouteThen().filter().filter(logger, level, marker, msg);
            }
        }

        return defaultFilterRoute.filter().filter(logger, level, marker, msg);
    }

    @Override
    public Result filter(LogEvent event) {
        for (FilterRoute filterRoute : filterRoutes) {
            if (Result.ACCEPT.equals(filterRoute.filterRouteIf().filter().filter(event))) {
                return filterRoute.filterRouteThen().filter().filter(event);
            }
        }

        return defaultFilterRoute.filter().filter(event);
    }

    FilterRoute[] filterRoutes() {
        return filterRoutes;
    }
}
