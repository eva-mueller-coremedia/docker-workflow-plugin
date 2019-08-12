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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.util.ArgumentListBuilder;
import hudson.util.VersionNumber;
import org.jenkinsci.plugins.docker.commons.fingerprint.ContainerRecord;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;
import org.jenkinsci.plugins.docker.workflow.ServiceRecord;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple docker swarm client for Pipelines.
 */
public class DockerSwarmClient {

    private static final Logger LOGGER = Logger.getLogger(DockerSwarmClient.class.getName());

    /**
     * Maximum amount of time (in seconds) to wait for {@code docker} client operations which are supposed to be more or less instantaneous.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "mutable for scripts")
    @Restricted(NoExternalUse.class)
    public static int CLIENT_TIMEOUT = Integer.getInteger(DockerSwarmClient.class.getName() + ".CLIENT_TIMEOUT", 180); // TODO 2.4+ SystemProperties

    // e.g. 2015-04-09T13:40:21.981801679Z
    public static final String DOCKER_DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS Z";

    private final Launcher launcher;
    private final @CheckForNull
    Node node;
    private final @CheckForNull
    String toolName;

//    private String serviceName;
//    private String taskId;
//    private String containerId;

    public DockerSwarmClient(@Nonnull Launcher launcher, @CheckForNull Node node, @CheckForNull String toolName) {
        this.launcher = launcher;
        this.node = node;
        this.toolName = toolName;
    }

    /**
     * Get Jenkins host name
     *
     * @return E.g. master-ci or toko-ci-01
     */
    private static String getJenkinsHostName() throws UnknownHostException {
        //InetAddress.getLocalHost().getCanonicalHostName().split("\\.")[0];
//        return "HAM-ITS0970";
        return System.getenv("JENKINS_HOST");
    }

    private static String getNfsShare() {
//        return "/media/rre-nfs";
        return System.getenv("JENKINS_NFS_SHARE");
    }

    private static String getDockerSwarmHostUri() {
        return System.getenv("DOCKER_SWARM_HOST_URI");
    }

    private static String getUserId() {
        return System.getenv("JENKINS_USER_ID");
    }

    private static String getDockerGroupId() {
        return System.getenv("DOCKER_GROUP_ID");
    }

    private static String getRandomString() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String generateServiceName(String workdir) throws UnknownHostException {
        return getJenkinsHostName() + "-" + getLastPart(workdir, "/") + "-" + getRandomString();
    }

    private static String getLastPart(String text, String separator) {
        String[] parts = text.split(separator);
        return parts.length == 0 ? null : parts[parts.length - 1];
    }

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
    public ServiceRecord run(@Nonnull EnvVars launchEnv, @Nonnull String image, @CheckForNull String args, @CheckForNull String workdir, @Nonnull Map<String, String> volumes, @Nonnull Collection<String> volumesFromContainers, @Nonnull EnvVars containerEnv, @Nonnull String user, @Nonnull String... command) throws IOException, InterruptedException {

        String serviceName = generateServiceName(workdir);

        ArgumentListBuilder argb = new ArgumentListBuilder();

        argb.add("-H", getDockerSwarmHostUri());
        argb.add("service", "create", "--constraint", "node.role==worker", "--name", serviceName, "-t", "-d", "-u", getUserId() +":" + getDockerGroupId(), "--replicas", "1", "--restart-condition", "none");
//        argb.add("service", "create", "--constraint", "node.role==worker", "--name", serviceName, "-t", "-d", "-u", "0:0", "--replicas", "1", "--restart-condition", "none");
        /*
        docker service create --mount type=volume,volume-opt=o=addr=HAM-ITS0970,volume-opt=device=:/Users/emueller/git-repositories/docker-workflow-plugin/work,volume-opt=type=nfs,source=work,target=/work --replicas 1 --name testnfs alpine /bin/sh -c "ls -al /work/workspace"
         */
        if (args != null) {
            argb.addTokenized(args);
        }

        if (workdir != null) {
            argb.add("-w", workdir);
        }
        /**
         * docker service create
         * --mount type=volume,volume-opt=o=addr=HAM-ITS0970,volume-opt=device=:/Users/emueller/git-repositories/docker-workflow-plugin/work,volume-opt=type=nfs,source=work,target=/work
         * --replicas 1 --name testnfs alpine /bin/sh -c "ls -al /work/workspace"
         */
        // TODO: fix hard coded nfs mount

        //--mount 'type=volume,src=test,volume-driver=local,dst=/data/,volume-nocopy=true,volume-opt=type=nfs,volume-opt=device=:/nfs/test,volume-opt=o=addr=nfs.my.corporate.network' --name test --entrypoint=ls volume:defined-with-copy /data/
//,volume-nocopy=true
        argb.add("--mount", "type=volume,src=jenkins_home_" + getJenkinsHostName()+ ",volume-driver=local,dst=/var/jenkins_home_" + getJenkinsHostName() + ",volume-opt=type=nfs,volume-opt=device=:" + getNfsShare() + ",\"volume-opt=o=addr=" + getJenkinsHostName()+".coremedia.com,rw\"");
//        argb.add("--mount", "type=bind,source=/usr/bin/docker,target=/usr/bin/docker");
//        argb.add("--mount", "type=bind,source=/usr/bin/docker,target=/usr/bin/docker");
        //argb.add("--mount", "type=volume,volume-opt=o=addr=" + getJenkinsHostName() + ",volume-opt=device=:/Users/emueller/git-repositories/docker-workflow-plugin/work/workspace/test@tmp,volume-opt=type=nfs,source=test@tmp,target=/Users/emueller/git-repositories/docker-workflow-plugin/work/workspace/test@tmp");
        for (Map.Entry<String, String> volume : volumes.entrySet()) {
//            argb.add("--mount", "type=volume,volume-opt=o=addr=" + getJenkinsHostName() + ",volume-opt=device=:/Users/emueller/git-repositories/docker-workflow-plugin/work,volume-opt=type=nfs,source=" + volume.getValue() + ",target=" + volume.getValue());
            // TODO: mount rw,z - as in DockerClient?
            argb.add("--mount", "target=" + volume.getValue());
        }
        argb.add("--mount", "type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock");
        argb.add("--mount", "type=bind,src=/usr/bin/docker,dst=/usr/bin/docker");
        argb.add("--mount", "type=bind,src=/usr/lib64/libltdl.so.7,dst=/usr/lib/libltdl.so.7");
//        argb.add("--mount", "target=/var/jenkins_home/workspace/dockertest@tmp");
//        argb.add("--mount", "target=/var/jenkins_home/workspace/dockertest");
//        argb.add("--group", "root");
//        argb.add("--mount", "target=/etc/passwd,readonly");
//        argb.add("--mount", "target=/etc/group,readonly");

// TODO: re-add
        for (String containerId : volumesFromContainers) {
//            argb.add("--volumes-from", containerId);
        }
//        argb.add("--mount", "/var/jenkins_home:/var/jenkins_home");
        for (Map.Entry<String, String> variable : containerEnv.entrySet()) {
            argb.add("-e");
            argb.addMasked(variable.getKey() + "=" + variable.getValue());
        }
        argb.add(image).add(command);

        LaunchResult result = launch(launchEnv, false, null, argb);
        if (result.getStatus() == 0) {
            try {
                String taskId = getTaskId(launchEnv, serviceName);
                String nodeId = getNodeId(launchEnv, taskId);
                String host = getNodeHost(launchEnv, nodeId);
                String containerId = getContainerId(launchEnv, taskId);

                String containerName = getContainerName(launchEnv, host, containerId);
                ContainerRecord containerRecord = getContainerRecord(launchEnv, serviceName, containerId, containerName, host);
                ServiceRecord serviceRecord = new ServiceRecord(serviceName, taskId, containerRecord);
                return serviceRecord;
            } catch (IOException | InterruptedException e) {
                rm(launchEnv, serviceName);
                throw e;
            }
        } else {
            throw new IOException(String.format("Failed to run image '%s'. Error: %s", image, result.getErr()));
        }
    }

    public static String getDockerSwarmHostUri(ContainerRecord containerRecord) {
        return "tcp://" + containerRecord.getHost() + ":2376";
    }

    private static String getDockerSwarmHostUri(String host) {
        return "tcp://" + host + ":2376";
    }

    public List<String> listProcess(@Nonnull EnvVars launchEnv, @Nonnull ContainerRecord containerRecord) throws IOException, InterruptedException {
        String containerId = containerRecord.getContainerId();
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(containerRecord), "top", containerId, "-eo", "pid,comm");
        if (result.getStatus() != 0) {
            throw new IOException(String.format("Failed to run top '%s'. Error: %s", containerId, result.getErr()));
        }
        List<String> processes = new ArrayList<>();
        try (Reader r = new StringReader(result.getOut());
             BufferedReader in = new BufferedReader(r)) {
            String line;
            in.readLine(); // ps header
            while ((line = in.readLine()) != null) {
                final StringTokenizer stringTokenizer = new StringTokenizer(line, " ");
                if (stringTokenizer.countTokens() < 2) {
                    throw new IOException("Unexpected `docker top` output : " + line);
                }
                stringTokenizer.nextToken(); // PID
                processes.add(stringTokenizer.nextToken()); // COMMAND
            }
        }
        return processes;
    }

    /**
     * Stop a container.
     *
     * @param launchEnv     Docker client launch environment.
     * @param serviceRecord The container ID.
     */
    public void stop(@Nonnull EnvVars launchEnv, @Nonnull ServiceRecord serviceRecord) throws IOException, InterruptedException {
        // There is no stop for services in Docker Swarm. Services are deleted
        // https://docs.docker.com/engine/swarm/swarm-tutorial/delete-service/
        rm(launchEnv, serviceRecord);
    }

    /**
     * Delete a service.
     *
     * @param launchEnv     Docker client launch environment.
     * @param serviceRecord The container ID.
     */
    public void rm(@Nonnull EnvVars launchEnv, @Nonnull ServiceRecord serviceRecord) throws IOException, InterruptedException {
        rm(launchEnv, serviceRecord.getServiceName());
    }

    /**
     * Delete a service.
     *
     * @param launchEnv   Docker client launch environment.
     * @param serviceName The container ID.
     */
    public void rm(@Nonnull EnvVars launchEnv, @Nonnull String serviceName) throws IOException, InterruptedException {
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(), "service", "rm", serviceName);
        if (result.getStatus() != 0) {
            throw new IOException(String.format("Failed to delete service '%s'.", serviceName));
        }
    }

    /**
     * Inspect a docker image/container.
     *
     * @param launchEnv Docker client launch environment.
     * @param objectId  The image/container ID.
     * @param fieldPath The data path of the data required e.g. {@code .NetworkSettings.IPAddress}.
     * @return The inspected field value. Null if the command failed
     */
    public @CheckForNull
    String inspect(@Nonnull EnvVars launchEnv, @Nonnull String objectId, @Nonnull String fieldPath) throws IOException, InterruptedException {
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(), "inspect", "-f", String.format("{{%s}}", fieldPath), objectId);
        if (result.getStatus() == 0) {
            return result.getOut();
        }
        throw new IOException("Cannot retrieve " + fieldPath + " from 'docker inspect " + objectId + "'");
    }

    public @CheckForNull
    String inspectService(@Nonnull EnvVars launchEnv, @Nonnull String objectId, @Nonnull String fieldPath) throws IOException, InterruptedException {
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(), "service", "inspect", "-f", String.format("{{%s}}", fieldPath), objectId);
        if (result.getStatus() == 0) {
            return result.getOut();
        }
        throw new IOException("Cannot retrieve " + fieldPath + " from 'docker inspect " + objectId + "'");
    }

    private String getTaskId(@Nonnull EnvVars launchEnv, @Nonnull String service) throws IOException, InterruptedException {
        //docker service ps --filter 'desired-state=running' $SERVICE_NAME -q
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(), "service", "ps", "--filter", "desired-state=running", service, "-q");
        if (result.getStatus() == 0) {
            return result.getOut();
        }
        throw new IOException("Cannot retrieve task id from 'docker service ps' for service " + service + "'");
    }

    private String getNodeId(@Nonnull EnvVars launchEnv, @Nonnull String taskId) throws IOException, InterruptedException {
        //NODE_ID=$(docker inspect --format '{{ .NodeID }}' $TASK_ID)
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(), "inspect", "--format", "{{ .NodeID }}", taskId);
        if (result.getStatus() == 0) {
            return result.getOut();
        }
        throw new IOException("Cannot retrieve task id from 'docker inspect' for task " + taskId);
    }

    private String getContainerId(@Nonnull EnvVars launchEnv, @Nonnull String taskId) throws IOException, InterruptedException {
        //CONTAINER_ID=$(docker inspect --format '{{ .Status.ContainerStatus.ContainerID }}' $TASK_ID)
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(), "inspect", "--format", "{{.Status.ContainerStatus.ContainerID}}", taskId);
        if (result.getStatus() == 0) {
            return result.getOut();
        }
        throw new IOException("Cannot retrieve container id from 'docker inspect' for task " + taskId);
    }

    private String getContainerName(@Nonnull EnvVars launchEnv, @Nonnull String host, @Nonnull String containerId) throws IOException, InterruptedException {
        //docker -H tcp://m12n-ci-01-swarm-node-01.coremedia.vm:2376 ps --filter 'id=42e3f7123f2b' --format {{.Names}}
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(host), "ps", "--filter", "id=" + containerId, "--format", "{{.Names}}");
        if (result.getStatus() == 0) {
            return result.getOut();
        }
        throw new IOException("Cannot retrieve container name from 'docker ps' for container id " + containerId);
    }

    private String getNodeHost(@Nonnull EnvVars launchEnv, @Nonnull String nodeId) throws IOException, InterruptedException {
        //NODE_HOST=$(docker node inspect --format '{{ .Description.Hostname }}' $NODE_ID)
        LaunchResult result = launch(launchEnv, false, "-H", getDockerSwarmHostUri(), "inspect", "--format", "{{.Description.Hostname}}", nodeId);
        if (result.getStatus() == 0) {
            return result.getOut();
        }
        throw new IOException("Cannot retrieve host from 'docker inspect' for node " + nodeId);
    }

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
    public @Nonnull
    String inspectRequiredField(@Nonnull EnvVars launchEnv, @Nonnull String objectId,
                                @Nonnull String fieldPath) throws IOException, InterruptedException {
        final String fieldValue = inspect(launchEnv, objectId, fieldPath);
        if (fieldValue == null) {
            throw new IOException("Cannot retrieve " + fieldPath + " from 'docker inspect " + objectId + "'");
        }
        return fieldValue;
    }

    private @CheckForNull
    Date getCreatedDate(@Nonnull EnvVars launchEnv, @Nonnull String objectId) throws IOException, InterruptedException {
        String createdString = inspectService(launchEnv, objectId, ".CreatedAt");
        if (createdString == null) {
            return null;
        }
        try {
            return new SimpleDateFormat(DOCKER_DATE_TIME_FORMAT).parse(createdString);
        } catch (ParseException e) {
            throw new IOException(String.format("Error parsing created date '%s' for object '%s'.", createdString, objectId), e);
        }
    }

    /**
     * Get the docker version.
     *
     * @return The {@link VersionNumber} instance if the version string matches the expected format,
     * otherwise {@code null}.
     */
    public @CheckForNull
    VersionNumber version() throws IOException, InterruptedException {
        LaunchResult result = launch(new EnvVars(), true, "-v");
        if (result.getStatus() == 0) {
            return parseVersionNumber(result.getOut());
        } else {
            return null;
        }
    }

    private static final Pattern pattern = Pattern.compile("^(\\D+)(\\d+)\\.(\\d+)\\.(\\d+)(.*)");

    /**
     * Parse a Docker version string (e.g. "Docker version 1.5.0, build a8a31ef").
     *
     * @param versionString The version string to parse.
     * @return The {@link VersionNumber} instance if the version string matched the
     * expected format, otherwise {@code null}.
     */
    protected static VersionNumber parseVersionNumber(@Nonnull String versionString) {
        Matcher matcher = pattern.matcher(versionString.trim());
        if (matcher.matches()) {
            String major = matcher.group(2);
            String minor = matcher.group(3);
            String maint = matcher.group(4);
            return new VersionNumber(String.format("%s.%s.%s", major, minor, maint));
        } else {
            return null;
        }
    }

    private LaunchResult launch(@Nonnull EnvVars launchEnv, boolean quiet, @Nonnull String... args) throws IOException, InterruptedException {
        return launch(launchEnv, quiet, null, args);
    }

    private LaunchResult launch(@Nonnull EnvVars launchEnv, boolean quiet, FilePath pwd, @Nonnull String... args) throws IOException, InterruptedException {
        return launch(launchEnv, quiet, pwd, new ArgumentListBuilder(args));
    }

    private LaunchResult launch(@CheckForNull @Nonnull EnvVars launchEnv, boolean quiet, FilePath pwd, @Nonnull ArgumentListBuilder args) throws IOException, InterruptedException {
        // Prepend the docker command
        args.prepend(DockerTool.getExecutable(toolName, node, launcher.getListener(), launchEnv));

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Executing docker command {0}", args.toString());
        }

        Launcher.ProcStarter procStarter = launcher.launch();

        if (pwd != null) {
            procStarter.pwd(pwd);
        }

        LaunchResult result = new LaunchResult();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        result.setStatus(procStarter.quiet(quiet).cmds(args).envs(launchEnv).stdout(out).stderr(err).start().joinWithTimeout(CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener()));
        final String charsetName = Charset.defaultCharset().name();
        result.setOut(out.toString(charsetName));
        result.setErr(err.toString(charsetName));
        return result;
    }

    /**
     * Who is executing this {@link DockerSwarmClient} instance.
     *
     * @return a {@link String} containing the <strong>uid:gid</strong>.
     */
    public String whoAmI() throws IOException, InterruptedException {
        ByteArrayOutputStream userId = new ByteArrayOutputStream();
        launcher.launch().cmds("id", "-u").quiet(true).stdout(userId).start().joinWithTimeout(CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());

        ByteArrayOutputStream groupId = new ByteArrayOutputStream();
        launcher.launch().cmds("id", "-g").quiet(true).stdout(groupId).start().joinWithTimeout(CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());

        final String charsetName = Charset.defaultCharset().name();
        return String.format("%s:%s", userId.toString(charsetName).trim(), groupId.toString(charsetName).trim());

    }

    /**
     * Checks if this {@link DockerSwarmClient} instance is running inside a container and returns the id of the container
     * if so.
     *
     * @return an optional string containing the <strong>container id</strong>, or <strong>absent</strong> if
     * it isn't containerized.
     * @see <a href="http://stackoverflow.com/a/25729598/12916">Discussion</a>
     */
    public Optional<String> getContainerIdIfContainerized() throws IOException, InterruptedException {
        if (node == null) {
            return Optional.absent();
        }
        FilePath cgroupFile = node.createPath("/proc/self/cgroup");
        if (cgroupFile == null || !cgroupFile.exists()) {
            return Optional.absent();
        }
        return ControlGroup.getContainerId(cgroupFile);
    }

    public ContainerRecord getContainerRecord(@Nonnull EnvVars launchEnv, String serviceName, String containerId, String containerName, String containerHost) throws IOException, InterruptedException {
        //            String host = getDockerSwarmHostUri().replaceFirst("tcp://", "");//inspectRequiredField(launchEnv, serviceName, ".Config.Hostname");
//            String containerName = serviceName;//inspectRequiredField(launchEnv, serviceName, ".Name");
        Date created = getCreatedDate(launchEnv, serviceName);
        String imageLong = inspectService(launchEnv, serviceName, ".Spec.TaskTemplate.ContainerSpec.Image");
        // TODO get tags and add for ContainerRecord
        return new ContainerRecord(containerHost, containerId, getLastPart(imageLong, ":"), containerName,
            (created != null ? created.getTime() : 0L),
            Collections.emptyMap());
    }

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
    public List<String> getVolumes(@Nonnull EnvVars launchEnv, String containerID) throws IOException, InterruptedException {
        LaunchResult result = launch(launchEnv, false, "inspect", "-f", "{{range.Mounts}}{{.Destination}}\n{{end}}", containerID);
        if (result.getStatus() != 0) {
            return Collections.emptyList();
        }

        String volumes = result.getOut();
        if (volumes.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(volumes.split("\\n"));
    }
}
