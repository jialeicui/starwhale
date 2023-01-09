/*
 * Copyright 2022 Starwhale, Inc. All Rights Reserved.
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

package ai.starwhale.mlops.domain.job;

import ai.starwhale.mlops.api.protocol.job.ModelServingVo;
import ai.starwhale.mlops.common.DockerImage;
import ai.starwhale.mlops.common.IdConverter;
import ai.starwhale.mlops.configuration.RunTimeProperties;
import ai.starwhale.mlops.configuration.security.ModelServingTokenValidator;
import ai.starwhale.mlops.domain.job.mapper.ModelServingMapper;
import ai.starwhale.mlops.domain.job.po.ModelServingEntity;
import ai.starwhale.mlops.domain.job.status.JobStatus;
import ai.starwhale.mlops.domain.model.ModelDao;
import ai.starwhale.mlops.domain.model.mapper.ModelMapper;
import ai.starwhale.mlops.domain.model.po.ModelVersionEntity;
import ai.starwhale.mlops.domain.project.ProjectManager;
import ai.starwhale.mlops.domain.runtime.RuntimeDao;
import ai.starwhale.mlops.domain.runtime.mapper.RuntimeMapper;
import ai.starwhale.mlops.domain.runtime.po.RuntimeVersionEntity;
import ai.starwhale.mlops.domain.system.SystemSettingService;
import ai.starwhale.mlops.domain.user.UserService;
import ai.starwhale.mlops.domain.user.bo.User;
import ai.starwhale.mlops.exception.SwProcessException;
import ai.starwhale.mlops.schedule.k8s.K8sClient;
import ai.starwhale.mlops.schedule.k8s.K8sJobTemplate;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ModelServingService {
    private final ModelServingMapper modelServingMapper;
    private final UserService userService;
    private final ProjectManager projectManager;
    private final ModelDao modelDao;
    private final RuntimeDao runtimeDao;
    private final K8sClient k8sClient;
    private final K8sJobTemplate k8sJobTemplate;
    private final RuntimeMapper runtimeMapper;
    private final ModelMapper modelMapper;
    private final SystemSettingService systemSettingService;
    private final String instanceUri;
    private final RunTimeProperties runTimeProperties;
    private final ModelServingTokenValidator modelServingTokenValidator;
    private final IdConverter idConverter;

    private final long maxTtlSec;
    private final long minTtlSec;

    public static final String MODEL_SERVICE_PREFIX = "model-serving";
    private static final Pattern modelServingNamePattern = Pattern.compile(MODEL_SERVICE_PREFIX + "-(\\d+)");

    public ModelServingService(
            ModelServingMapper modelServingMapper,
            RuntimeDao runtimeDao,
            ProjectManager projectManager,
            ModelDao modelDao,
            UserService userService,
            K8sClient k8sClient,
            K8sJobTemplate k8sJobTemplate,
            RuntimeMapper runtimeMapper,
            ModelMapper modelMapper,
            SystemSettingService systemSettingService,
            RunTimeProperties runTimeProperties,
            @Value("${sw.instance-uri}") String instanceUri,
            ModelServingTokenValidator modelServingTokenValidator,
            IdConverter idConverter,
            @Value("${sw.online-eval.max-time-to-live-in-seconds}") long maxTtlSec,
            @Value("${sw.online-eval.min-time-to-live-in-seconds}") long minTtlSec
    ) {
        this.modelServingMapper = modelServingMapper;
        this.runtimeDao = runtimeDao;
        this.projectManager = projectManager;
        this.modelDao = modelDao;
        this.userService = userService;
        this.k8sClient = k8sClient;
        this.k8sJobTemplate = k8sJobTemplate;
        this.runtimeMapper = runtimeMapper;
        this.modelMapper = modelMapper;
        this.systemSettingService = systemSettingService;
        this.runTimeProperties = runTimeProperties;
        this.instanceUri = instanceUri;
        this.modelServingTokenValidator = modelServingTokenValidator;
        this.idConverter = idConverter;
        this.maxTtlSec = maxTtlSec;
        this.minTtlSec = minTtlSec;
    }

    public ModelServingVo create(
            String projectUrl,
            String modelVersionUrl,
            String runtimeVersionUrl,
            String resourcePool
    ) {
        User user = userService.currentUserDetail();
        Long projectId = projectManager.getProjectId(projectUrl);
        var runtime = runtimeDao.getRuntimeVersion(runtimeVersionUrl);
        var model = modelDao.getModelVersion(modelVersionUrl);

        var entity = ModelServingEntity.builder()
                .ownerId(user.getId())
                .runtimeVersionId(runtime.getId())
                .projectId(projectId)
                .modelVersionId(model.getId())
                .jobStatus(JobStatus.CREATED)
                .resourcePool(resourcePool)
                .lastVisitTime(new Date())
                .build();

        long id;
        synchronized (this) {
            modelServingMapper.insertIgnore(entity);

            var services = modelServingMapper.list(projectId, model.getId(), runtime.getId(), resourcePool);
            if (services.size() != 1) {
                // this can not happen
                throw new SwProcessException(SwProcessException.ErrorType.DB,
                        "duplicate entries, size " + services.size());
            }
            id = services.get(0).getId();
            // update last visit time, prevents garbage collected
            modelServingMapper.updateLastVisitTime(id, new Date());
        }

        log.info("Model serving job has been created. ID={}", id);

        try {
            deploy(runtime, model, projectId.toString(), user, id);
        } catch (ApiException e) {
            log.error(e.getResponseBody(), e);
            throw new SwProcessException(SwProcessException.ErrorType.SYSTEM, e.getResponseBody(), e);
        }

        var idStr = idConverter.convert(id);
        var uri = getServiceBaseUri(id);
        return ModelServingVo.builder().id(idStr).baseUri(uri).build();
    }

    private void deploy(
            RuntimeVersionEntity runtime,
            ModelVersionEntity model,
            String project,
            User owner,
            long id
    ) throws ApiException {
        var name = getServiceName(id);

        // TODO: refactor image generation
        var image = runtime.getImage();
        if (systemSettingService.getSystemSetting() != null
                && systemSettingService.getSystemSetting().getDockerSetting() != null
                && systemSettingService.getSystemSetting().getDockerSetting().getRegistry() != null
        ) {
            image = new DockerImage(image)
                    .resolve(systemSettingService.getSystemSetting().getDockerSetting().getRegistry());
        }


        var rt = runtimeMapper.find(runtime.getRuntimeId());
        var md = modelMapper.find(model.getModelId());

        var envs = Map.of(
                "SW_RUNTIME_VERSION", String.format("%s/version/%s", rt.getRuntimeName(), runtime.getId()),
                "SW_MODEL_VERSION", String.format("%s/version/%s", md.getModelName(), model.getId()),
                "SW_INSTANCE_URI", instanceUri,
                "SW_TOKEN", modelServingTokenValidator.getToken(owner, id),
                "SW_PROJECT", project,
                "SW_PYPI_INDEX_URL", runTimeProperties.getPypi().getIndexUrl(),
                "SW_PYPI_EXTRA_INDEX_URL", runTimeProperties.getPypi().getExtraIndexUrl(),
                "SW_PYPI_TRUSTED_HOST", runTimeProperties.getPypi().getTrustedHost(),
                "SW_MODEL_SERVING_BASE_URI", getServiceBaseUri(id),
                // see https://github.com/star-whale/starwhale/blob/c1d85ab98045a95ab3c75a89e7af56a17e966714/client/starwhale/utils/__init__.py#L51
                "SW_PRODUCTION", "1"
        );
        var ss = k8sJobTemplate.renderModelServingOrch(envs, image, name);
        try {
            ss = k8sClient.deployStatefulSet(ss);
        } catch (ApiException e) {
            if (e.getCode() != HttpServletResponse.SC_CONFLICT) {
                throw e;
            }
            // exists, ignore exception
            return;
        }

        var svc = new V1Service();
        var meta = new V1ObjectMeta();
        meta.name(name);
        svc.metadata(meta);
        var spec = new V1ServiceSpec();
        svc.spec(spec);
        var selector = Map.of(K8sJobTemplate.LABEL_APP, name);
        spec.selector(selector);
        var port = new V1ServicePort();
        port.name("model-serving-port");
        port.protocol("TCP");
        port.port(80);
        port.targetPort(new IntOrString(K8sJobTemplate.ONLINE_EVAL_PORT_IN_POD));
        spec.ports(List.of(port));

        // add owner reference for svc and we can just delete the stateful-set when gc is needed
        var ownerRef = new V1OwnerReference();
        ownerRef.name(name);
        ownerRef.kind(ss.getKind());
        ownerRef.apiVersion(ss.getApiVersion());
        Objects.requireNonNull(ss.getMetadata());
        ownerRef.uid(ss.getMetadata().getUid());
        meta.ownerReferences(List.of(ownerRef));

        // add svc to k8s
        k8sClient.deployService(svc);

        // if operations of svc failed, the gc thread will delete the zombie stateful-set,
        // so we do not need to delete the previous stateful-set when this fails
    }

    public static String getServiceName(long id) {
        return String.format("%s-%d", MODEL_SERVICE_PREFIX, id);
    }

    public static Long getServiceIdFromName(String name) {
        var match = modelServingNamePattern.matcher(name);
        if (match.matches()) {
            return Long.parseLong(match.group(1));
        }
        return null;
    }

    public static String getServiceBaseUri(long id) {
        return String.format("/gateway/%s/%d", MODEL_SERVICE_PREFIX, id);
    }


    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    public void gc() {
        try {
            synchronized (this) {
                internalGc();
            }
        } catch (ApiException e) {
            log.error("Failed to gc, code: {}, body: {}", e.getCode(), e.getResponseBody(), e);
        }
    }

    public void internalGc() throws ApiException {
        var labelSelector = K8sClient.toV1LabelSelector(Map.of(
                K8sJobTemplate.LABEL_WORKLOAD_TYPE,
                K8sJobTemplate.WORKLOAD_TYPE_ONLINE_EVAL
        ));
        var statefulSetList = k8sClient.getStatefulSetList(labelSelector);
        var podList = k8sClient.getPodList(labelSelector);
        var pods = new HashMap<String, V1Pod>();
        for (var pod : podList.getItems()) {
            if (pod.getMetadata() == null || pod.getMetadata().getOwnerReferences() == null) {
                continue;
            }
            for (var ownerRef : pod.getMetadata().getOwnerReferences()) {
                if (ownerRef.getKind() == null || !ownerRef.getKind().equals("StatefulSet")) {
                    continue;
                }
                var name = ownerRef.getName();
                if (StringUtils.isEmpty(name)) {
                    continue;
                }
                pods.put(name, pod);
                log.info("found pod {} for stateful set {}", pod.getMetadata().getName(), name);
            }
        }

        boolean hasPending = false;
        Map<Date, V1StatefulSet> mayBeGarbageCollected = new TreeMap<>((t1, t2) -> {
            // oldest at the beginning
            return t1 == t2 ? 0 : t1.before(t2) ? -1 : 1;
        });

        for (var statefulSet : statefulSetList.getItems()) {
            // check if the stateful set is outdated
            var meta = statefulSet.getMetadata();
            if (meta == null || StringUtils.isEmpty(meta.getName())) {
                continue;
            }
            // parse entity id from stateful set name
            var name = meta.getName();
            var id = getServiceIdFromName(name);
            if (id == null) {
                log.warn("can not get entity id from name {}", name);
                continue;
            }

            // check if the record in db
            var entity = modelServingMapper.find(id);
            if (entity == null) {
                // delete the unknown stateful set
                log.info("delete stateful set {} when there is no entry in db", name);
                deleteStatefulSet(name);
                continue;
            }

            var now = System.currentTimeMillis();
            var last = entity.getLastVisitTime().getTime();
            if (now - last > maxTtlSec * 1000) {
                log.info("delete stateful set {} when it reaches the max TTL (since {})", name, last);
                deleteStatefulSet(name);
                continue;
            }

            var status = statefulSet.getStatus();
            // check if the stateful set is pending
            if (status == null) {
                // may have just been deployed, ignore
                log.info("ignore stateful set {} (no status found)", name);
                continue;
            }

            // check if the pod is running
            var pod = pods.get(name);
            if (pod == null) {
                // maybe no enough resources
                log.info("stateful set {} has no matching pod", name);
                hasPending = true;
                continue;
            }
            var podStatus = pod.getStatus();
            // no status found or the status is pending
            if (podStatus == null || (podStatus.getPhase() != null && podStatus.getPhase().equals("Pending"))) {
                log.info("stateful set {} has pending pod", name);
                hasPending = true;
                continue;
            }

            // current replica of online eval is 1, so 0 means not ready
            if (status.getReadyReplicas() == null || status.getReadyReplicas() == 0) {
                log.info("found stateful set {} not ready, won't gc", name);
                continue;
            }

            if (now - last < minTtlSec * 1000) {
                log.info("stateful set {} not reach the TTL (since {}), won't gc", name, last);
                continue;
            }

            mayBeGarbageCollected.put(entity.getLastVisitTime(), statefulSet);
        }

        if (!hasPending) {
            log.info("no pending stateful set, done");
            return;
        }

        // kill the oldest stateful set
        if (mayBeGarbageCollected.isEmpty()) {
            log.info("no stateful set to gc");
            return;
        }
        var key = mayBeGarbageCollected.keySet().iterator().next();
        var oldest = mayBeGarbageCollected.get(key);
        var name = Objects.requireNonNull(oldest.getMetadata()).getName();
        deleteStatefulSet(name);
        log.info("delete stateful set {}", name);
    }

    private void deleteStatefulSet(String name) {
        try {
            k8sClient.deleteStatefulSet(name);
        } catch (ApiException e) {
            if (e.getCode() == HttpServletResponse.SC_NOT_FOUND) {
                log.info("stateful set {} not found", name);
                return;
            }
            log.error("delete stateful set {} failed, reason {}", name, e.getResponseBody(), e);
        }
    }
}
