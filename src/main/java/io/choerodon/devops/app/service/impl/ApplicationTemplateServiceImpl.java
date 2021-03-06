package io.choerodon.devops.app.service.impl;

import java.util.List;

import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.choerodon.core.convertor.ConvertHelper;
import io.choerodon.core.convertor.ConvertPageHelper;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.api.dto.ApplicationTemplateDTO;
import io.choerodon.devops.api.dto.ApplicationTemplateRepDTO;
import io.choerodon.devops.api.dto.ApplicationTemplateUpdateDTO;
import io.choerodon.devops.api.dto.GitlabProjectEventDTO;
import io.choerodon.devops.api.validator.ApplicationTemplateValidator;
import io.choerodon.devops.app.service.ApplicationTemplateService;
import io.choerodon.devops.domain.application.entity.ApplicationTemplateE;
import io.choerodon.devops.domain.application.entity.gitlab.GitlabGroupE;
import io.choerodon.devops.domain.application.event.GitlabProjectPayload;
import io.choerodon.devops.domain.application.factory.ApplicationTemplateFactory;
import io.choerodon.devops.domain.application.repository.ApplicationTemplateRepository;
import io.choerodon.devops.domain.application.repository.GitlabRepository;
import io.choerodon.devops.domain.application.repository.IamRepository;
import io.choerodon.devops.domain.application.valueobject.Organization;
import io.choerodon.devops.infra.common.util.GitUserNameUtil;
import io.choerodon.devops.infra.common.util.GitUtil;
import io.choerodon.devops.infra.common.util.TypeUtil;
import io.choerodon.devops.infra.common.util.enums.Visibility;
import io.choerodon.event.producer.execute.EventProducerTemplate;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;

/**
 * Created by younger on 2018/3/27.
 */

@Service
public class ApplicationTemplateServiceImpl implements ApplicationTemplateService {

    private static final String TEMPLATE = "template";
    @Value("${spring.application.name}")
    private String applicationName;
    @Value("${services.gitlab.url}")
    private String gitlabUrl;

    private IamRepository iamRepository;
    private GitlabRepository gitlabRepository;
    private EventProducerTemplate eventProducerTemplate;
    private ApplicationTemplateRepository applicationTemplateRepository;
    private GitUtil gitUtil;


    /**
     * 构造方法
     */
    public ApplicationTemplateServiceImpl(GitUtil gitUtil, IamRepository iamRepository, GitlabRepository gitlabRepository, EventProducerTemplate eventProducerTemplate, ApplicationTemplateRepository applicationTemplateRepository) {
        this.gitUtil = gitUtil;
        this.iamRepository = iamRepository;
        this.gitlabRepository = gitlabRepository;
        this.eventProducerTemplate = eventProducerTemplate;
        this.applicationTemplateRepository = applicationTemplateRepository;
    }

    @Override
    public ApplicationTemplateRepDTO create(ApplicationTemplateDTO applicationTemplateDTO, Long organizationId) {
        ApplicationTemplateValidator.checkApplicationTemplate(applicationTemplateDTO);
        ApplicationTemplateE applicationTemplateE = ConvertHelper.convert(
                applicationTemplateDTO, ApplicationTemplateE.class);
        applicationTemplateRepository.checkCode(applicationTemplateE);
        applicationTemplateRepository.checkName(applicationTemplateE);
        Integer gitlabGroupId;

        Organization organization = iamRepository.queryOrganizationById(organizationId);
        applicationTemplateE.initOrganization(organization.getId());
        GitlabGroupE gitlabGroupE = gitlabRepository.queryGroupByName(organization.getCode() + "_" + TEMPLATE);
        if (gitlabGroupE == null) {
            GitlabGroupE gitlabGroupENew = new GitlabGroupE();
            gitlabGroupENew.initName(organization.getCode() + "_" + TEMPLATE);
            gitlabGroupENew.initPath(organization.getCode() + "_" + TEMPLATE);
            gitlabGroupENew.initVisibility(Visibility.PUBLIC);
            gitlabGroupId = gitlabRepository.createGroup(gitlabGroupENew).getId();
        } else {
            gitlabGroupId = gitlabGroupE.getId();
        }
        GitlabProjectPayload gitlabProjectPayload = new GitlabProjectPayload();
        gitlabProjectPayload.setGroupId(gitlabGroupId);
        gitlabProjectPayload.setUserName(GitUserNameUtil.getUsername());
        gitlabProjectPayload.setPath(applicationTemplateDTO.getCode());
        gitlabProjectPayload.setOrganizationId(organization.getId());
        gitlabProjectPayload.setType(TEMPLATE);
        Exception exception = eventProducerTemplate.execute("CreateGitlabProject", "gitlab-service", gitlabProjectPayload,
                (String uuid) -> {
                    applicationTemplateE.initUuid(uuid);
                    if (applicationTemplateRepository.create(applicationTemplateE) == null) {
                        throw new CommonException("error.applicationTemplate.insert");
                    }
                });
        if (exception != null) {
            throw new CommonException(exception.getMessage());
        }
        return ConvertHelper.convert(applicationTemplateRepository.queryByCode(organization.getId(),
                applicationTemplateDTO.getCode()), ApplicationTemplateRepDTO.class);
    }

    @Override
    public ApplicationTemplateRepDTO update(ApplicationTemplateUpdateDTO applicationTemplateUpdateDTO, Long organizationId) {
        ApplicationTemplateE applicationTemplateE = ConvertHelper.convert(
                applicationTemplateUpdateDTO, ApplicationTemplateE.class);
        applicationTemplateE.initOrganization(organizationId);
        return ConvertHelper.convert(applicationTemplateRepository.update(applicationTemplateE),
                ApplicationTemplateRepDTO.class);
    }

    @Override
    public void delete(Long appTemplateId) {
        ApplicationTemplateE applicationTemplateE = applicationTemplateRepository.query(appTemplateId);
        if (applicationTemplateE.getGitlabProjectE() != null) {
            gitlabRepository.deleteProject(applicationTemplateE.getGitlabProjectE().getId());
        }
        applicationTemplateRepository.delete(appTemplateId);
    }

    @Override
    public ApplicationTemplateRepDTO query(Long appTemplateId) {
        ApplicationTemplateRepDTO applicationTemplateRepDTO = ConvertHelper.convert(applicationTemplateRepository
                        .query(appTemplateId),
                ApplicationTemplateRepDTO.class);
        setUrl(applicationTemplateRepDTO);
        return applicationTemplateRepDTO;
    }

    @Override
    public Page<ApplicationTemplateRepDTO> listByOptions(PageRequest pageRequest, Long organizationId, String searchParam) {
        Page<ApplicationTemplateRepDTO> applicationTemplateRepDTOPage = ConvertPageHelper
                .convertPage(applicationTemplateRepository.listByOptions(
                        pageRequest, organizationId, searchParam),
                        ApplicationTemplateRepDTO.class);
        List<ApplicationTemplateRepDTO> applicationTemplateRepDTOList = applicationTemplateRepDTOPage.getContent();
        for (ApplicationTemplateRepDTO applicationTemplateRepDTO : applicationTemplateRepDTOList) {
            setUrl(applicationTemplateRepDTO);
        }
        applicationTemplateRepDTOPage.setContent(applicationTemplateRepDTOList);
        return applicationTemplateRepDTOPage;
    }


    @Override
    public void operationApplicationTemplate(GitlabProjectEventDTO gitlabProjectEventDTO) {
        ApplicationTemplateE applicationTemplateE = applicationTemplateRepository.queryByCode(
                gitlabProjectEventDTO.getOrganizationId(), gitlabProjectEventDTO.getPath());

        applicationTemplateE.initGitlabProjectE(
                TypeUtil.objToInteger(gitlabProjectEventDTO.getGitlabProjectId()));
        applicationTemplateRepository.update(applicationTemplateE);
        String applicationDir = gitlabProjectEventDTO.getType() + System.currentTimeMillis();
        if (applicationTemplateE.getCopyFrom() != null) {
            ApplicationTemplateRepDTO templateRepDTO = ConvertHelper.convert(applicationTemplateRepository
                    .query(applicationTemplateE.getCopyFrom()), ApplicationTemplateRepDTO.class);
            //拉取模板
            Git git = gitUtil.clone(applicationDir, gitlabUrl + templateRepDTO.getRepoUrl());
            List<String> tokens = gitlabRepository.listTokenByUserName(gitlabProjectEventDTO.getGitlabProjectId(),
                    applicationDir, gitlabProjectEventDTO.getUserName());
            String accessToken = "";
            if (tokens.isEmpty()) {
                accessToken = gitlabRepository.createToken(gitlabProjectEventDTO.getGitlabProjectId(),
                        applicationDir, gitlabProjectEventDTO.getUserName());
            } else {
                accessToken = tokens.get(tokens.size() - 1);
            }
            gitUtil.push(git, applicationDir, gitlabUrl + applicationTemplateE.getRepoUrl(),
                    gitlabProjectEventDTO.getUserName(), accessToken, TEMPLATE);
        } else {
            gitlabRepository.createFile(gitlabProjectEventDTO.getGitlabProjectId(),
                    gitlabProjectEventDTO.getUserName());
        }
    }

    @Override
    public List<ApplicationTemplateRepDTO> list(Long organizationId) {
        List<ApplicationTemplateRepDTO> applicationTemplateRepDTOList = ConvertHelper.convertList(
                applicationTemplateRepository.list(organizationId),
                ApplicationTemplateRepDTO.class);
        for (ApplicationTemplateRepDTO applicationTemplateRepDTO : applicationTemplateRepDTOList) {
            setUrl(applicationTemplateRepDTO);
        }
        return applicationTemplateRepDTOList;
    }

    @Override
    public void checkName(Long organizationId, String name) {
        ApplicationTemplateE applicationTemplateE = ApplicationTemplateFactory.createApplicationTemplateE();
        applicationTemplateE.initOrganization(organizationId);
        applicationTemplateE.setName(name);
        applicationTemplateRepository.checkName(applicationTemplateE);
    }

    @Override
    public void checkCode(Long organizationId, String code) {
        ApplicationTemplateE applicationTemplateE = ApplicationTemplateFactory.createApplicationTemplateE();
        applicationTemplateE.initOrganization(organizationId);
        applicationTemplateE.setCode(code);
        applicationTemplateRepository.checkCode(applicationTemplateE);
    }

    @Override
    public Boolean applicationTemplateExist(String uuid) {
        return applicationTemplateRepository.applicationTemplateExist(uuid);
    }

    private ApplicationTemplateRepDTO setUrl(ApplicationTemplateRepDTO applicationTemplateRepDTO) {
        if (applicationTemplateRepDTO != null) {
            String repoUrl = gitlabUrl + applicationTemplateRepDTO.getRepoUrl();
            applicationTemplateRepDTO.setRepoUrl(repoUrl);
        }
        return applicationTemplateRepDTO;
    }
}
