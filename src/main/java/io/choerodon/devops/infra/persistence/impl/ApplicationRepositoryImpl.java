package io.choerodon.devops.infra.persistence.impl;

import java.util.List;
import java.util.Map;

import io.kubernetes.client.JSON;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Service;

import io.choerodon.core.convertor.ConvertHelper;
import io.choerodon.core.convertor.ConvertPageHelper;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.domain.application.entity.ApplicationE;
import io.choerodon.devops.domain.application.repository.ApplicationRepository;
import io.choerodon.devops.infra.common.util.TypeUtil;
import io.choerodon.devops.infra.dataobject.ApplicationDO;
import io.choerodon.devops.infra.mapper.ApplicationMapper;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;

/**
 * Created by younger on 2018/3/28.
 */
@Service
public class ApplicationRepositoryImpl implements ApplicationRepository {

    private JSON json = new JSON();

    private ApplicationMapper applicationMapper;

    public ApplicationRepositoryImpl(ApplicationMapper applicationMapper) {
        this.applicationMapper = applicationMapper;
    }

    @Override
    public void checkName(ApplicationE applicationE) {
        ApplicationDO applicationDO = new ApplicationDO();
        applicationDO.setProjectId(applicationE.getProjectE().getId());
        applicationDO.setName(applicationE.getName());
        if (!applicationMapper.select(applicationDO).isEmpty()) {
            throw new CommonException("error.name.exist");
        }
    }

    @Override
    public void checkCode(ApplicationE applicationE) {
        ApplicationDO applicationDO = new ApplicationDO();
        applicationDO.setProjectId(applicationE.getProjectE().getId());
        applicationDO.setCode(applicationE.getCode());
        if (!applicationMapper.select(applicationDO).isEmpty()) {
            throw new CommonException("error.code.exist");
        }
    }

    @Override
    public int create(ApplicationE applicationE) {
        return applicationMapper.insert(ConvertHelper.convert(applicationE, ApplicationDO.class));
    }


    @Override
    public int update(ApplicationE applicationE) {
        ApplicationDO applicationDO = applicationMapper.selectByPrimaryKey(applicationE.getId());
        ApplicationDO newApplicationDO = ConvertHelper.convert(applicationE, ApplicationDO.class);
        newApplicationDO.setObjectVersionNumber(applicationDO.getObjectVersionNumber());
        return applicationMapper.updateByPrimaryKeySelective(newApplicationDO);
    }

    @Override
    public ApplicationE query(Long applicationId) {
        ApplicationDO applicationDO = applicationMapper.selectByPrimaryKey(applicationId);
        return ConvertHelper.convert(applicationDO, ApplicationE.class);
    }

    @Override
    public Page<ApplicationE> listByOptions(Long projectId, PageRequest pageRequest, String params) {
        Page<ApplicationDO> applicationES;
        if (!StringUtils.isEmpty(params)) {
            Map<String, Object> maps = json.deserialize(params, Map.class);
            if (maps.get(TypeUtil.SEARCH_PARAM).equals("")) {
                applicationES = PageHelper.doPageAndSort(
                        pageRequest, () -> applicationMapper.list(
                                projectId, null,
                                TypeUtil.cast(maps.get(TypeUtil.PARAM))));
            } else {
                applicationES = PageHelper.doPageAndSort(
                        pageRequest, () -> applicationMapper.list(
                                projectId, TypeUtil.cast(maps.get(TypeUtil.SEARCH_PARAM)),
                                TypeUtil.cast(maps.get(TypeUtil.PARAM))));
            }
        } else {
            applicationES = PageHelper.doPageAndSort(
                    pageRequest, () -> applicationMapper.list(projectId, null, null));
        }
        return ConvertPageHelper.convertPage(applicationES, ApplicationE.class);
    }

    @Override
    public Boolean applicationExist(String uuid) {
        ApplicationDO applicationDO = new ApplicationDO();
        applicationDO.setUuid(uuid);
        return !applicationMapper.select(applicationDO).isEmpty();
    }

    @Override
    public ApplicationE queryByCode(String code, Long projectId) {
        ApplicationDO applicationDO = new ApplicationDO();
        applicationDO.setProjectId(projectId);
        applicationDO.setCode(code);
        List<ApplicationDO> applicationDOS = applicationMapper.select(applicationDO);
        if (!applicationDOS.isEmpty()) {
            return ConvertHelper.convert(applicationDOS.get(0), ApplicationE.class);
        } else {
            throw new CommonException("error.application.get");
        }
    }

    @Override
    public List<ApplicationE> listByEnvId(Long projectId, Long envId, String status) {
        return ConvertHelper.convertList(applicationMapper.listByEnvId(projectId, envId, status), ApplicationE.class);
    }

    @Override
    public List<ApplicationE> listByActive(Long projectId) {
        ApplicationDO applicationDO = new ApplicationDO();
        applicationDO.setProjectId(projectId);
        applicationDO.setActive(true);
        return ConvertHelper.convertList(applicationMapper.select(applicationDO), ApplicationE.class);
    }

    @Override
    public List<ApplicationE> listByActiveAndPubAndVersion(Long projectId, Boolean isActive) {
        return ConvertHelper.convertList(applicationMapper
                .listByActiveAndPubAndVersion(projectId, isActive), ApplicationE.class);
    }

    @Override
    public ApplicationE queryByToken(String token) {
        ApplicationDO applicationDO = applicationMapper.queryByToken(token);
        return ConvertHelper.convert(applicationDO, ApplicationE.class);
    }
}
