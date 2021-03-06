package io.choerodon.devops.app.service.impl;

import java.io.File;
import java.io.IOException;
import java.util.*;

import com.alibaba.fastjson.JSONArray;
import io.kubernetes.client.JSON;
import io.kubernetes.client.models.*;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.app.service.DeployMsgHandlerService;
import io.choerodon.devops.domain.application.entity.*;
import io.choerodon.devops.domain.application.factory.*;
import io.choerodon.devops.domain.application.repository.*;
import io.choerodon.devops.domain.application.valueobject.Job;
import io.choerodon.devops.domain.application.valueobject.Organization;
import io.choerodon.devops.domain.application.valueobject.ReleasePayload;
import io.choerodon.devops.infra.common.util.FileUtil;
import io.choerodon.devops.infra.common.util.K8sUtil;
import io.choerodon.devops.infra.common.util.TypeUtil;
import io.choerodon.devops.infra.common.util.enums.*;
import io.choerodon.devops.infra.config.HarborConfigurationProperties;
import io.choerodon.devops.infra.dataobject.DevopsEnvPodContainerDO;
import io.choerodon.devops.infra.dataobject.DevopsIngressDO;
import io.choerodon.devops.infra.mapper.DevopsIngressMapper;
import io.choerodon.websocket.tool.KeyParseTool;

/**
 * Created by Zenger on 2018/4/17.
 */
@Service
public class DeployMsgHandlerServiceImpl implements DeployMsgHandlerService {

    private static final String SERVICE_LABLE = "choerodon.io/network";
    private static final String PENDING = "Pending";
    private static final String METADATA = "metadata";
    private static final Logger logger = LoggerFactory.getLogger(DeployMsgHandlerServiceImpl.class);
    private static final String DESTPATH = "devops";
    private static JSON json = new JSON();
    private static ObjectMapper objectMapper = new ObjectMapper();
    @Value("${services.helm.url}")
    private String helmUrl;

    @Autowired
    private DevopsEnvPodRepository devopsEnvPodRepository;
    @Autowired
    private ApplicationInstanceRepository applicationInstanceRepository;
    @Autowired
    private DevopsEnvResourceRepository devopsEnvResourceRepository;
    @Autowired
    private DevopsEnvResourceDetailRepository devopsEnvResourceDetailRepository;
    @Autowired
    private DevopsServiceInstanceRepository devopsServiceInstanceRepository;
    @Autowired
    private DevopsServiceRepository devopsServiceRepository;
    @Autowired
    private DevopsEnvCommandLogRepository devopsEnvCommandLogRepository;
    @Autowired
    private DevopsIngressRepository devopsIngressRepository;
    @Autowired
    private DevopsEnvironmentRepository devopsEnvironmentRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ApplicationVersionRepository applicationVersionRepository;
    @Autowired
    private ApplicationVersionValueRepository applicationVersionValueRepository;
    @Autowired
    private IamRepository iamRepository;
    @Autowired
    private HarborConfigurationProperties harborConfigurationProperties;
    @Autowired
    private DevopsEnvPodContainerRepository containerRepository;
    @Autowired
    private DevopsEnvCommandRepository devopsEnvCommandRepository;
    @Autowired
    private DevopsEnvCommandValueRepository devopsEnvCommandValueRepository;
    @Autowired
    private DevopsIngressMapper devopsIngressMapper;

    /**
     * pod 更新
     */
    public void handlerUpdateMessage(String key, String msg) {
        V1Pod v1Pod = json.deserialize(msg, V1Pod.class);
        ApplicationInstanceE applicationInstanceE =
                applicationInstanceRepository.selectByCode(KeyParseTool.getReleaseName(key));
        if (applicationInstanceE == null) {
            return;
        }
        DevopsEnvResourceE devopsEnvResourceE = DevopsInstanceResourceFactory.createDevopsInstanceResourceE();
        DevopsEnvResourceE newDevopsEnvResourceE =
                devopsEnvResourceRepository.queryByInstanceIdAndKindAndName(
                        applicationInstanceE.getId(),
                        KeyParseTool.getResourceType(key),
                        v1Pod.getMetadata().getName());
        DevopsEnvResourceDetailE devopsEnvResourceDetailE = new DevopsEnvResourceDetailE();
        devopsEnvResourceDetailE.setMessage(msg);
        devopsEnvResourceE.setKind(KeyParseTool.getResourceType(key));
        devopsEnvResourceE.setName(v1Pod.getMetadata().getName());
        devopsEnvResourceE.setReversion(TypeUtil.objToLong(v1Pod.getMetadata().getResourceVersion()));
        List<V1OwnerReference> v1OwnerReferences = v1Pod.getMetadata().getOwnerReferences();
        if (v1OwnerReferences == null || v1OwnerReferences.isEmpty()) {
            return;
        }
        if (v1OwnerReferences.get(0).getKind().equals(ResourceType.JOB.getType())) {
            return;
        }
        saveOrUpdateResource(devopsEnvResourceE,
                newDevopsEnvResourceE,
                devopsEnvResourceDetailE,
                applicationInstanceE);
        if (v1OwnerReferences.get(0).getKind().equals(ResourceType.REPLICASET.getType())) {
            String status = K8sUtil.changePodStatus(v1Pod);
            String resourceVersion = v1Pod.getMetadata().getResourceVersion();

            DevopsEnvPodE devopsEnvPodE = new DevopsEnvPodE();
            devopsEnvPodE.setName(v1Pod.getMetadata().getName());
            devopsEnvPodE.setIp(v1Pod.getStatus().getPodIP());
            devopsEnvPodE.setStatus(status);
            devopsEnvPodE.setResourceVersion(resourceVersion);
            devopsEnvPodE.setNamespace(v1Pod.getMetadata().getNamespace());
            if (!PENDING.equals(status)) {
                devopsEnvPodE.setReady(v1Pod.getStatus().getContainerStatuses().get(0).isReady());
            } else {
                devopsEnvPodE.setReady(false);
            }

            Boolean flag = false;
            if (applicationInstanceE.getId() != null) {
                List<DevopsEnvPodE> devopsEnvPodEList = devopsEnvPodRepository
                        .selectByInstanceId(applicationInstanceE.getId());
                if (devopsEnvPodEList == null || devopsEnvPodEList.isEmpty()) {
                    devopsEnvPodE.initApplicationInstanceE(applicationInstanceE.getId());
                    devopsEnvPodRepository.insert(devopsEnvPodE);
                    Long podId = devopsEnvPodRepository.get(devopsEnvPodE).getId();
                    v1Pod.getSpec().getContainers().parallelStream().forEach(t ->
                            containerRepository.insert(new DevopsEnvPodContainerDO(
                                    podId,
                                    t.getName())));
                } else {
                    for (DevopsEnvPodE pod : devopsEnvPodEList) {
                        if (pod.getName().equals(v1Pod.getMetadata().getName())
                                && pod.getNamespace().equals(v1Pod.getMetadata().getNamespace())) {
                            if (!resourceVersion.equals(pod.getResourceVersion())) {
                                devopsEnvPodE.setId(pod.getId());
                                devopsEnvPodE.initApplicationInstanceE(pod.getApplicationInstanceE().getId());
                                devopsEnvPodE.setObjectVersionNumber(pod.getObjectVersionNumber());
                                devopsEnvPodRepository.update(devopsEnvPodE);
                                containerRepository.deleteByPodId(pod.getId());
                                v1Pod.getSpec().getContainers().parallelStream().forEach(t ->
                                        containerRepository.insert(
                                                new DevopsEnvPodContainerDO(pod.getId(), t.getName())));
                            }
                            flag = true;
                        }
                    }
                    if (!flag) {
                        devopsEnvPodE.initApplicationInstanceE(applicationInstanceE.getId());
                        devopsEnvPodRepository.insert(devopsEnvPodE);
                        Long podId = devopsEnvPodRepository.get(devopsEnvPodE).getId();
                        v1Pod.getSpec().getContainers().parallelStream().forEach(t ->
                                containerRepository.insert(new DevopsEnvPodContainerDO(
                                        podId,
                                        t.getName())));
                    }
                }
            }
        }
    }

    @Override
    public void handlerReleaseInstall(String msg) {
        Object object = null;
        try {
            object = objectMapper.readValue(msg, Object.class);
            LinkedHashMap linkedHashMap = (java.util.LinkedHashMap) ((LinkedHashMap) object).get("release");
            String releaseName = linkedHashMap.get("name").toString();
            List<Object> objects = (ArrayList) linkedHashMap.get("resources");
            ApplicationInstanceE applicationInstanceE = applicationInstanceRepository.selectByCode(releaseName);
            applicationInstanceE.setStatus(InstanceStatus.RUNNING.getStatus());
            applicationInstanceRepository.update(applicationInstanceE);
            DevopsEnvCommandE devopsEnvCommandE = devopsEnvCommandRepository
                    .queryByObject(ObjectType.INSTANCE.getObjectType(), applicationInstanceE.getId());
            devopsEnvCommandE.setStatus(CommandStatus.SUCCESS.getCommandStatus());
            devopsEnvCommandRepository.update(devopsEnvCommandE);
            for (Object obj : objects) {
                Object newObj = objectMapper.readValue(((LinkedHashMap) obj).get("object").toString(), Object.class);
                DevopsEnvResourceE newdevopsEnvResourceE =
                        devopsEnvResourceRepository.queryByInstanceIdAndKindAndName(
                                applicationInstanceE.getId(),
                                ((LinkedHashMap) obj).get("kind").toString(),
                                ((LinkedHashMap) obj).get("name").toString());
                DevopsEnvResourceDetailE devopsEnvResourceDetailE = new DevopsEnvResourceDetailE();
                devopsEnvResourceDetailE.setMessage(((LinkedHashMap) obj).get("object").toString());
                DevopsEnvResourceE devopsEnvResourceE =
                        DevopsInstanceResourceFactory.createDevopsInstanceResourceE();
                devopsEnvResourceE.setKind(((LinkedHashMap) obj).get("kind").toString());
                devopsEnvResourceE.setName(((LinkedHashMap) obj).get("name").toString());
                devopsEnvResourceE.setReversion(
                        TypeUtil.objToLong(((LinkedHashMap) ((LinkedHashMap) newObj)
                                .get(METADATA)).get("resourceVersion").toString()));
                saveOrUpdateResource(
                        devopsEnvResourceE,
                        newdevopsEnvResourceE,
                        devopsEnvResourceDetailE,
                        applicationInstanceE);
            }
        } catch (Exception e) {
            throw new CommonException("error.deploy.error");
        }
    }


    @Override
    public void handlerPreInstall(String msg) {
        List<Job> jobs = JSONArray.parseArray(msg, Job.class);
        try {
            for (Job job : jobs) {
                ApplicationInstanceE applicationInstanceE = applicationInstanceRepository
                        .selectByCode(job.getReleaseName());
                DevopsEnvResourceE newdevopsEnvResourceE =
                        devopsEnvResourceRepository.queryByInstanceIdAndKindAndName(
                                applicationInstanceE.getId(),
                                job.getKind(),
                                job.getName());
                DevopsEnvResourceE devopsEnvResourceE =
                        DevopsInstanceResourceFactory.createDevopsInstanceResourceE();
                devopsEnvResourceE.setKind(job.getKind());
                devopsEnvResourceE.setName(job.getName());
                devopsEnvResourceE.setWeight(
                        TypeUtil.objToLong(job.getWeight()));
                DevopsEnvResourceDetailE devopsEnvResourceDetailE = new DevopsEnvResourceDetailE();
                devopsEnvResourceDetailE.setMessage(
                        FileUtil.yamlStringtoJson(job.getManifest()));
                saveOrUpdateResource(
                        devopsEnvResourceE,
                        newdevopsEnvResourceE,
                        devopsEnvResourceDetailE,
                        applicationInstanceE);
            }
        } catch (Exception e) {
            throw new CommonException("error.resource.insert");
        }
    }

    @Override
    public void resourceUpdate(String key, String msg) {
        try {
            Object obj = objectMapper.readValue(msg, Object.class);
            DevopsEnvResourceE devopsEnvResourceE =
                    DevopsInstanceResourceFactory.createDevopsInstanceResourceE();
            DevopsEnvResourceDetailE devopsEnvResourceDetailE = new DevopsEnvResourceDetailE();
            devopsEnvResourceDetailE.setMessage(msg);
            devopsEnvResourceE.setKind(KeyParseTool.getResourceType(key));
            devopsEnvResourceE.setName(
                    KeyParseTool.getResourceName(key));
            devopsEnvResourceE.setReversion(
                    TypeUtil.objToLong(
                            ((LinkedHashMap) ((LinkedHashMap) obj).get(METADATA)).get("resourceVersion").toString()));
            String releaseName = null;
            DevopsEnvResourceE newdevopsEnvResourceE = null;
            ApplicationInstanceE applicationInstanceE = null;
            ResourceType resourceType = ResourceType.forString(KeyParseTool.getResourceType(key));
            switch (resourceType) {
                case INGRESS:
                    V1beta1Ingress v1beta1Ingress = json.deserialize(msg, V1beta1Ingress.class);
                    Map<String, String> label = v1beta1Ingress.getMetadata().getLabels();
                    if (label.get(SERVICE_LABLE) != null
                            && label.get(SERVICE_LABLE).equals("ingress")) {
                        DevopsEnvironmentE devopsEnvironmentE = devopsEnvironmentRepository.queryByNamespace(
                                v1beta1Ingress.getMetadata().getNamespace());
                        if (devopsEnvironmentE == null) {
                            return;
                        }
                        DevopsIngressE devopsIngressE = devopsIngressRepository.selectByEnvAndName(
                                devopsEnvironmentE.getId(), v1beta1Ingress.getMetadata().getName());
                        if (devopsIngressE == null) {
                            devopsIngressE = new DevopsIngressE();
                            devopsIngressE.setProjectId(devopsEnvironmentE.getProjectE().getId());
                            devopsIngressE.setDomain(v1beta1Ingress.getSpec().getRules().get(0).getHost());
                            devopsIngressE.setEnvId(devopsEnvironmentE.getId());
                            devopsIngressE.setName(v1beta1Ingress.getMetadata().getName());
                            devopsIngressE.setUsable(true);
                            devopsIngressE = devopsIngressRepository.insertIngress(devopsIngressE);

                            List<V1beta1HTTPIngressPath> paths = v1beta1Ingress.getSpec().getRules()
                                    .get(0).getHttp().getPaths();
                            for (V1beta1HTTPIngressPath path : paths) {
                                DevopsIngressPathE devopsIngressPathE = new DevopsIngressPathE();
                                devopsIngressPathE.setDevopsIngressE(devopsIngressE);
                                devopsIngressPathE.setServiceName(path.getBackend().getServiceName());
                                DevopsServiceE devopsServiceE = devopsServiceRepository
                                        .selectByNameAndNamespace(path.getBackend().getServiceName(),
                                                v1beta1Ingress.getMetadata().getNamespace());
                                if (devopsServiceE != null) {
                                    devopsIngressPathE.setServiceId(devopsServiceE.getId());
                                }
                                devopsIngressPathE.setPath(path.getPath());
                                devopsIngressRepository.insertIngressPath(devopsIngressPathE);
                            }
                        }
                    }

                    newdevopsEnvResourceE =
                            devopsEnvResourceRepository.queryByInstanceIdAndKindAndName(
                                    null,
                                    KeyParseTool.getResourceType(key),
                                    KeyParseTool.getResourceName(key));
                    saveOrUpdateResource(devopsEnvResourceE, newdevopsEnvResourceE,
                            devopsEnvResourceDetailE, null);
                    break;
                case POD:
                    handlerUpdateMessage(key, msg);
                    break;
                case SERVICE:
                    V1Service v1Service = json.deserialize(msg, V1Service.class);
                    String releaseNames = v1Service.getMetadata().getAnnotations().get("choerodon.io/network-service-instances");
                    List<String> releases = Arrays.asList(releaseNames.split("\\+"));

                    Boolean flag = false;
                    Map<String, String> lab = v1Service.getMetadata().getLabels();
                    if (lab.get(SERVICE_LABLE) != null && lab.get(SERVICE_LABLE).equals("service")) {
                        flag = true;
                    }
                    for (String release : releases) {
                        applicationInstanceE = applicationInstanceRepository
                                .selectByCode(release);

                        String namespace = v1Service.getMetadata().getNamespace();
                        if (flag) {
                            DevopsServiceE devopsServiceE = devopsServiceRepository.selectByNameAndNamespace(
                                    v1Service.getMetadata().getName(), namespace);
                            if (devopsServiceE == null) {
                                devopsServiceE = new DevopsServiceE();
                                devopsServiceE.setEnvId(applicationInstanceE.getDevopsEnvironmentE().getId());
                                devopsServiceE.setAppId(applicationInstanceE.getApplicationE().getId());
                                devopsServiceE.setName(KeyParseTool.getResourceName(key));
                                devopsServiceE.setNamespace(namespace);
                                devopsServiceE.setStatus(ServiceStatus.RUNNING.getStatus());
                                devopsServiceE.setPort(v1Service.getSpec().getPorts().get(0).getPort().longValue());
                                devopsServiceE.setTargetPort(v1Service.getSpec().getPorts().get(0)
                                        .getTargetPort().getIntValue().longValue());
                                if (v1Service.getSpec().getExternalIPs() != null) {
                                    devopsServiceE.setExternalIp(v1Service.getSpec().getExternalIPs().get(0));
                                }
                                devopsServiceE.setLabel(json.serialize(lab));
                                devopsServiceE = devopsServiceRepository.insert(devopsServiceE);

                                DevopsServiceAppInstanceE devopsServiceAppInstanceE = devopsServiceInstanceRepository
                                        .queryByOptions(devopsServiceE.getId(), applicationInstanceE.getId());
                                if (devopsServiceAppInstanceE == null) {
                                    devopsServiceAppInstanceE = new DevopsServiceAppInstanceE();
                                    devopsServiceAppInstanceE.setServiceId(devopsServiceE.getId());
                                    devopsServiceAppInstanceE.setAppInstanceId(applicationInstanceE.getId());
                                    devopsServiceAppInstanceE.setCode(release);
                                    devopsServiceInstanceRepository.insert(devopsServiceAppInstanceE);
                                }

                                DevopsEnvCommandE devopsEnvCommandE = DevopsEnvCommandFactory.createDevopsEnvCommandE();
                                devopsEnvCommandE.setObject(ObjectType.SERVICE.getObjectType());
                                devopsEnvCommandE.setObjectId(devopsServiceE.getId());
                                devopsEnvCommandE.setCommandType(CommandType.CREATE.getCommandType());
                                devopsEnvCommandE.setStatus(CommandStatus.DOING.getCommandStatus());
                                devopsEnvCommandRepository.create(devopsEnvCommandE);
                            }

                            List<DevopsIngressPathE> devopsIngressPathEList = devopsIngressRepository.selectByEnvIdAndServiceName(
                                    devopsServiceE.getEnvId(), devopsServiceE.getName());
                            for (DevopsIngressPathE dd : devopsIngressPathEList) {
                                if (dd.getServiceId() == null) {
                                    dd.setServiceId(devopsServiceE.getId());
                                    devopsIngressRepository.updateIngressPath(dd);
                                }
                            }
                        }
                    }
                    break;
                default:
                    releaseName = KeyParseTool.getReleaseName(key);
                    applicationInstanceE = applicationInstanceRepository.selectByCode(releaseName);
                    DevopsEnvResourceE newdevopsInsResourceE =
                            devopsEnvResourceRepository.queryByInstanceIdAndKindAndName(
                                    applicationInstanceE.getId(),
                                    KeyParseTool.getResourceType(key),
                                    KeyParseTool.getResourceName(key));
                    saveOrUpdateResource(devopsEnvResourceE, newdevopsInsResourceE,
                            devopsEnvResourceDetailE, applicationInstanceE);
                    break;
            }
        } catch (IOException e) {
            logger.info(e.toString());
        }
    }

    @Override
    public void resourceDelete(String msg) {
        if (!KeyParseTool.getResourceType(msg).equals(ResourceType.JOB.getType())) {
            if (KeyParseTool.getResourceType(msg).equals(ResourceType.POD.getType())) {
                String podName = KeyParseTool.getResourceName(msg);
                String podNameSpace = KeyParseTool.getNamespace(msg);
                DevopsEnvPodE podE = devopsEnvPodRepository.get(new DevopsEnvPodE(podName, podNameSpace));
                devopsEnvPodRepository.deleteByName(podName, podNameSpace);
                if (podE != null) {
                    containerRepository.deleteByPodId(podE.getId());
                }
            }

            if (KeyParseTool.getResourceType(msg).equals(ResourceType.SERVICE.getType())) {
                DevopsServiceE devopsServiceE =
                        devopsServiceRepository.selectByNameAndNamespace(
                                KeyParseTool.getResourceName(msg),
                                KeyParseTool.getNamespace(msg));
                //更新网络数据
                if (devopsServiceE != null) {
                    devopsServiceE.setStatus(ServiceStatus.DELETED.getStatus());
                    devopsServiceRepository.update(devopsServiceE);

                    DevopsEnvCommandE newdevopsEnvCommandE = devopsEnvCommandRepository
                            .queryByObject(ObjectType.SERVICE.getObjectType(), devopsServiceE.getId());
                    newdevopsEnvCommandE.setStatus(CommandStatus.SUCCESS.getCommandStatus());
                    devopsEnvCommandRepository.update(newdevopsEnvCommandE);
                }
            }
            if (KeyParseTool.getResourceType(msg).equals(ResourceType.INGRESS.getType())) {
                DevopsEnvironmentE devopsEnvironmentE = devopsEnvironmentRepository.queryByNamespace(
                        KeyParseTool.getNamespace(msg));
                DevopsIngressE devopsIngressE = devopsIngressRepository.selectByEnvAndName(
                        devopsEnvironmentE.getId(), KeyParseTool.getResourceName(msg));
                if (devopsIngressE != null) {
                    devopsIngressRepository.deleteIngress(devopsIngressE.getId());
                    devopsIngressRepository.deleteIngressPath(devopsIngressE.getId());
                }
                DevopsIngressDO domainDO = devopsIngressMapper
                        .select(new DevopsIngressDO(KeyParseTool.getResourceName(msg))).get(0);
                DevopsEnvCommandE newdevopsEnvCommandE = devopsEnvCommandRepository
                        .queryByObject(ObjectType.INGRESS.getObjectType(), domainDO.getId());
                newdevopsEnvCommandE.setStatus(CommandStatus.SUCCESS.getCommandStatus());
                devopsEnvCommandRepository.update(newdevopsEnvCommandE);
            }

            devopsEnvResourceRepository.deleteByKindAndName(
                    KeyParseTool.getResourceType(msg),
                    KeyParseTool.getResourceName(msg));
        }
    }

    @Override
    public void helmReleaseHookLogs(String key, String msg) {
        ApplicationInstanceE applicationInstanceE = applicationInstanceRepository
                .selectByCode(KeyParseTool.getReleaseName(key));
        DevopsEnvCommandE devopsEnvCommandE = devopsEnvCommandRepository
                .queryByObject(ObjectType.INSTANCE.getObjectType(), applicationInstanceE.getId());
        DevopsEnvCommandLogE devopsEnvCommandLogE = new DevopsEnvCommandLogE();
        devopsEnvCommandLogE.initDevopsEnvCommandE(devopsEnvCommandE.getId());
        devopsEnvCommandLogE.setLog(msg);
        devopsEnvCommandLogRepository.create(devopsEnvCommandLogE);
    }

    @Override
    public void updateInstanceStatus(String key, String instanceStatus, String commandStatus, String msg) {
        ApplicationInstanceE instanceE = applicationInstanceRepository.selectByCode(key);
        if (instanceE != null) {
            instanceE.setStatus(instanceStatus);
            applicationInstanceRepository.update(instanceE);
            DevopsEnvCommandE devopsEnvCommandE = devopsEnvCommandRepository
                    .queryByObject(ObjectType.INSTANCE.getObjectType(), instanceE.getId());
            devopsEnvCommandE.setStatus(commandStatus);
            devopsEnvCommandE.setError(msg);
            devopsEnvCommandRepository.update(devopsEnvCommandE);
        }
    }

    @Override
    public void handlerDomainCreateMessage(String key, String msg) {
        V1beta1Ingress ingress = json.deserialize(msg, V1beta1Ingress.class);
        DevopsEnvResourceE devopsEnvResourceE = DevopsInstanceResourceFactory.createDevopsInstanceResourceE();
        DevopsEnvResourceDetailE devopsEnvResourceDetailE = new DevopsEnvResourceDetailE();
        devopsEnvResourceDetailE.setMessage(msg);
        devopsEnvResourceE.setKind(KeyParseTool.getResourceType(key));
        devopsEnvResourceE.setName(KeyParseTool.getResourceName(key));
        devopsEnvResourceE.setReversion(TypeUtil.objToLong(ingress.getMetadata().getResourceVersion()));
        DevopsEnvResourceE newDevopsEnvResourceE =
                devopsEnvResourceRepository.queryByInstanceIdAndKindAndName(
                        null,
                        KeyParseTool.getResourceType(key),
                        KeyParseTool.getResourceName(key));

        saveOrUpdateResource(devopsEnvResourceE, newDevopsEnvResourceE, devopsEnvResourceDetailE, null);
        devopsIngressRepository.setUsable(ingress.getMetadata().getName());
    }

    @Override
    public void helmReleasePreUpgrade(String msg) {
        handlerPreInstall(msg);
    }

    @Override
    public void handlerReleaseUpgrade(String msg) {
        handlerReleaseInstall(msg);
    }

    @Override
    public void helmRelease(String msg) {
        List<ReleasePayload> releasePayloads = JSONArray.parseArray(msg, ReleasePayload.class);
        for (ReleasePayload releasePayload : releasePayloads) {
            ApplicationVersionValueE applicationVersionValueE = ApplicationVersionValueFactory.create();
            ApplicationVersionE applicationVersionE = ApplicationVersionEFactory.create();
            if (applicationInstanceRepository.selectByCode(releasePayload.getName()) == null) {
                try {
                    ApplicationInstanceE applicationInstanceE = ApplicationInstanceFactory.create();
                    DevopsEnvironmentE devopsEnvironmentE = devopsEnvironmentRepository
                            .queryByNamespace(releasePayload.getNamespace());
                    ProjectE projectE = iamRepository.queryIamProject(devopsEnvironmentE.getProjectE().getId());
                    Organization organization = iamRepository.queryOrganizationById(projectE.getOrganization().getId());
                    ApplicationE applicationE = applicationRepository
                            .queryByCode(releasePayload.getChartName(), devopsEnvironmentE.getProjectE().getId());
                    applicationVersionE.initApplicationEById(applicationE.getId());
                    String image = String.format("%s%s%s%s%s%s%s%s%s", harborConfigurationProperties.getBaseUrl(),
                            System.getProperty("file.separator"),
                            organization.getCode(),
                            "-",
                            projectE.getCode(),
                            System.getProperty("file.separator"),
                            applicationE.getCode(),
                            ":",
                            releasePayload.getChartVersion()
                    );
                    applicationVersionE.setImage(image);
                    applicationVersionE.setVersion(releasePayload.getChartVersion());
                    applicationVersionE
                            .setRepository("/" + organization.getCode() + "/" + projectE.getCode() + "/");
                    String classPath = String.format("Charts%s%s%s%s%s%s%s%s%s",
                            System.getProperty("file.separator"),
                            organization.getCode(),
                            System.getProperty("file.separator"),
                            projectE.getCode(),
                            System.getProperty("file.separator"),
                            releasePayload.getChartName(),
                            "-",
                            releasePayload.getChartVersion(),
                            ".tgz");
                    FileUtil.unTarGZ(classPath, DESTPATH);
                    String value = FileUtil.yamltoJson(
                            FileUtil.queryFileFromFiles(
                                    new File(DESTPATH), "values.yaml").getAbsolutePath());
                    if (releasePayload.getConfig() != null) {
                        value = FileUtil.mergeJsonString(value, FileUtil.yamlStringtoJson(releasePayload.getConfig()));
                    }
                    applicationVersionValueE.setValue(value);
                    applicationVersionE.initApplicationVersionValueE(
                            applicationVersionValueRepository.create(applicationVersionValueE).getId());
                    applicationInstanceE.setCode(releasePayload.getName());
                    applicationInstanceE.setStatus(InstanceStatus.RUNNING.getStatus());
                    applicationInstanceE.initApplicationEById(applicationE.getId());
                    ApplicationVersionE newApplicationVersionE = applicationVersionRepository
                            .queryByAppAndVersion(applicationE.getId(), releasePayload.getChartVersion());
                    DevopsEnvCommandE devopsEnvCommandE = new DevopsEnvCommandE();
                    if (newApplicationVersionE == null) {
                        applicationInstanceE.initApplicationVersionEById(
                                applicationVersionRepository.create(applicationVersionE).getId());
                    } else {
                        applicationInstanceE.initApplicationVersionEById(newApplicationVersionE.getId());
                    }
                    applicationInstanceE.initDevopsEnvironmentEById(devopsEnvironmentE.getId());
                    DevopsEnvCommandValueE devopsEnvCommandValueE = DevopsEnvCommandValueFactory
                            .createDevopsEnvCommandE();
                    devopsEnvCommandValueE.setValue(value);
                    devopsEnvCommandE.setObject(ObjectType.INSTANCE.getObjectType());
                    devopsEnvCommandE.setCommandType(CommandType.CREATE.getCommandType());
                    devopsEnvCommandE.setObjectId(applicationInstanceRepository.create(applicationInstanceE).getId());
                    devopsEnvCommandE.setStatus(CommandStatus.SUCCESS.getCommandStatus());
                    devopsEnvCommandE.initDevopsEnvCommandValueE(
                            devopsEnvCommandValueRepository.create(devopsEnvCommandValueE).getId());
                    devopsEnvCommandRepository.create(devopsEnvCommandE);
                    FileUtil.deleteFile(new File(DESTPATH));
                } catch (Exception e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(releasePayload.getChartName() + "Release Back Error:" + e.getMessage());
                    }
                    continue;
                }
            }
        }
    }

    @Override
    public void helmReleaseDeleteFail(String key, String msg) {
        updateInstanceStatus(KeyParseTool.getReleaseName(key),
                InstanceStatus.DELETED.getStatus(),
                CommandStatus.FAILED.getCommandStatus(),
                msg);
    }

    @Override
    public void helmReleaseStartFail(String key, String msg) {
        updateInstanceStatus(KeyParseTool.getReleaseName(key),
                InstanceStatus.STOPED.getStatus(),
                CommandStatus.FAILED.getCommandStatus(),
                msg);
    }

    @Override
    public void helmReleaseRollBackFail(String key, String msg) {
    }

    @Override
    public void helmReleaseInstallFail(String key, String msg) {
        updateInstanceStatus(KeyParseTool.getReleaseName(key),
                InstanceStatus.FAILED.getStatus(),
                CommandStatus.FAILED.getCommandStatus(),
                msg);
    }

    @Override
    public void helmReleaseUpgradeFail(String key, String msg) {
        updateInstanceStatus(KeyParseTool.getReleaseName(key),
                InstanceStatus.RUNNING.getStatus(),
                CommandStatus.FAILED.getCommandStatus(),
                msg);
    }

    @Override
    public void helmReleaeStopFail(String key, String msg) {
        updateInstanceStatus(KeyParseTool.getReleaseName(key),
                InstanceStatus.RUNNING.getStatus(),
                CommandStatus.FAILED.getCommandStatus(),
                msg);

    }

    @Override
    public void netWorkUpdate(String key, String msg) {
        V1Service v1Service = json.deserialize(msg, V1Service.class);

        String releaseNames = v1Service.getMetadata().getAnnotations().get("choerodon.io/network-service-instances");
        List<String> releases = Arrays.asList(releaseNames.split("\\+"));

        DevopsEnvResourceE devopsEnvResourceE =
                DevopsInstanceResourceFactory.createDevopsInstanceResourceE();
        DevopsEnvResourceDetailE devopsEnvResourceDetailE = new DevopsEnvResourceDetailE();
        devopsEnvResourceDetailE.setMessage(msg);
        devopsEnvResourceE.setKind(KeyParseTool.getResourceType(key));
        devopsEnvResourceE.setName(
                KeyParseTool.getResourceName(key));
        devopsEnvResourceE.setReversion(
                TypeUtil.objToLong(v1Service.getMetadata().getResourceVersion()));

        Boolean flag = false;
        Map<String, String> label = v1Service.getMetadata().getLabels();
        if (label.get(SERVICE_LABLE) != null && label.get(SERVICE_LABLE).equals("service")) {
            flag = true;
        }
        for (String release : releases) {
            ApplicationInstanceE applicationInstanceE = applicationInstanceRepository
                    .selectByCode(release);

            String namespace = v1Service.getMetadata().getNamespace();
            if (flag) {
                DevopsServiceE devopsServiceE = devopsServiceRepository.selectByNameAndNamespace(
                        v1Service.getMetadata().getName(), namespace);
                if (devopsServiceE == null) {
                    devopsServiceE = new DevopsServiceE();
                    devopsServiceE.setEnvId(applicationInstanceE.getDevopsEnvironmentE().getId());
                    devopsServiceE.setAppId(applicationInstanceE.getApplicationE().getId());
                    devopsServiceE.setName(KeyParseTool.getResourceName(key));
                    devopsServiceE.setNamespace(namespace);
                    devopsServiceE.setStatus(ServiceStatus.RUNNING.getStatus());
                    devopsServiceE.setPort(v1Service.getSpec().getPorts().get(0).getPort().longValue());
                    devopsServiceE.setTargetPort(v1Service.getSpec().getPorts().get(0)
                            .getTargetPort().getIntValue().longValue());
                    if (v1Service.getSpec().getExternalIPs() != null) {
                        devopsServiceE.setExternalIp(v1Service.getSpec().getExternalIPs().get(0));
                    }
                    devopsServiceE.setLabel(json.serialize(label));
                    devopsServiceE = devopsServiceRepository.insert(devopsServiceE);

                    DevopsServiceAppInstanceE devopsServiceAppInstanceE = devopsServiceInstanceRepository
                            .queryByOptions(devopsServiceE.getId(), applicationInstanceE.getId());
                    if (devopsServiceAppInstanceE == null) {
                        devopsServiceAppInstanceE = new DevopsServiceAppInstanceE();
                        devopsServiceAppInstanceE.setServiceId(devopsServiceE.getId());
                        devopsServiceAppInstanceE.setAppInstanceId(applicationInstanceE.getId());
                        devopsServiceAppInstanceE.setCode(release);
                        devopsServiceInstanceRepository.insert(devopsServiceAppInstanceE);
                    }

                    DevopsEnvCommandE devopsEnvCommandE = DevopsEnvCommandFactory.createDevopsEnvCommandE();
                    devopsEnvCommandE.setObject(ObjectType.SERVICE.getObjectType());
                    devopsEnvCommandE.setObjectId(devopsServiceE.getId());
                    devopsEnvCommandE.setCommandType(CommandType.CREATE.getCommandType());
                    devopsEnvCommandE.setStatus(CommandStatus.DOING.getCommandStatus());
                    devopsEnvCommandRepository.create(devopsEnvCommandE);
                }

                List<DevopsIngressPathE> devopsIngressPathEList = devopsIngressRepository.selectByEnvIdAndServiceName(
                        devopsServiceE.getEnvId(), devopsServiceE.getName());
                for (DevopsIngressPathE dd : devopsIngressPathEList) {
                    if (dd.getServiceId() == null) {
                        dd.setServiceId(devopsServiceE.getId());
                        devopsIngressRepository.updateIngressPath(dd);
                    }
                }
            }

            DevopsEnvResourceE newdevopsEnvResourceE = devopsEnvResourceRepository
                    .queryByInstanceIdAndKindAndName(
                            applicationInstanceE.getId(),
                            KeyParseTool.getResourceType(key),
                            KeyParseTool.getResourceName(key));
            saveOrUpdateResource(devopsEnvResourceE,
                    newdevopsEnvResourceE,
                    devopsEnvResourceDetailE,
                    applicationInstanceE);
        }
    }

    private void saveOrUpdateResource(DevopsEnvResourceE devopsEnvResourceE,
                                      DevopsEnvResourceE newdevopsEnvResourceE,
                                      DevopsEnvResourceDetailE devopsEnvResourceDetailE,
                                      ApplicationInstanceE applicationInstanceE) {
        if (newdevopsEnvResourceE == null) {
            devopsEnvResourceE.initDevopsInstanceResourceMessageE(
                    devopsEnvResourceDetailRepository.create(devopsEnvResourceDetailE).getId());
            if (!devopsEnvResourceE.getKind().equals(ResourceType.INGRESS.getType())) {
                devopsEnvResourceE.initApplicationInstanceE(applicationInstanceE.getId());
            }
            devopsEnvResourceRepository.create(devopsEnvResourceE);
            return;
        }
        if (newdevopsEnvResourceE.getReversion() == null) {
            newdevopsEnvResourceE.setReversion(0L);
        }
        if (devopsEnvResourceE.getReversion() == null) {
            devopsEnvResourceE.setReversion(0L);
        }
        if (!newdevopsEnvResourceE.getReversion().equals(devopsEnvResourceE.getReversion())) {
            newdevopsEnvResourceE.setReversion(devopsEnvResourceE.getReversion());
            devopsEnvResourceDetailE.setId(
                    newdevopsEnvResourceE.getDevopsEnvResourceDetailE().getId());
            devopsEnvResourceRepository.update(newdevopsEnvResourceE);
            devopsEnvResourceDetailRepository.update(devopsEnvResourceDetailE);
        }
    }
}
