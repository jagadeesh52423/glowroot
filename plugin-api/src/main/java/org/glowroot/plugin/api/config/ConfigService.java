/*
 * Copyright 2011-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.plugin.api.config;

public abstract class ConfigService {

    protected ConfigService() {}

    /**
     * Registers a listener that will receive a callback when the plugin's property values are
     * changed, the plugin is enabled/disabled.
     * 
     * This allows the useful plugin optimization of caching the results of {@link #isEnabled()},
     * {@link #getStringProperty(String)}, {@link #getBooleanProperty(String)}, and
     * {@link #getDoubleProperty(String)} as {@code volatile} fields, and updating the cached values
     * anytime {@link ConfigListener#onChange()} is called.
     */
    public abstract void registerConfigListener(ConfigListener listener);

    /**
     * Returns whether the plugin is enabled. When Glowroot itself is disabled, this returns
     * {@code false}.
     * 
     * Plugins can be individually disabled on the configuration page.
     */
    public abstract boolean isEnabled();

    /**
     * Returns the {@code String} plugin property value with the specified {@code name}.
     * {@code null} is never returned. If there is no {@code String} plugin property with the
     * specified {@code name} then the empty string {@code ""} is returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/glowroot.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     */
    public abstract StringProperty getStringProperty(String name);

    /**
     * Returns the {@code boolean} plugin property value with the specified {@code name}. If there
     * is no {@code boolean} plugin property with the specified {@code name} then {@code false} is
     * returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/glowroot.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     */
    public abstract BooleanProperty getBooleanProperty(String name);

    /**
     * Returns the {@code Double} plugin property value with the specified {@code name}. If there is
     * no {@code Double} plugin property with the specified {@code name} then {@code null} is
     * returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/glowroot.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     */
    public abstract DoubleProperty getDoubleProperty(String name);

    public abstract BooleanProperty getEnabledProperty(String name);
}