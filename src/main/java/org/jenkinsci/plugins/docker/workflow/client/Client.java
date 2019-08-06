/*
 * The MIT License
 *
 * Copyright (c) 2015, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.docker.workflow.client;

import com.google.common.base.Optional;
import hudson.EnvVars;
import hudson.util.VersionNumber;
import org.jenkinsci.plugins.docker.commons.fingerprint.ContainerRecord;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Simple docker client for Pipeline.
 *
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public interface Client {

    /**
     * Run a docker image.
     *
     * @param launchEnv             Docker client launch environment.
     * @param image                 The image name.
     * @param args                  Any additional arguments for the {@code docker run} command.
     * @param workdir               The working directory in the container, or {@code null} for default.
     * @param volumes               Volumes to be bound. Supply an empty list if no volumes are to be bound.
     * @param volumesFromContainers Mounts all volumes from the given containers.
     * @param containerEnv          Environment variables to set in container.
     * @param user                  The <strong>uid:gid</strong> to execute the container command as. Use {@link #whoAmI()}.
     * @param command               The command to execute in the image container being run.
     * @return The container ID.
     */
    ContainerRecord run(@Nonnull EnvVars launchEnv, @Nonnull String image, @CheckForNull String args, @CheckForNull String workdir, @Nonnull Map<String, String> volumes, @Nonnull Collection<String> volumesFromContainers, @Nonnull EnvVars containerEnv, @Nonnull String user, @Nonnull String... command) throws IOException, InterruptedException;

    List<String> listProcess(@Nonnull EnvVars launchEnv, @Nonnull ContainerRecord containerRecord) throws IOException, InterruptedException;

    /**
     * Stop a container.
     *
     * <p>
     * Also removes ({@link #rm(EnvVars, String)}) the container.
     *
     * @param launchEnv   Docker client launch environment.
     * @param containerId The container ID.
     */
    void stop(@Nonnull EnvVars launchEnv, @Nonnull String containerId) throws IOException, InterruptedException;

    /**
     * Remove a container.
     *
     * @param launchEnv   Docker client launch environment.
     * @param containerId The container ID.
     */
    void rm(@Nonnull EnvVars launchEnv, @Nonnull String containerId) throws IOException, InterruptedException;

    /**
     * Inspect a docker image/container.
     *
     * @param launchEnv Docker client launch environment.
     * @param objectId  The image/container ID.
     * @param fieldPath The data path of the data required e.g. {@code .NetworkSettings.IPAddress}.
     * @return The inspected field value. Null if the command failed
     */
    @CheckForNull
    String inspect(@Nonnull EnvVars launchEnv, @Nonnull String objectId, @Nonnull String fieldPath) throws IOException, InterruptedException;

    /**
     * Inspect a docker image/container.
     *
     * @param launchEnv Docker client launch environment.
     * @param objectId  The image/container ID.
     * @param fieldPath The data path of the data required e.g. {@code .NetworkSettings.IPAddress}.
     * @return The inspected field value. May be an empty string
     * @throws IOException          Execution error. Also fails if cannot retrieve the requested field from the request
     * @throws InterruptedException Interrupted
     * @since 1.1
     */
    @Nonnull
    String inspectRequiredField(@Nonnull EnvVars launchEnv, @Nonnull String objectId,
                                @Nonnull String fieldPath) throws IOException, InterruptedException;

    /**
     * Get the docker version.
     *
     * @return The {@link VersionNumber} instance if the version string matches the expected format,
     * otherwise {@code null}.
     */
    @CheckForNull
    VersionNumber version() throws IOException, InterruptedException;

    /**
     * Who is executing this {@link Client} instance.
     *
     * @return a {@link String} containing the <strong>uid:gid</strong>.
     */
    String whoAmI() throws IOException, InterruptedException;

    /**
     * Checks if this {@link Client} instance is running inside a container and returns the id of the container
     * if so.
     *
     * @return an optional string containing the <strong>container id</strong>, or <strong>absent</strong> if
     * it isn't containerized.
     * @see <a href="http://stackoverflow.com/a/25729598/12916">Discussion</a>
     */
    Optional<String> getContainerIdIfContainerized() throws IOException, InterruptedException;

    ContainerRecord getContainerRecord(@Nonnull EnvVars launchEnv, String serviceName, String containerId, String containerName, String containerHost) throws IOException, InterruptedException;

    /**
     * Inspect the mounts of a container.
     * These might have been declared {@code VOLUME}s, or mounts defined via {@code --volume}.
     *
     * @param launchEnv   Docker client launch environment.
     * @param containerID The container ID.
     * @return a list of filesystem paths inside the container
     * @throws IOException          Execution error. Also fails if cannot retrieve the requested field from the request
     * @throws InterruptedException Interrupted
     */
    List<String> getVolumes(@Nonnull EnvVars launchEnv, String containerID) throws IOException, InterruptedException;
}
