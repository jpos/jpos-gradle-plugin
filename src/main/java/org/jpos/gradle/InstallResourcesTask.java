/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2026 jPOS Software SRL
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jpos.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;

/**
 * Installs q2 module resources embedded under META-INF/q2/installs.
 */
@DisableCachingByDefault(because = "Resource installation is an imperative JavaExec task")
public abstract class InstallResourcesTask extends JavaExec {
    private final Property<String> outputDir;

    /**
     * Creates an install resources task.
     *
     * @param objects Gradle object factory used to create task properties
     */
    @Inject
    public InstallResourcesTask(ObjectFactory objects) {
        outputDir = objects.property(String.class);
    }

    /**
     * Returns the directory where embedded q2 resources are installed.
     *
     * @return output directory property, defaulting to {@code jpos.installDir}
     */
    @Input
    public Property<String> getOutputDir() {
        return outputDir;
    }

    /**
     * Sets the directory where embedded q2 resources are installed.
     *
     * @param outputDir output directory path supplied through the {@code --outputDir} task option
     */
    @Option(option = "outputDir", description = "Output directory for installed resources. Defaults to jpos.installDir.")
    public void setOutputDir(String outputDir) {
        this.outputDir.set(outputDir);
    }
}
