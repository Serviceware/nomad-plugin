package org.jenkinsci.plugins.nomad;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import hudson.Util;
import okhttp3.*;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.nomad.Api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NomadApi {

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Logger LOGGER = Logger.getLogger(NomadApi.class.getName());
    private static final OkHttpClient client = new OkHttpClient();
    private final String nomadApi;

    NomadApi(String nomadApi) {
        this.nomadApi = nomadApi;
    }

    void startWorker(NomadCloud cloud, String workerName, String nomadToken, String jnlpSecret, NomadWorkerTemplate template) {

        String workerJob = buildWorkerJob(
                workerName,
                jnlpSecret,
                cloud,
                template
        );

        LOGGER.log(Level.FINE, workerJob);

        try {
            RequestBody body = RequestBody.create(JSON, workerJob);
            Request.Builder builder = new Request.Builder()
                    .url(this.nomadApi + "/v1/job/" + workerName + "?region=" + template.getRegion());

            if (StringUtils.isNotEmpty(nomadToken))
                builder = builder.header("X-Nomad-Token", nomadToken);

            Request request = builder.put(body)
                    .build();

            ResponseBody response = client.newCall(request).execute().body();
            if (response != null) {
                response.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    void stopWorker(String workerName, String nomadToken) {

        Request.Builder builder = new Request.Builder()
                .url(this.nomadApi + "/v1/job/" + workerName);

        if (StringUtils.isNotEmpty(nomadToken))
            builder = builder.addHeader("X-Nomad-Token", nomadToken);

        Request request = builder.delete()
                .build();

        try {
            ResponseBody response = client.newCall(request).execute().body();
            if (response != null) {
                response.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    JobInfo[] getRunningWorkers(String prefix, String nomadToken) {

        JobInfo[] nomadJobs = null;

        Request.Builder builder = new Request.Builder()
                .url(this.nomadApi + "/v1/jobs?prefix=" + prefix)
                .get();

        if (StringUtils.isNotEmpty(nomadToken))
            builder = builder.addHeader("X-Nomad-Token", nomadToken);

        Request request = builder.build();

        try {
            ResponseBody body = client.newCall(request).execute().body();

            if (body != null) {
                Gson gson = new Gson();

                nomadJobs = gson.fromJson(body.string(), JobInfo[].class);

                body.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve running jobs", e);
        }

        return nomadJobs;
    }

    private Map<String, Object> buildDriverConfig(String name, String secret, NomadCloud cloud, NomadWorkerTemplate template) {
        Map<String, Object> driverConfig = new HashMap<>();

        if (template.getUsername() != null && !template.getUsername().isEmpty()) {
            Map<String, String> authConfig = new HashMap<>();
            authConfig.put("username", template.getUsername());
            authConfig.put("password", template.getPassword());

            ArrayList<Map> credentials = new ArrayList<>();
            credentials.add(authConfig);

            driverConfig.put("auth", credentials);
        }

        ArrayList<String> args = new ArrayList<>();

        if (template.isJavaDriver()) {
            args.add("-jnlpUrl");

            args.add(Util.ensureEndsWith(cloud.getJenkinsUrl(), "/") + "computer/" + name + "/worker-agent.jnlp");

            // java -cp /local/slave.jar [options...] <secret key> <agent name>
            if (!secret.isEmpty()) {
                args.add("-secret");
                args.add(secret);
            }

            driverConfig.put("jar_path", "/local/slave.jar");
            driverConfig.put("args", args);
        } else if (template.isRawExecDriver()) {
            args.add("-jar");
            args.add("./local/slave.jar");

            args.add("-jnlpUrl");
            args.add(Util.ensureEndsWith(cloud.getJenkinsUrl(), "/") + "computer/" + name + "/worker-agent.jnlp");

            // java -cp /local/slave.jar [options...] <secret key> <agent name>
            if (!secret.isEmpty()) {
                args.add("-secret");
                args.add(secret);
            }

            driverConfig.put("command", "java");
            driverConfig.put("args", args);
        } else if (template.isDockerDriver()) {
            args.add("-headless");

            if (!cloud.getJenkinsUrl().isEmpty()) {
                args.add("-url");
                args.add(cloud.getJenkinsUrl());
            }

            if (!cloud.getJenkinsTunnel().isEmpty()) {
                args.add("-tunnel");
                args.add(cloud.getJenkinsTunnel());
            }

            if (!template.getRemoteFs().isEmpty()) {
                args.add("-workDir");
                args.add(Util.ensureEndsWith(template.getRemoteFs(), "/"));
            }

            // java -cp /local/slave.jar [options...] <secret key> <agent name>
            if (!secret.isEmpty()) {
                args.add(secret);
            }
            args.add(name);

            String prefixCmd = template.getPrefixCmd();
            // If an addtional command is defined - prepend it to jenkins worker invocation
            if (!prefixCmd.isEmpty()) {
                driverConfig.put("command", "/bin/bash");
                String argString =
                        prefixCmd + "; java -cp /local/slave.jar hudson.remoting.jnlp.Main -headless ";
                argString += StringUtils.join(args, " ");
                args.clear();
                args.add("-c");
                args.add(argString);
            } else {
                driverConfig.put("command", "java");
                args.add(0, "-cp");
                args.add(1, "/local/slave.jar");
                args.add(2, "hudson.remoting.jnlp.Main");
            }
            driverConfig.put("image", template.getImage());

            String hostVolumes = template.getHostVolumes();
            if (hostVolumes != null && !hostVolumes.isEmpty()) {
                driverConfig.put("volumes", StringUtils.split(hostVolumes, ","));
            }

            driverConfig.put("args", args);
            driverConfig.put("force_pull", template.getForcePull());
            driverConfig.put("privileged", template.getPrivileged());
            driverConfig.put("network_mode", template.getNetwork());

            String extraHosts = template.getExtraHosts();
            if (extraHosts != null && !extraHosts.isEmpty()) {
                driverConfig.put("extra_hosts", StringUtils.split(extraHosts, ", "));
            }

            String securityOpt = template.getSecurityOpt();
            if (securityOpt != null && !securityOpt.isEmpty()) {
                driverConfig.put("security_opt", StringUtils.split(securityOpt, ", "));
            }

            String capAdd = template.getCapAdd();
            if (capAdd != null && !capAdd.isEmpty()) {
                driverConfig.put("cap_add", StringUtils.split(capAdd, ", "));
            }

            String capDrop = template.getCapDrop();
            if (capDrop != null && !capDrop.isEmpty()) {
                driverConfig.put("cap_drop", StringUtils.split(capDrop, ", "));
            }
        }

        return driverConfig;
    }

    String buildWorkerJob(
            String name,
            String secret,
            NomadCloud cloud,
            NomadWorkerTemplate template
    ) {
        PortGroup portGroup = new PortGroup(template.getPorts());
        Network network = new Network(1, portGroup.getPorts());

        ArrayList<Network> networks = new ArrayList<>(1);
        networks.add(network);

        Task task = new Task(
                "jenkins-worker",
                template.getDriver(),
                template.getSwitchUser(),
                buildDriverConfig(name, secret, cloud, template),
                new Resource(
                        template.getCpu(),
                        template.getMemory(),
                        networks
                ),
                new LogConfig(1, 10),
                new Artifact[]{
                        new Artifact(cloud.getWorkerUrl(), null, "/local/")
                }
        );

        TaskGroup taskGroup = new TaskGroup(
                "jenkins-worker-taskgroup",
                1,
                new Task[]{task},
                new RestartPolicy(0, 10000000000L, 1000000000L, "fail"),
                new EphemeralDisk(template.getDisk(), false, false)
        );

        ConstraintGroup constraintGroup = new ConstraintGroup(template.getConstraints());
        List<Constraint> Constraints = constraintGroup.getConstraints();

        Job job = new Job(
                name,
                name,
                template.getRegion(),
                "batch",
                template.getPriority(),
                template.getDatacenters().split(","),
                Constraints,
                new TaskGroup[]{taskGroup}
        );

        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        JsonObject jobJson = new JsonObject();

        jobJson.add("Job", gson.toJsonTree(job));

        return gson.toJson(jobJson);
    }
}
