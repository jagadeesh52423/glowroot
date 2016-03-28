/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.webdriver.tests.config;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.agent.webdriver.tests.Utils;

import static org.openqa.selenium.By.cssSelector;
import static org.openqa.selenium.By.linkText;

public class ConfigSidebar {

    private final WebDriver driver;

    public ConfigSidebar(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getGaugesLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Gauges"));
    }

    public WebElement getAlertsLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Alerts"));
    }

    public WebElement getUiLink() {
        return Utils.withWait(driver, getSidebar(), linkText("UI"));
    }

    public WebElement getInstrumentationLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Instrumentation"));
    }

    public WebElement getAdvancedLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Advanced"));
    }

    public WebElement getAccessLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Access"));
    }

    public WebElement getStorageLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Storage"));
    }

    public WebElement getSmtpLink() {
        return Utils.withWait(driver, getSidebar(), linkText("SMTP"));
    }

    private WebElement getSidebar() {
        return Utils.withWait(driver, cssSelector("div.gt-sidebar"));
    }
}
