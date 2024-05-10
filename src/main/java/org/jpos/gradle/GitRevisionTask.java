/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2024 jPOS Software SRL
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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

@DisableCachingByDefault(because = "We want to check Git status each time")
class GitRevisionTask extends DefaultTask {
    @OutputFile
    File outputFile;

    @Inject
    public GitRevisionTask (File outputFile) {
        this.outputFile = outputFile;
    }

    public File getOutputFile() {
        return outputFile;
    }

    @TaskAction
    public void writeFile() throws IOException, GitAPIException {
        new File(outputFile.getParent()).mkdirs();
        createProperties().store(new FileOutputStream(outputFile), "Generated by GitRevisionTask");
    }

    private Properties createProperties() throws IOException, GitAPIException {
        Properties props=new Properties();
        try (Git git = Git.open(getProject().getRootProject().getProjectDir())) {
            var status = git.status().call();
            Repository rep = git.getRepository();
            props.put("branch", rep.getBranch());
            Iterator<RevCommit> iter = git.log().call().iterator();
            RevCommit commit = iter.next();
            props.put ("revision", (iter.hasNext() ? commit.getId().abbreviate(7).name() : "unknown") + (status.isClean() ? "" : "/dirty"));
            if (!status.isClean()) {
                put(props, "modified", status.getModified());
                put(props, "conflicting", status.getConflicting());
                put(props, "changed", status.getChanged());
                put(props, "added", status.getAdded());
                put(props, "ignored", status.getIgnoredNotInIndex());
                put(props, "missing", status.getMissing());
                put(props, "removed", status.getRemoved());
                put(props, "uncommitted", status.getUncommittedChanges());
                put(props, "untracked", status.getUntracked());
                put(props, "untrackedFolders", status.getUntrackedFolders());
            }
        } catch (Throwable ignored) {
            props.put("revision", "unknown");
        }
        return props;
    }

    private void put (Properties props, String name, Set<String> set) {
        if (!set.isEmpty())
            props.put (name, set.toString());
    }
}
