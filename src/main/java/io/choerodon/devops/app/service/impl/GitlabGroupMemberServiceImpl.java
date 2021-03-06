package io.choerodon.devops.app.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import io.choerodon.devops.api.dto.GitlabGroupMemberDTO;
import io.choerodon.devops.app.service.GitlabGroupMemberService;
import io.choerodon.devops.domain.application.entity.gitlab.GitlabGroupE;
import io.choerodon.devops.domain.application.entity.gitlab.GitlabGroupMemberE;
import io.choerodon.devops.domain.application.entity.gitlab.GitlabUserE;
import io.choerodon.devops.domain.application.repository.DevopsProjectRepository;
import io.choerodon.devops.domain.application.repository.GitlabGroupMemberRepository;
import io.choerodon.devops.domain.application.repository.GitlabUserRepository;
import io.choerodon.devops.infra.common.util.enums.AccessLevel;
import io.choerodon.devops.infra.dataobject.gitlab.RequestMemberDO;

/**
 * Created Zenger qs on 2018/3/28.
 */
@Service
public class GitlabGroupMemberServiceImpl implements GitlabGroupMemberService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitlabGroupMemberServiceImpl.class);
    private static final String PROJECT = "project";

    private DevopsProjectRepository devopsProjectRepository;
    private GitlabUserRepository gitlabUserRepository;
    private GitlabGroupMemberRepository gitlabGroupMemberRepository;

    /**
     * 构造函数
     */
    public GitlabGroupMemberServiceImpl(DevopsProjectRepository devopsProjectRepository,
                                        GitlabUserRepository gitlabUserRepository,
                                        GitlabGroupMemberRepository gitlabGroupMemberRepository) {
        this.devopsProjectRepository = devopsProjectRepository;
        this.gitlabUserRepository = gitlabUserRepository;
        this.gitlabGroupMemberRepository = gitlabGroupMemberRepository;
    }

    @Override
    public void createGitlabGroupMemberRole(List<GitlabGroupMemberDTO> gitlabGroupMemberDTOList) {
        for (GitlabGroupMemberDTO gitlabGroupMemberDTO : gitlabGroupMemberDTOList) {
            if (PROJECT.equals(gitlabGroupMemberDTO.getResourceType())) {
                List<Integer> accessLevelList = new ArrayList<>();
                accessLevelList.add(0);
                List<String> userMemberRoleList = gitlabGroupMemberDTO.getRoleLabels();
                if (userMemberRoleList == null || userMemberRoleList.size() == 0) {
                    LOGGER.error("user member role is empty,{}");
                }
                AccessLevel level = getGitlabGroupMemberRole(userMemberRoleList);

                operation(gitlabGroupMemberDTO.getResourceId(), level,
                        gitlabGroupMemberDTO.getUsername());
            }
        }
    }

    @Override
    public void deleteGitlabGroupMemberRole(List<GitlabGroupMemberDTO> gitlabGroupMemberDTOList) {
        for (GitlabGroupMemberDTO gg : gitlabGroupMemberDTOList) {
            if (PROJECT.equals(gg.getResourceType())) {
                GitlabUserE gitlabUserE = gitlabUserRepository.getGitlabUserByUsername(gg.getUsername());
                if (gitlabUserE == null) {
                    LOGGER.error("error.gitlab.username.select: " + gg.getUsername());
                    return;
                }

                GitlabGroupE gitlabGroupE = devopsProjectRepository.queryDevopsProject(gg.getResourceId());
                if (gitlabGroupE.getId() == null) {
                    LOGGER.error("error.gitlab.groupId.select: " + gg.getResourceId());
                    return;
                }

                GitlabGroupMemberE grouoMemberE = gitlabGroupMemberRepository.getUserMemberByUserId(
                        gitlabGroupE.getId(),
                        gitlabUserE.getId());

                if (grouoMemberE != null) {
                    ResponseEntity removeMember = gitlabGroupMemberRepository.deleteMember(
                            gitlabGroupE.getId(),
                            gitlabUserE.getId());
                    if (removeMember.getStatusCode() != HttpStatus.NO_CONTENT) {
                        LOGGER.error("error.gitlab.member.remove");
                    }
                }
            }
        }
    }

    /**
     * get AccessLevel
     *
     * @param userMemberRoleList userMemberRoleList
     */
    public AccessLevel getGitlabGroupMemberRole(List<String> userMemberRoleList) {
        List<Integer> accessLevelList = new ArrayList<>();
        accessLevelList.add(0);
        userMemberRoleList.forEach(level -> {
            AccessLevel levels = AccessLevel.forString(level.toUpperCase());
            switch (levels) {
                case OWNER:
                    accessLevelList.add(levels.toValue());
                    break;
                case MASTER:
                    accessLevelList.add(levels.toValue());
                    break;
                case DEVELOPER:
                    accessLevelList.add(levels.toValue());
                    break;
                default:
                    break;
            }
        });
        return AccessLevel.forValue(Collections.max(accessLevelList));
    }

    /**
     * The user action
     *
     * @param projectId projectId
     * @param level     level
     * @param userName  userName
     */
    public void operation(Long projectId, AccessLevel level, String userName) {
        GitlabUserE gitlabUserE = gitlabUserRepository.getGitlabUserByUsername(userName);
        if (gitlabUserE == null) {
            LOGGER.error("error.gitlab.username.select: " + userName);
            return;
        }

        GitlabGroupE gitlabGroupE = devopsProjectRepository.queryDevopsProject(projectId);
        if (gitlabGroupE.getId() == null) {
            LOGGER.error("error.gitlab.groupId.select: " + projectId);
            return;
        }

        GitlabGroupMemberE grouoMemberE = gitlabGroupMemberRepository.getUserMemberByUserId(
                gitlabGroupE.getId(),
                gitlabUserE.getId());

        // 增删改用户
        switch (level) {
            case NONE:
                if (grouoMemberE != null) {
                    ResponseEntity removeMember = gitlabGroupMemberRepository.deleteMember(
                            gitlabGroupE.getId(),
                            gitlabUserE.getId());
                    if (removeMember.getStatusCode() != HttpStatus.NO_CONTENT) {
                        LOGGER.error("error.gitlab.member.remove");
                    }
                }
                break;
            case DEVELOPER:
            case MASTER:
            case OWNER:
                RequestMemberDO requestMember = new RequestMemberDO();
                requestMember.setUserId(gitlabUserE.getId());
                requestMember.setAccessLevel(level.toValue());
                requestMember.setExpiresAt("");
                if (grouoMemberE == null) {
                    int addMember = gitlabGroupMemberRepository.insertMember(
                            gitlabGroupE.getId(),
                            requestMember);
                    if (addMember != HttpStatus.CREATED.value()) {
                        LOGGER.error("error.gitlab.member.insert");
                    }
                } else {
                    ResponseEntity updateMember = gitlabGroupMemberRepository.updateMember(
                            gitlabGroupE.getId(),
                            requestMember);
                    if (updateMember.getStatusCode() != HttpStatus.OK) {
                        LOGGER.error("error.gitlab.member.update");
                    }
                }
                break;
            default:
                LOGGER.error("error.gitlab.member.level");
                break;
        }
    }
}
